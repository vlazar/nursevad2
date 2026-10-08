package com.example.nursevad;

import android.content.Context;
import android.os.SystemClock;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendAudio;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TelegramOutbox {

    public enum Lane { INTERACTIVE, BULK }
    public enum Kind { TEXT, EDIT, AUDIO }

    public static class Item {
        public final String id = UUID.randomUUID().toString();
        public Lane lane;
        public Kind kind;
        public long chatId;
        public String text;
        public Integer editMessageId;
        public Integer replyToMessageId;
        public InlineKeyboardMarkup markup;
        public String filePath;
        public String caption;
        public String title;
        public String performer;
        public int level;
        public boolean isPoni;
        public String captionBody;
        public String wavPath;
        public long speechStartMs;
        public long speechEndMs;
        public int attempts;
        public long enqueuedAt = SystemClock.elapsedRealtime();
        public long readyAt;
        // Stitch state
        public boolean noStitch = false;
        public List<Item> stitchMembers = null;   // head carries the group on retry
        public String overrideFilePath = null;    // prebuilt stitched OGG on retry
        public String stitchedCaption = null;
    }

    public interface OutboxListener { void onFatalGlobalError(String description); }

    private static final long SAFE_INTERVAL_GROUP_MS = 3334;
    private static final long SAFE_INTERVAL_DM_MS    = 1112;
    private static final int  SAFE_COUNT_GROUP = 18;   // per 60 s
    private static final int  SAFE_COUNT_DM    = 54;   // per 60 s
    private static final long RETRY_PADDING_MS = 1000;
    private static final int  MAX_ATTEMPTS = 3;
    private static final long TRANSIENT_BACKOFF_MS = 1000;
    private static final long PACE_WINDOW_MS = 60000;
    private static final int  CAPTION_MAX = 1024;

    private static TelegramOutbox instance;
    public static synchronized TelegramOutbox getInstance() {
        if (instance == null) instance = new TelegramOutbox();
        return instance;
    }

    private TelegramBot bot;
    private Context appContext;
    private OutboxListener listener;
    private File queueFile;

    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean running = false;

    private final ArrayDeque<Item> interactive = new ArrayDeque<>();
    private final ArrayDeque<Item> bulk = new ArrayDeque<>();
    private final Map<Long, ArrayDeque<Long>> bulkEnqueueWindow = new HashMap<>();

    private static class ChatState {
        long nextSendAtMs = 0;
        long pausedUntilMs = 0;
        boolean dead = false;
        boolean wasPaused = false;   // NEW: for resume-after-429 logging
    }
    private final Map<Long, ChatState> chats = new HashMap<>();
    private boolean kickNoticeSent = false;
    private long lastHeartbeatMs = 0;
    private boolean wasNonEmpty = false;

    public void attach(TelegramBot bot, Context context, OutboxListener listener) {
        this.bot = bot;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        File base = appContext.getExternalFilesDir(null);
        if (base == null) base = appContext.getFilesDir();
        File dir = new File(base, "outbox");
        if (!dir.exists()) dir.mkdirs();
        queueFile = new File(dir, "bulk_queue.json");
    }

    public void startWorker() {
        synchronized (lock) {
            if (running && worker != null && worker.isAlive()) return;
            loadBulkLocked();
            running = true;
            worker = new Thread(this::workerLoop, "TelegramOutbox");
            worker.setDaemon(true);
            worker.start();
            DebugLogger.log("Outbox worker started (bulk depth after load=" + bulk.size() + ")");
        }
    }

    public void shutdown() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
    }

    // ── Persistence ──

    private void persistBulkLocked() {
        if (queueFile == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Item i : bulk) {
                JSONObject o = new JSONObject();
                o.put("id", i.id);
                o.put("chatId", i.chatId);
                o.put("filePath", i.filePath);
                o.put("caption", i.caption);
                o.put("title", i.title);
                o.put("performer", i.performer);
                o.put("level", i.level);
                o.put("isPoni", i.isPoni);
                o.put("captionBody", i.captionBody);
                if (i.wavPath != null) o.put("wavPath", i.wavPath);
                o.put("speechStartMs", i.speechStartMs);
                o.put("speechEndMs", i.speechEndMs);
                o.put("attempts", i.attempts);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("items", arr);
            File tmp = new File(queueFile.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
            fos.close();
            if (!tmp.renameTo(queueFile)) DebugLogger.log("Outbox persist: rename failed");
        } catch (Exception e) {
            DebugLogger.log("Outbox persist failed: " + e);
        }
    }

    private void loadBulkLocked() {
        bulk.clear();
        bulkEnqueueWindow.clear();
        if (queueFile == null || !queueFile.exists()) return;
        try {
            FileInputStream fis = new FileInputStream(queueFile);
            byte[] data = new byte[(int) queueFile.length()];
            int read = 0;
            while (read < data.length) {
                int r = fis.read(data, read, data.length - read);
                if (r < 0) break;
                read += r;
            }
            fis.close();
            JSONObject root = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            long now = SystemClock.elapsedRealtime();
            int dropped = 0;
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                String filePath = o.optString("filePath", "");
                if (filePath.isEmpty() || !new File(filePath).exists()) { dropped++; continue; }
                Item i = new Item();
                i.lane = Lane.BULK;
                i.kind = Kind.AUDIO;
                i.chatId = o.optLong("chatId");
                i.filePath = filePath;
                i.caption = o.optString("caption", "");
                i.title = o.optString("title", "Speech Detected");
                i.performer = o.optString("performer", "Someone");
                i.level = o.optInt("level", 1);
                i.isPoni = o.optBoolean("isPoni", false);
                i.captionBody = o.optString("captionBody", "");
                i.wavPath = o.optString("wavPath", null);
                i.speechStartMs = o.optLong("speechStartMs", 0);
                i.speechEndMs = o.optLong("speechEndMs", 0);
                i.attempts = o.optInt("attempts", 0);
                i.enqueuedAt = now;
                bulk.addLast(i);
            }
            DebugLogger.log("Outbox loaded bulk queue: " + bulk.size() + " items, dropped " + dropped + " (missing artifacts)");
            if (dropped > 0) persistBulkLocked();
        } catch (Exception e) {
            DebugLogger.log("Outbox bulk queue corrupt, discarding: " + e);
            bulk.clear();
            persistBulkLocked();
        }
    }

    private void persistIfBulk(Item i) {
        if (i.lane == Lane.BULK) {
            synchronized (lock) { persistBulkLocked(); }
        }
    }

    // ── Enqueue API ──

    public void enqueueText(long chatId, String text, InlineKeyboardMarkup markup) {
        enqueueText(chatId, text, markup, null);
    }

    public void enqueueText(long chatId, String text, InlineKeyboardMarkup markup, Integer replyTo) {
        Item i = new Item();
        i.lane = Lane.INTERACTIVE; i.kind = Kind.TEXT; i.chatId = chatId;
        i.text = text; i.markup = markup; i.replyToMessageId = replyTo;
        add(i);
    }

    public void enqueueEdit(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        Item i = new Item();
        i.lane = Lane.INTERACTIVE; i.kind = Kind.EDIT; i.chatId = chatId;
        i.editMessageId = messageId; i.text = text; i.markup = markup;
        synchronized (lock) {
            Iterator<Item> it = interactive.iterator();
            while (it.hasNext()) {
                Item old = it.next();
                if (old.kind == Kind.EDIT && old.chatId == chatId
                        && old.editMessageId != null && old.editMessageId.intValue() == messageId) {
                    it.remove();
                }
            }
            addLocked(i);
        }
    }

    public void enqueueAudio(long chatId, String filePath, String caption, String title, String performer,
                             int level, boolean isPoni, String captionBody, String wavPath,
                             long speechStartMs, long speechEndMs) {
        Item i = new Item();
        i.lane = Lane.BULK; i.kind = Kind.AUDIO; i.chatId = chatId;
        i.filePath = filePath; i.caption = caption; i.title = title; i.performer = performer;
        i.level = level; i.isPoni = isPoni; i.captionBody = captionBody; i.wavPath = wavPath;
        i.speechStartMs = speechStartMs; i.speechEndMs = speechEndMs;
        add(i);
    }

    private void add(Item i) {
        synchronized (lock) { addLocked(i); }
    }

    private void addLocked(Item i) {
        if (state(i.chatId).dead) {
            DebugLogger.log("Outbox: dropping item for dead chat " + i.chatId);
            return;
        }
        (i.lane == Lane.INTERACTIVE ? interactive : bulk).addLast(i);
        if (i.lane == Lane.BULK) {
            ArrayDeque<Long> w = bulkEnqueueWindow.get(i.chatId);
            if (w == null) { w = new ArrayDeque<>(); bulkEnqueueWindow.put(i.chatId, w); }
            w.addLast(SystemClock.elapsedRealtime());
            persistBulkLocked();
        }
        DebugLogger.log("Outbox enqueue: lane=" + i.lane + " kind=" + i.kind + " chat=" + i.chatId
                + (isGroup(i.chatId) ? "(group)" : "(dm)")
                + " depths i=" + interactive.size() + " b=" + bulk.size());
        lock.notifyAll();
    }

    private ChatState state(long chatId) {
        ChatState cs = chats.get(chatId);
        if (cs == null) { cs = new ChatState(); chats.put(chatId, cs); }
        return cs;
    }

    private boolean isGroup(long chatId) {
        Long group = SettingsManager.getGroupChatIdLong(appContext);
        return group != null && group.longValue() == chatId;
    }

    private long intervalFor(long chatId) {
        return isGroup(chatId) ? SAFE_INTERVAL_GROUP_MS : SAFE_INTERVAL_DM_MS;
    }

    private int safeCountFor(long chatId) {
        return isGroup(chatId) ? SAFE_COUNT_GROUP : SAFE_COUNT_DM;
    }

    // ── Worker ──

    private void workerLoop() {
        while (running) {
            List<Item> group;
            synchronized (lock) {
                long now = SystemClock.elapsedRealtime();
                long nowMs = SystemClock.elapsedRealtime();
                int di = depth(Lane.INTERACTIVE);
                int db = depth(Lane.BULK);
                if (di + db > 0) {
                    wasNonEmpty = true;
                    if (nowMs - lastHeartbeatMs >= 300000) {   // 5-minute heartbeat while non-empty
                        lastHeartbeatMs = nowMs;
                        DebugLogger.log("Outbox heartbeat: depths i=" + di + " b=" + db);
                    }
                } else if (wasNonEmpty) {
                    wasNonEmpty = false;
                    DebugLogger.log("Outbox drained: all lanes empty");
                }
                group = pickReady(now);
                if (group == null) {
                    long wait = computeWait(now);
                    try { lock.wait(wait); } catch (InterruptedException e) { break; }
                    continue;
                }
            }
            try {
                dispatch(group);
            } catch (Throwable t) {
                DebugLogger.log("Outbox dispatch crash: " + t);
            }
        }
        DebugLogger.log("Outbox worker stopped");
    }

    private List<Item> pickReady(long now) { // lock held
        Item i = pickFrom(interactive, now);
        if (i != null) {
            List<Item> single = new ArrayList<>(1);
            single.add(i);
            return single;
        }
        return pickBulkGroup(now);
    }

    private Item pickFrom(ArrayDeque<Item> deque, long now) { // lock held
        Iterator<Item> it = deque.iterator();
        while (it.hasNext()) {
            Item i = it.next();
            ChatState cs = state(i.chatId);
            if (cs.dead) continue;
            if (now < cs.pausedUntilMs) continue;
            if (now < cs.nextSendAtMs) continue;
            if (now < i.readyAt) continue;
            it.remove();
            return i;
        }
        return null;
    }

    private List<Item> pickBulkGroup(long now) { // lock held
        Item head = pickFrom(bulk, now);
        if (head == null) return null;
        List<Item> group = new ArrayList<>();
        group.add(head);

        if (head.kind == Kind.AUDIO && head.stitchMembers == null
                && head.overrideFilePath == null && !head.noStitch
                && stitchActiveLocked(head.chatId)) {
            int k = computeK(head.chatId);
            Iterator<Item> it = bulk.iterator();
            while (it.hasNext() && group.size() < k) {
                Item cand = it.next();
                if (cand.kind == Kind.AUDIO && cand.chatId == head.chatId
                        && cand.level == head.level && cand.isPoni == head.isPoni
                        && !cand.noStitch && cand.stitchMembers == null && cand.overrideFilePath == null
                        && cand.wavPath != null && new File(cand.wavPath).exists()) {
                    it.remove();
                    group.add(cand);
                } else {
                    break; // consecutive siblings only
                }
            }
        }

        if (group.size() > 1) {
            head.stitchMembers = group;
            int k = computeK(head.chatId);
            StringBuilder mb = new StringBuilder();
            for (Item m : group) { if (mb.length() > 0) mb.append(","); mb.append(m.speechStartMs); }
            DebugLogger.log("Outbox stitch group formed: chat=" + head.chatId
                    + " size=" + group.size() + " level=" + head.level + " poni=" + head.isPoni
                    + " k=" + k + " membersStartMs=[" + mb + "]");
        }
        return group;
    }

    private boolean stitchActiveLocked(long chatId) { // lock held
        int depth = 1; // head already removed
        for (Item i : bulk) if (i.chatId == chatId) depth++;
        if (depth < 2) return false;
        long now = SystemClock.elapsedRealtime();
        ArrayDeque<Long> w = bulkEnqueueWindow.get(chatId);
        if (w == null) return false;
        while (!w.isEmpty() && now - w.peekFirst() > PACE_WINDOW_MS) w.pollFirst();
        return w.size() > safeCountFor(chatId);
    }

    private int computeK(long chatId) { // lock held
        int depth = 1; // head already removed from deque
        for (Item i : bulk) if (i.chatId == chatId) depth++;

        ArrayDeque<Long> w = bulkEnqueueWindow.get(chatId);
        int rate = (w == null) ? 0 : w.size(); // window is pruned in stitchActiveLocked

        int safe = safeCountFor(chatId);
        int kRate  = (int) Math.ceil(rate  / (double) safe);
        int kDepth = (int) Math.ceil(depth / (double) safe);
        int k = Math.max(2, Math.max(kRate, kDepth));
        return k;
    }

    private int depthOf(long chatId) {
      int d = 0;
      for (Item i : bulk) if (i.chatId == chatId) d++;
      return d;
    }

    private long computeWait(long now) {
        long min = Math.min(earliest(interactive), earliest(bulk));
        if (min == Long.MAX_VALUE) return 30000;
        return Math.max(50, min - now);
    }

    private long earliest(ArrayDeque<Item> deque) {
        long min = Long.MAX_VALUE;
        for (Item i : deque) {
            ChatState cs = state(i.chatId);
            if (cs.dead) continue;
            min = Math.min(min, Math.max(Math.max(cs.pausedUntilMs, cs.nextSendAtMs), i.readyAt));
        }
        return min;
    }

    // ── Dispatch ──

    private void dispatch(List<Item> group) {
        if (group.size() == 1) dispatchSingle(group.get(0));
        else dispatchStitched(group);
    }

    private void dispatchSingle(Item item) {
        long dispatchNow = SystemClock.elapsedRealtime();
        ChatState cst = state(item.chatId);
        if (cst.wasPaused) {
            cst.wasPaused = false;
            DebugLogger.log("Outbox resumed sending to chat=" + cst + " after 429 pause");
        }
        BaseResponse resp = null;
        RuntimeException failure = null;
        try {
            switch (item.kind) {
                case TEXT: {
                    SendMessage req = new SendMessage(item.chatId, item.text);
                    if (item.markup != null) req.replyMarkup(item.markup);
                    if (item.replyToMessageId != null && item.replyToMessageId > 0) req.replyToMessageId(item.replyToMessageId);
                    resp = bot.execute(req);
                    break;
                }
                case EDIT: {
                    EditMessageText req = new EditMessageText(item.chatId, item.editMessageId, item.text);
                    if (item.markup != null) req.replyMarkup(item.markup);
                    resp = bot.execute(req);
                    break;
                }
                case AUDIO: {
                    SendAudio req = new SendAudio(item.chatId, new File(item.filePath))
                            .caption(item.caption)
                            .title(item.title)
                            .performer(item.performer);
                    resp = bot.execute(req);
                    break;
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        long now = SystemClock.elapsedRealtime();
        ChatState cs = state(item.chatId);

        if (failure != null) { handleTransient(item, now, "exception: " + failure); return; }
        if (resp.isOk()) {
            cs.nextSendAtMs = dispatchNow + intervalFor(item.chatId);
            DebugLogger.log("Outbox sent ok: kind=" + item.kind + " chat=" + item.chatId
                    + " uploadMs=" + (now - dispatchNow)
                    + " depths i=" + depth(Lane.INTERACTIVE) + " b=" + depth(Lane.BULK));
            if (item.kind == Kind.AUDIO) deleteMemberWav(item); // commit point
            persistIfBulk(item);
            return;
        }
        int code = resp.errorCode();
        String desc = resp.description();
        if (code == 429) {
            int retryAfter = (resp.parameters() != null && resp.parameters().retryAfter() != null)
                    ? resp.parameters().retryAfter() : 0;
            cs.pausedUntilMs = now + retryAfter * 1000L + RETRY_PADDING_MS;
            cs.wasPaused = true;
            String line = "Outbox 429: chat=" + item.chatId + " retry_after=" + retryAfter
                    + "s depths i=" + depth(Lane.INTERACTIVE) + " b=" + depth(Lane.BULK);
            DebugLogger.log(line);
            DebugLogger.logError(line + " | " + desc);
            requeue(item);
            return;
        }
        if (code == 401 || code == 404) { handleFatalGlobal(code, desc); return; }
        if (code == 403 || code == 400) { cs.dead = true; purgeChat(item.chatId); return; }
        handleTransient(item, now, "http " + code + " " + desc);
    }

    private void dispatchStitched(List<Item> group) {
        Item head = group.get(0);
        long dispatchNow = SystemClock.elapsedRealtime();
        ChatState cst = state(head.chatId);
        if (cst.wasPaused) {
            cst.wasPaused = false;
            DebugLogger.log("Outbox resumed sending to chat=" + cst + " after 429 pause");
        }

        String oggPath = head.overrideFilePath;
        if (oggPath == null) {
            File dir = new File(head.wavPath).getParentFile();
            File sw = new File(dir, "stitched_" + System.currentTimeMillis() + ".wav");
            File so = new File(sw.getAbsolutePath() + ".ogg");
            boolean ok = SpeechStitcher.buildStitchedWav(group, sw);
            if (ok) ok = OpusTranscoder.transcodeWavToOpusOgg(sw, so);
            if (!ok) {
                DebugLogger.log("Outbox stitch build/transcode failed; dissolving group size=" + group.size());
                synchronized (lock) {
                    head.noStitch = true;
                    head.stitchMembers = null;
                    for (int i = group.size() - 1; i >= 0; i--) bulk.addFirst(group.get(i));
                    lock.notifyAll();
                }
                return;
            }
            if (sw.exists()) sw.delete();
            oggPath = so.getAbsolutePath();
            head.overrideFilePath = oggPath;
            head.stitchedCaption = buildStitchedCaption(group);
        }

        boolean useEmb = SettingsManager.getUseEmbeddings(appContext);
        String performer = (useEmb && !head.isPoni) ? "Client" : "Someone";

        BaseResponse resp = null;
        RuntimeException failure = null;
        try {
            SendAudio req = new SendAudio(head.chatId, new File(oggPath))
                    .caption(head.stitchedCaption)
                    .title("Speech Detected")
                    .performer(performer);
            resp = bot.execute(req);
        } catch (RuntimeException e) {
            failure = e;
        }
        long now = SystemClock.elapsedRealtime();
        ChatState cs = state(head.chatId);

        if (failure != null) { handleTransientStitched(head, now, "exception: " + failure); return; }
        if (resp.isOk()) {
            cs.nextSendAtMs = dispatchNow + intervalFor(head.chatId);
            DebugLogger.log("Outbox sent ok STITCHED group=" + group.size()
                    + " chat=" + head.chatId + " uploadMs=" + (now - dispatchNow)
                    + " depths i=" + depth(Lane.INTERACTIVE) + " b=" + depth(Lane.BULK));
            cleanupStitched(head);
            synchronized (lock) { persistBulkLocked(); } // commit removal of all members
            return;
        }
        int code = resp.errorCode();
        String desc = resp.description();
        if (code == 429) {
            int retryAfter = (resp.parameters() != null && resp.parameters().retryAfter() != null)
                    ? resp.parameters().retryAfter() : 0;
            cs.pausedUntilMs = now + retryAfter * 1000L + RETRY_PADDING_MS;
            cs.wasPaused = true;
            String line = "Outbox 429: chat=" + head.chatId + " retry_after=" + retryAfter
                    + "s depths i=" + depth(Lane.INTERACTIVE) + " b=" + depth(Lane.BULK);
            DebugLogger.log(line);
            DebugLogger.logError(line + " | " + desc);
            requeue(head);
            return;
        }
        if (code == 401 || code == 404) { handleFatalGlobal(code, desc); return; }
        if (code == 403 || code == 400) { cs.dead = true; cleanupStitched(head); purgeChat(head.chatId); return; }
        handleTransientStitched(head, now, "http " + code + " " + desc);
    }

    private void deleteMemberWav(Item i) {
        if (i.wavPath != null && !i.wavPath.equals(i.filePath)) {
            File f = new File(i.wavPath);
            if (f.exists()) f.delete();
        }
    }

    private void cleanupStitched(Item head) {
        if (head.overrideFilePath != null) {
            File ogg = new File(head.overrideFilePath);
            if (ogg.exists()) ogg.delete();
            String wavPath = head.overrideFilePath.endsWith(".ogg")
                    ? head.overrideFilePath.substring(0, head.overrideFilePath.length() - 4) + ".wav"
                    : null;
            if (wavPath != null) { File w = new File(wavPath); if (w.exists()) w.delete(); }
        }
        if (head.stitchMembers != null) {
            for (Item m : head.stitchMembers) deleteMemberWav(m);
            head.stitchMembers = null;
        }
        head.overrideFilePath = null;
    }

    private void handleTransientStitched(Item head, long now, String why) {
        head.attempts++;
        DebugLogger.log("Outbox transient failure (stitched) attempt=" + head.attempts
                + " chat=" + head.chatId + " : " + why);
        DebugLogger.logError("Transient failure attempt=" + head.attempts + " kind=STITCHED_AUDIO"
                + " chat=" + head.chatId + " : " + why);
        if (head.attempts >= MAX_ATTEMPTS) {
            DebugLogger.log("Outbox dropping stitched item after " + head.attempts + " attempts: " + why);
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                    "Telegram send failed after " + head.attempts + " attempts"));
            cleanupStitched(head);
            synchronized (lock) { persistBulkLocked(); }
            return;
        }
        head.readyAt = now + TRANSIENT_BACKOFF_MS;
        requeue(head);
    }

    // NOTE: 429 handling needs retry_after from the response; implemented inline below.

    private void handleFatalGlobal(int code, String desc) {
        DebugLogger.logError("Permanent global " + code + " " + desc);
        EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                "Telegram auth error " + code + " — bot stopped"));
        running = false;
        if (listener != null) listener.onFatalGlobalError(desc);
    }

    private void handleTransient(Item item, long now, String why) {
        item.attempts++;
        DebugLogger.log("Outbox transient failure attempt=" + item.attempts
                + " kind=" + item.kind + " chat=" + item.chatId + " : " + why);
        DebugLogger.logError("Transient failure attempt=" + item.attempts + " kind=" + item.kind
                + " chat=" + item.chatId + " : " + why);
        if (item.attempts >= MAX_ATTEMPTS) {
            DebugLogger.log("Outbox dropping item after " + item.attempts + " attempts: " + why);
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                    "Telegram send failed after " + item.attempts + " attempts"));
            if (item.kind == Kind.AUDIO) deleteMemberWav(item);
            persistIfBulk(item);
            return;
        }
        item.readyAt = now + TRANSIENT_BACKOFF_MS;
        requeue(item);
    }

    private void requeue(Item item) {
        synchronized (lock) {
            (item.lane == Lane.INTERACTIVE ? interactive : bulk).addFirst(item);
            lock.notifyAll();
        }
    }

    private void purgeChat(long chatId) {
        synchronized (lock) {
            interactive.removeIf(i -> i.chatId == chatId);
            bulk.removeIf(i -> i.chatId == chatId);
            bulkEnqueueWindow.remove(chatId);
            DebugLogger.log("Outbox purged all pending items for dead chat " + chatId);
            persistBulkLocked();
        }
        EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                "Bot removed/blocked in chat " + chatId + " — pending messages dropped"));
        if (!kickNoticeSent) {
            kickNoticeSent = true;
            Long group = SettingsManager.getGroupChatIdLong(appContext);
            for (Long t : SettingsManager.getAllowedUserIds(appContext)) {
                if (t.longValue() != chatId && !state(t.longValue()).dead) {
                    enqueueText(t.longValue(), "⚠️ Bot was removed or blocked in chat " + chatId, null);
                }
            }
            if (group != null && group.longValue() != chatId && !state(group.longValue()).dead) {
                enqueueText(group.longValue(), "⚠️ Bot was removed or blocked in chat " + chatId, null);
            }
        }
    }

    private int depth(Lane lane) {
        synchronized (lock) {
            return lane == Lane.INTERACTIVE ? interactive.size() : bulk.size();
        }
    }

    // ── Captions ──

    private static String emojiFor(int level, boolean isPoni) {
        if (isPoni) return "⚪️";
        switch (level) {
            case 1: return "🔵";
            case 2: return "🟢";
            case 3: return "🟡";
            case 4: return "🟠";
            case 5: return "🔴";
            default: return "⚪️";
        }
    }

    private static String circled(int n) {
        if (n >= 1 && n <= 20) return String.valueOf((char) (0x2460 + n - 1));
        if (n >= 21 && n <= 35) return String.valueOf((char) (0x3251 + n - 21));
        if (n >= 36 && n <= 50) return String.valueOf((char) (0x32B1 + n - 36));
        return "(" + n + ")";
    }

    private String buildStitchedCaption(List<Item> group) {
        Item head = group.get(0);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            String prefix = (i == 0) ? (emojiFor(head.level, head.isPoni) + " ") : "";
            lines.add(prefix + circled(i + 1) + " " + group.get(i).captionBody);
        }
        StringBuilder sb = new StringBuilder();
        int included = 0;
        for (String line : lines) {
            int add = (sb.length() > 0 ? 1 : 0) + line.length();
            if (sb.length() + add > CAPTION_MAX) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
            included++;
        }
        if (included < lines.size()) {
            String suffix = "\n… +" + (lines.size() - included) + " more events";
            if (sb.length() + suffix.length() > CAPTION_MAX) {
                sb.setLength(CAPTION_MAX - suffix.length());
            }
            sb.append(suffix);
        }
        return sb.toString();
    }
}