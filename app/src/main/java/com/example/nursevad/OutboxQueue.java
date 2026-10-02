package com.example.nursevad;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent FIFO outbox with two priority lanes (INTERACTIVE jumps ahead of BULK),
 * per-chat rate limiting (<=54 msg/min private, <=18 msg/min groups) and a global
 * pause driven by Telegram 429 retry_after.
 * Metadata is persisted as one small JSON file per queued item in <files>/tg_outbox,
 * so unsent messages survive crashes and reboots.
 */
public class OutboxQueue {

    public enum Lane { INTERACTIVE, BULK }

    public static class Item {
        public long seq;
        public Lane lane;
        public String type;      // TEXT | TEXT_RAW_MENU | AUDIO | EDIT_TEXT | EDIT_MAIN | EDIT_SETTINGS
        public long chatId;
        public int messageId;
        public String text;
        public String filePath;
        public String caption;
        public String title;
        public String performer;
        public int attempts;
        public File file;        // backing metadata file on disk
    }

    public interface SendCallback {
        /** @return 0 = sent ok; 429 = flood (fill retryAfterSec[0]); -1 = transient; -2 = permanent fail */
        int send(Item item, int[] retryAfterSec);
    }

    private static final long INTERVAL_PRIVATE_MS = 60_000L / 54 + 1;  // ~1112 ms
    private static final long INTERVAL_GROUP_MS   = 60_000L / 18 + 1;  // ~3334 ms
    private static final int  MAX_ATTEMPTS = 3;

    private File dir;
    private SendCallback callback;
    private final ArrayDeque<Item> interactive = new ArrayDeque<>();
    private final ArrayDeque<Item> bulk = new ArrayDeque<>();
    private final Map<Long, Long> lastSendPerChat = new HashMap<>();
    private final AtomicLong seqGen = new AtomicLong(0);
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean stopped = true;
    private volatile long globalPauseUntil = 0;

    public void init(Context context, SendCallback callback) {
        this.callback = callback;
        dir = new File(context.getFilesDir(), "tg_outbox");
        if (!dir.exists()) dir.mkdirs();
        loadFromDisk();
    }

    // ─── persistence ───

