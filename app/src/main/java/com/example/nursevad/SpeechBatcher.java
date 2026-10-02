package com.example.nursevad;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aggregates speech events into digest audio messages while there is a constant
 * stream (> 20 events/min). Only same-level, same-POI/PONI events are combined.
 * Combined audio covers < 15 s wall clock; gaps between events become silence
 * blocks; no trailing silence. Captions are numbered ① ② ③ ...
 */
public class SpeechBatcher {

    private static final int    RATE_WINDOW_MS     = 60000;
    private static final int    RATE_THRESHOLD     = 20;    // events per minute
    private static final long   MAX_BATCH_SPAN_MS  = 15000; // wall-clock cap (exclusive)
    private static final long   IDLE_FLUSH_MS      = 5000;  // quiet-period flush
    private static final int    BYTES_PER_MS       = 32;    // 16 kHz mono 16-bit

    private static SpeechBatcher instance;

    public static synchronized SpeechBatcher getInstance(Context context) {
        if (instance == null) instance = new SpeechBatcher(context.getApplicationContext());
        return instance;
    }

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<Long> rateWindow = new ArrayDeque<>();
    private Batch pending = null;
    private long lastEventWall = 0;

    private final Runnable idleFlushRunnable = () ->
            worker.execute(() -> {
                synchronized (SpeechBatcher.this) {
                    if (pending != null && System.currentTimeMillis() - lastEventWall >= IDLE_FLUSH_MS - 300) {
                        Batch b = pending;
                        pending = null;
                        flush(b);
                    }
                }
            });

    private SpeechBatcher(Context context) {
        this.appContext = context;
    }

    private static class BatchItem {
        final File wav;
        final LogEvent logEvent;
        final String caption;
        final long startMs;
        final long endMs;
        BatchItem(File wav, LogEvent logEvent, String caption, long startMs, long endMs) {
            this.wav = wav; this.logEvent = logEvent; this.caption = caption;
            this.startMs = startMs; this.endMs = endMs;
        }
    }

    private static class Batch {
        final int level;
        final boolean poni;
        final long startMs;
        long endMs;
        final List<BatchItem> items = new ArrayList<>();
        Batch(int level, boolean poni, long startMs) {
            this.level = level; this.poni = poni; this.startMs = startMs; this.endMs = startMs;
        }
    }

    public void onSpeechEvent(int level, boolean poni, String responseName, File wav,
                              LogEvent logEvent, long startWall, long endWall) {
        String caption = responseName != null ? responseName : "No file found";
        worker.execute(() -> process(level, poni, caption, wav, logEvent, startWall, endWall));
    }

    public void flushAll() {
        worker.execute(() -> {
            synchronized (SpeechBatcher.this) {
                handler.removeCallbacks(idleFlushRunnable);
                if (pending != null) {
                    Batch b = pending;
                    pending = null;
                    flush(b);
                }
            }
        });
    }

    private void process(int level, boolean poni, String caption, File wav,
                         LogEvent logEvent, long startWall, long endWall) {
        synchronized (this) {
            long now = System.currentTimeMillis();
            while (!rateWindow.isEmpty() && now - rateWindow.peekFirst() > RATE_WINDOW_MS) {
                rateWindow.pollFirst();
            }
            rateWindow.addLast(endWall);
            boolean active = rateWindow.size() > RATE_THRESHOLD;
            lastEventWall = endWall;

            BatchItem item = new BatchItem(wav, logEvent, caption, startWall, endWall);

            if (pending != null) {
                if (!active) {                       // stream calmed down
                    Batch b = pending;
                    pending = null;
                    flush(b);
                    sendSingle(item, level, poni);
                    return;
                }
                boolean sameKind = (pending.level == level && pending.poni == poni);
                boolean fits = (endWall - pending.startMs) < MAX_BATCH_SPAN_MS;
                if (sameKind && fits) {
                    pending.items.add(item);
                    pending.endMs = endWall;
                    DebugLogger.log("Batch append: level=" + level + " items=" + pending.items.size());
                    scheduleIdleFlush();
                } else {
                    Batch b = pending;
                    pending = null;
                    flush(b);
                    pending = newBatch(item, level, poni, startWall, endWall);
                    scheduleIdleFlush();
                }
            } else {
                if (active) {
                    pending = newBatch(item, level, poni, startWall, endWall);
                    DebugLogger.log("Batch opened: level=" + level + " rate=" + rateWindow.size() + "/min");
                    scheduleIdleFlush();
                } else {
                    sendSingle(item, level, poni);
                }
            }
        }
    }

    private Batch newBatch(BatchItem item, int level, boolean poni, long startWall, long endWall) {
        Batch b = new Batch(level, poni, startWall);
        b.items.add(item);
        b.endMs = endWall;
        return b;
    }

