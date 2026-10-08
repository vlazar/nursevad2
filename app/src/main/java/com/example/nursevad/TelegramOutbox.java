package com.example.nursevad;

import android.content.Context;
import android.os.SystemClock;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendAudio;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-lane outbox with a single paced worker.
 *  - INTERACTIVE (memory-only): texts, menu edits, warnings, notices.
 *  - BULK: speech-event audio (persistence arrives in Step 2, stitching in Step 3).
 * Bypassed entirely (never queued): answerCallbackQuery, getUpdates long-poll, inbound downloads.
 */
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
        public String filePath;   // artifact to upload (OGG/WAV)
        public String caption;
        public String title;
        public String performer;
        // Stitching metadata (used from Step 3; persisted from Step 2)
        public int level;
        public boolean isPoni;
        public String captionBody;
        public String wavPath;
        public long speechStartMs;
        public long speechEndMs;
        // Runtime
        public int attempts;
        public final long enqueuedAt = SystemClock.elapsedRealtime();
        public long readyAt;
    }

    public interface OutboxListener {
        void onFatalGlobalError(String description); // 401/404 → stop the bot
    }

    // Hidden safe-pace constants
    private static final long SAFE_INTERVAL_GROUP_MS = 3334; // ≈18 msg/min
    private static final long SAFE_INTERVAL_DM_MS    = 1112; // ≈54 msg/min
    private static final long RETRY_PADDING_MS = 1000;
    private static final int  MAX_ATTEMPTS = 3;
    private static final long TRANSIENT_BACKOFF_MS = 1000;

    private static TelegramOutbox instance;
    public static synchronized TelegramOutbox getInstance() {
        if (instance == null) instance = new TelegramOutbox();
        return instance;
    }

    private TelegramBot bot;
    private Context appContext;
    private OutboxListener listener;

    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean running = false;

    private final ArrayDeque<Item> interactive = new ArrayDeque<>();
    private final ArrayDeque<Item> bulk = new ArrayDeque<>();

    private static class ChatState {
        long nextSendAtMs = 0;
        long pausedUntilMs = 0;
        boolean dead = false;
    }
    private final Map<Long, ChatState> chats = new HashMap<>();
    private boolean kickNoticeSent = false;

    public void attach(TelegramBot bot, Context context, OutboxListener listener) {
        this.bot = bot;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void startWorker() {
        synchronized (lock) {
            if (running && worker != null && worker.isAlive()) return;
            running = true;
            worker = new Thread(this::workerLoop, "TelegramOutbox");
            worker.setDaemon(true);
            worker.start();
            DebugLogger.log("Outbox worker started");
        }
    }

    public void shutdown() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
        // Step 2 adds: persist remaining bulk items here.
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
            // Coalesce: a newer edit for the same message supersedes a pending one
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
        DebugLogger.log("Outbox enqueue: lane=" + i.lane + " kind=" + i.kind + " chat=" + i.chatId
                + " depths i=" + interactive.size() + " b=" + bulk.size());
        lock.notifyAll();
    }

    private ChatState state(long chatId) {
        ChatState cs = chats.get(chatId);
        if (cs == null) { cs = new ChatState(); chats.put(chatId, cs); }
        return cs;
    }

    private long intervalFor(long chatId) {
        Long group = SettingsManager.getGroupChatIdLong(appContext);
        return (group != null && group.longValue() == chatId) ? SAFE_INTERVAL_GROUP_MS : SAFE_INTERVAL_DM_MS;
    }

    // ── Worker ──
    private void workerLoop() {
        while (running) {
            Item item;
            synchronized (lock) {
                long now = SystemClock.elapsedRealtime();
                item = pickReady(now);
                if (item == null) {
                    long wait = computeWait(now);
                    try { lock.wait(wait); } catch (InterruptedException e) { break; }
                    continue;
                }
            }
            try {
                dispatch(item);
            } catch (Throwable t) {
                DebugLogger.log("Outbox dispatch crash: " + t);
            }
        }
        DebugLogger.log("Outbox worker stopped");
    }

    private Item pickReady(long now) {
        Item i = pickFrom(interactive, now);   // interactive jumps ahead of bulk
        return (i != null) ? i : pickFrom(bulk, now);
    }

    private Item pickFrom(ArrayDeque<Item> deque, long now) {
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

    private long computeWait(long now) {
        long min = Math.min(earliest(interactive), earliest(bulk));
        if (min == Long.MAX_VALUE) return 30000; // idle heartbeat
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

    // ── Dispatch & error policy ──
    private void dispatch(Item item) {
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

        if (failure != null) {
            handleTransient(item, now, "exception: " + failure);
            return;
        }
        if (resp.isOk()) {
            cs.nextSendAtMs = now + intervalFor(item.chatId);
            DebugLogger.log("Outbox sent ok: kind=" + item.kind + " chat=" + item.chatId
                    + " depths i=" + depth(Lane.INTERACTIVE) + " b=" + depth(Lane.BULK));
            // Step 2: persistence commit here. Step 3: WAV/stitched-file cleanup here.
            return;
        }

        int code = resp.errorCode();
        String desc = resp.description();

        if (code == 429) {
            int retryAfter = (resp.parameters() != null && resp.parameters().retryAfter() != null)
                    ? resp.parameters().retryAfter() : 0;
            cs.pausedUntilMs = now + retryAfter * 1000L + RETRY_PADDING_MS;
            String line = "Outbox 429: chat=" + item.chatId + " retry_after=" + retryAfter
                    + "s depths i=" + depth(Lane.INTERACTIVE) + " b=" + depth(Lane.BULK);
            DebugLogger.log(line);
            DebugLogger.logError(line + " | " + desc);
            requeue(item);
            return;
        }
        if (code == 401 || code == 404) {
            DebugLogger.logError("Permanent global " + code + " " + desc + " chat=" + item.chatId);
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                    "Telegram auth error " + code + " — bot stopped"));
            running = false;
            if (listener != null) listener.onFatalGlobalError(desc);
            return;
        }
        if (code == 403 || code == 400) {
            DebugLogger.logError("Permanent chat " + code + " " + desc + " chat=" + item.chatId);
            cs.dead = true;
            purgeChat(item.chatId);
            return;
        }
        handleTransient(item, now, "http " + code + " " + desc);
    }

    private void handleTransient(Item item, long now, String why) {
        item.attempts++;
        DebugLogger.logError("Transient failure attempt=" + item.attempts + " kind=" + item.kind
                + " chat=" + item.chatId + " : " + why);
        if (item.attempts >= MAX_ATTEMPTS) {
            DebugLogger.log("Outbox dropping item after " + item.attempts + " attempts: " + why);
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                    "Telegram send failed after " + item.attempts + " attempts"));
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
        List<Long> aliveTargets = new ArrayList<>();
        synchronized (lock) {
            interactive.removeIf(i -> i.chatId == chatId);
            bulk.removeIf(i -> i.chatId == chatId);
            DebugLogger.log("Outbox purged all pending items for dead chat " + chatId);
            for (Long t : SettingsManager.getAllowedUserIds(appContext)) {
                if (t.longValue() != chatId && !state(t.longValue()).dead) aliveTargets.add(t);
            }
            Long group = SettingsManager.getGroupChatIdLong(appContext);
            if (group != null && group.longValue() != chatId && !state(group.longValue()).dead
                    && !aliveTargets.contains(group)) {
                aliveTargets.add(group);
            }
        }
        EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.WARNING,
                "Bot removed/blocked in chat " + chatId + " — pending messages dropped"));
        if (!kickNoticeSent && !aliveTargets.isEmpty()) {
            kickNoticeSent = true;
            for (Long t : aliveTargets) {
                enqueueText(t.longValue(), "⚠️ Bot was removed or blocked in chat " + chatId, null);
            }
        }
    }

    private int depth(Lane lane) {
        synchronized (lock) {
            return lane == Lane.INTERACTIVE ? interactive.size() : bulk.size();
        }
    }
}