    private void loadFromDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        long maxSeq = 0;
        for (File f : files) {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                Item it = fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
                it.file = f;
                (it.lane == Lane.INTERACTIVE ? interactive : bulk).addLast(it);
                if (it.seq > maxSeq) maxSeq = it.seq;
            } catch (Exception e) {
                DebugLogger.log("Outbox: failed to load " + f.getName() + ": " + e);
                f.delete();
            }
        }
        seqGen.set(Math.max(maxSeq + 1, System.currentTimeMillis()));
        DebugLogger.log("Outbox loaded from disk: interactive=" + interactive.size() + " bulk=" + bulk.size());
    }

    private JSONObject toJson(Item it) {
        JSONObject o = new JSONObject();
        o.put("seq", it.seq);
        o.put("lane", it.lane == Lane.INTERACTIVE ? 0 : 1);
        o.put("type", it.type);
        o.put("chatId", it.chatId);
        o.put("messageId", it.messageId);
        o.put("text", it.text);
        o.put("filePath", it.filePath);
        o.put("caption", it.caption);
        o.put("title", it.title);
        o.put("performer", it.performer);
        return o;
    }

    private Item fromJson(JSONObject o) {
        Item it = new Item();
        it.seq = o.optLong("seq");
        it.lane = o.optInt("lane") == 0 ? Lane.INTERACTIVE : Lane.BULK;
        it.type = o.optString("type", "TEXT");
        it.chatId = o.optLong("chatId");
        it.messageId = o.optInt("messageId");
        it.text = o.optString("text", null);
        it.filePath = o.optString("filePath", null);
        it.caption = o.optString("caption", null);
        it.title = o.optString("title", null);
        it.performer = o.optString("performer", null);
        return it;
    }

    // ─── enqueue ───

    public void enqueue(Lane lane, String type, long chatId, int messageId, String text,
                        String filePath, String caption, String title, String performer) {
        if (dir == null) return;
        Item it = new Item();
        it.seq = seqGen.getAndIncrement();
        it.lane = lane;
        it.type = type;
        it.chatId = chatId;
        it.messageId = messageId;
        it.text = text;
        it.filePath = filePath;
        it.caption = caption;
        it.title = title;
        it.performer = performer;

        File f = new File(dir, String.format(java.util.Locale.US, "%013d_%d.json",
                it.seq, lane == Lane.INTERACTIVE ? 0 : 1));
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(toJson(it).toString());
        } catch (Exception e) {
            DebugLogger.log("Outbox: persist failed: " + e);
        }
        it.file = f;
        synchronized (lock) {
            (lane == Lane.INTERACTIVE ? interactive : bulk).addLast(it);
            lock.notifyAll();
        }
    }

    public void enqueueText(long chatId, String text) {
        enqueue(Lane.INTERACTIVE, "TEXT", chatId, 0, text, null, null, null, null);
    }

    public void enqueueAudio(long chatId, String filePath, String caption, String title, String performer) {
        enqueue(Lane.BULK, "AUDIO", chatId, 0, null, filePath, caption, title, performer);
    }

    // ─── worker ───

    public void startWorker() {
        stopped = false;
        worker = new Thread(this::run, "TgOutboxWorker");
        worker.setDaemon(true);
        worker.start();
    }

    public void stopWorker() {
        stopped = true;
        synchronized (lock) { lock.notifyAll(); }
        if (worker != null) worker.interrupt();
    }

    public int depth() {
        synchronized (lock) { return interactive.size() + bulk.size(); }
    }

    private Item peekNext() {
        synchronized (lock) {
            Item it = interactive.peekFirst();
            return it != null ? it : bulk.peekFirst();
        }
    }

    private void dropHead(Item it) {
        synchronized (lock) {
            (it.lane == Lane.INTERACTIVE ? interactive : bulk).removeFirst();
        }
        if (it.file != null) it.file.delete();
    }

    private long rateWaitMs(long chatId) {
        long interval = chatId < 0 ? INTERVAL_GROUP_MS : INTERVAL_PRIVATE_MS;
        Long last = lastSendPerChat.get(chatId);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return elapsed >= interval ? 0 : interval - elapsed;
    }

    private void run() {
        while (!stopped) {
            try {
                long now = System.currentTimeMillis();
                if (now < globalPauseUntil) {
                    Thread.sleep(Math.min(globalPauseUntil - now, 2000));
                    continue;
                }
                Item item = peekNext();
                if (item == null) {
                    synchronized (lock) { lock.wait(1000); }
                    continue;
                }
                long wait = rateWaitMs(item.chatId);
                if (wait > 0) {
                    Thread.sleep(Math.min(wait, 2000));
                    continue;
                }

                lastSendPerChat.put(item.chatId, System.currentTimeMillis());
                int[] retry = new int[1];
                int code = callback.send(item, retry);

                if (code == 0) {
                    dropHead(item);
                } else if (code == 429) {
                    int secs = retry[0] > 0 ? retry[0] : 5;
                    globalPauseUntil = System.currentTimeMillis() + secs * 1000L + 1000L;
                    DebugLogger.log("Telegram 429: retry_after=" + secs + "s, queueDepth=" + depth());
                    // item stays at head and is resent after the pause
                } else if (code == -1) {
                    item.attempts++;
                    if (item.attempts >= MAX_ATTEMPTS) {
                        DebugLogger.log("Outbox: dropping seq=" + item.seq + " after " + item.attempts + " attempts");
                        dropHead(item);
                    } else {
                        Thread.sleep(1500);
                    }
                } else { // -2 permanent (400/403/404 ...) — details are in log_errors.txt
                    DebugLogger.log("Outbox: permanent failure, dropping seq=" + item.seq + " type=" + item.type);
                    dropHead(item);
                }
            } catch (InterruptedException ie) {
                if (stopped) break;
            } catch (Exception e) {
                DebugLogger.log("Outbox worker error: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
        DebugLogger.log("Outbox worker stopped");
    }
}