    private void scheduleIdleFlush() {
        handler.removeCallbacks(idleFlushRunnable);
        handler.postDelayed(idleFlushRunnable, IDLE_FLUSH_MS);
    }

    // ─── Single (non-batched) send: unchanged behavior ───
    private void sendSingle(BatchItem item, int level, boolean poni) {
        File ogg = new File(item.wav.getParent(),
                item.wav.getName().replaceAll("\\.wav$", "") + ".ogg");
        if (OpusTranscoder.transcodeWavToOpusOgg(item.wav, ogg)) {
            item.wav.delete();
            item.logEvent.recordedSpeechUri = Uri.fromFile(ogg).toString();
            EventRepository.getInstance().refresh();
            TelegramManager.getInstance().sendAudioEvent(ogg.getAbsolutePath(), level, item.caption, poni);
        } else {
            TelegramManager.getInstance().sendAudioEvent(item.wav.getAbsolutePath(), level, item.caption, poni);
        }
    }

    // ─── Digest send: join PCM with silence gaps, transcode once ───
    private void flush(Batch batch) {
        if (batch == null) return;
        if (batch.items.size() == 1) {
            sendSingle(batch.items.get(0), batch.level, batch.poni);
            return;
        }

        DebugLogger.log("Batch flush: level=" + batch.level + " items=" + batch.items.size());

        File dir = batch.items.get(0).wav.getParentFile();
        File combined = new File(dir, "speech_batch_" + batch.startMs + ".wav");
        try {
            FileOutputStream out = new FileOutputStream(combined);
            try {
                writeWavHeader(out, 16000, 1, 16);
                long prevEnd = -1;
                for (BatchItem it : batch.items) {
                    if (prevEnd >= 0) {
                        long gap = Math.max(0, it.startMs - prevEnd);
                        writeSilence(out, gap);
                    }
                    byte[] pcm = OpusTranscoder.readPcm(it.wav);
                    out.write(pcm);
                    prevEnd = it.endMs;
                    it.wav.delete(); // PCM is now inside the combined file
                }
            } finally {
                out.close();
            }
        } catch (Exception e) {
            DebugLogger.log("Batch combine failed, sending individually: " + e);
            for (BatchItem it : batch.items) sendSingle(it, batch.level, batch.poni);
            return;
        }

        String caption = buildCaption(batch);
        File ogg = new File(combined.getParent(), combined.getName().replaceAll("\\.wav$", "") + ".ogg");
        File artifact;
        if (OpusTranscoder.transcodeWavToOpusOgg(combined, ogg)) {
            combined.delete();
            artifact = ogg;
        } else {
            artifact = combined; // fallback: send the joined WAV itself
        }
        String uri = Uri.fromFile(artifact).toString();
        for (BatchItem it : batch.items) {
            it.logEvent.recordedSpeechUri = uri; // all events point at the digest audio
        }
        EventRepository.getInstance().refresh();
        TelegramManager.getInstance().sendBatchedAudioEvent(artifact, batch.level, batch.poni, caption);
    }

    private String buildCaption(Batch batch) {
        StringBuilder sb = new StringBuilder();
        sb.append(TelegramManager.emojiForLevel(batch.level, batch.poni)).append(' ');
        for (int i = 0; i < batch.items.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(circled(i + 1)).append(' ').append(batch.items.get(i).caption);
        }
        return sb.toString();
    }

    private static String circled(int n) {
        if (n >= 1 && n <= 20)  return new String(Character.toChars(0x2460 + n - 1));   // ①..⑳
        if (n <= 35)            return new String(Character.toChars(0x3251 + n - 21));  // ㉑..
        if (n <= 50)            return new String(Character.toChars(0x32B1 + n - 36));  // ..㋿
        return "(" + n + ")";
    }

    private static void writeSilence(FileOutputStream out, long gapMs) throws Exception {
        long bytes = gapMs * BYTES_PER_MS;
        byte[] chunk = new byte[8192];
        while (bytes > 0) {
            int n = (int) Math.min(chunk.length, bytes);
            out.write(chunk, 0, n);
            bytes -= n;
        }
    }

    private static void writeWavHeader(FileOutputStream out, int sampleRate, int channels, int bitsPerSample) throws Exception {
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16;
        header[20] = 1;
        header[22] = (byte) channels;
        header[24] = (byte) (sampleRate & 0xff); header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff); header[27] = (byte) ((sampleRate >> 24) & 0xff);
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        header[28] = (byte) (byteRate & 0xff); header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff); header[31] = (byte) ((byteRate >> 24) & 0xff);
        int blockAlign = channels * bitsPerSample / 8;
        header[32] = (byte) (blockAlign & 0xff); header[33] = (byte) ((blockAlign >> 8) & 0xff);
        header[34] = (byte) (bitsPerSample & 0xff); header[35] = (byte) ((bitsPerSample >> 8) & 0xff);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        out.write(header, 0, 44);
    }
}