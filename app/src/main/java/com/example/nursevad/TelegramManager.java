package com.example.nursevad;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendAudio;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import okhttp3.OkHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class TelegramManager implements OutboxQueue.SendCallback {
    private static TelegramManager instance;
    private TelegramBot bot;
    private boolean isRunning = false;
    private Context appContext;

    private final OutboxQueue outbox = new OutboxQueue();
    private Thread pollingThread;
    private volatile boolean pollingStopped = true;
    private volatile int updateOffset = 0;

    public static synchronized TelegramManager getInstance() {
        if (instance == null) instance = new TelegramManager();
        return instance;
    }

    public void start(Context context) {
        if (isRunning) return;
        appContext = context.getApplicationContext();

        String token = SettingsManager.getBotToken(appContext);
        Set<Long> initialIds = SettingsManager.getAllowedUserIds(appContext);
        boolean hasGroup = SettingsManager.getGroupChatIdLong(appContext) != null;

        if (token == null || token.isEmpty() || (initialIds.isEmpty() && !hasGroup)) {
            Log.d("TelegramManager", "Bot not started: Missing token or user IDs/group.");
            return;
        }

        try {
            TelegramErrorLogger.init(appContext);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(TelegramErrorLogger.interceptor())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            client.dispatcher().setMaxRequests(64);
            client.dispatcher().setMaxRequestsPerHost(32);

            bot = new TelegramBot.Builder(token).okHttpClient(client).build();
            isRunning = true;

            outbox.init(appContext, this);
            outbox.startWorker();

            startPolling();
        } catch (Throwable t) {
            Log.e("TelegramManager", "Fatal error starting Telegram Bot", t);
            isRunning = false;
        }
    }

    public void stop() {
        pollingStopped = true;
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread = null;
        }
        outbox.stopWorker();
        if (bot != null) {
            bot.shutdown();
            bot = null;
        }
        isRunning = false;
    }

    public boolean isBotRunning() { return isRunning; }

    // ─── long polling ───

    private void startPolling() {
        pollingStopped = false;
        pollingThread = new Thread(() -> {
            DebugLogger.log("Long polling thread started (timeout=30s)");
            while (!pollingStopped) {
                try {
                    GetUpdates request = new GetUpdates().offset(updateOffset).timeout(30);
                    GetUpdatesResponse response = bot.execute(request);
                    if (response == null || !response.isOk()) {
                        Thread.sleep(3000);
                        continue;
                    }
                    List<Update> updates = response.updates();
                    if (updates != null && !updates.isEmpty()) {
                        for (Update update : updates) {
                            updateOffset = update.updateId() + 1;
                            try {
                                if (update.message() != null) handleMessage(update.message());
                                else if (update.callbackQuery() != null) handleCallback(update.callbackQuery());
                            } catch (Exception ex) {
                                Log.e("TelegramManager", "Error processing update", ex);
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    Log.e("TelegramManager", "Long polling error", e);
                    if (!pollingStopped) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
            DebugLogger.log("Long polling thread stopped");
        }, "TelegramLongPoll");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    // ─── guards & helpers ───

    private boolean isIncomingAllowed(long chatId, long fromId) {
        Long groupChatId = SettingsManager.getGroupChatIdLong(appContext);
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        if (groupChatId != null) {
            if (chatId != groupChatId.longValue()) return false;
            return allowedIds.isEmpty() || allowedIds.contains(fromId);
        }
        return allowedIds.contains(fromId);
    }

    private String getUserName(com.pengrad.telegrambot.model.User from) {
        if (from != null && from.firstName() != null) return from.firstName();
        if (from != null && from.username() != null) return from.username();
        return "Someone";
    }

    // ─── OutboxQueue.SendCallback ───

    @Override
    public int send(OutboxQueue.Item item, int[] retryAfterSec) {
        if (bot == null) return -1;
        try {
            BaseResponse r;
            switch (item.type) {
                case "TEXT":
                    r = bot.execute(new SendMessage(item.chatId, item.text));
                    break;
                case "TEXT_RAW_MENU": {
                    SendMessage m = new SendMessage(item.chatId, item.text)
                            .replyMarkup(buildMainMenuMarkup(VadService.isVadListening));
                    if (item.messageId > 0) m.replyToMessageId(item.messageId);
                    r = bot.execute(m);
                    break;
                }
                case "AUDIO": {
                    File f = new File(item.filePath);
                    if (!f.exists()) return -2;
                    r = bot.execute(new SendAudio(item.chatId, f)
                            .caption(item.caption).title(item.title).performer(item.performer));
                    break;
                }
                case "EDIT_TEXT":
                    r = bot.execute(new EditMessageText(item.chatId, item.messageId, item.text));
                    break;
                case "EDIT_MAIN":
                    r = bot.execute(new EditMessageText(item.chatId, item.messageId, mainMenuText())
                            .replyMarkup(buildMainMenuMarkup(VadService.isVadListening)));
                    break;
                case "EDIT_SETTINGS":
                    r = bot.execute(new EditMessageText(item.chatId, item.messageId, "⚙️ Nurse VAD Settings")
                            .replyMarkup(buildSettingsMarkup()));
                    break;
                default:
                    return -2;
            }
            if (r.isOk()) return 0;
            if (r.errorCode() == 429) {
                Integer ra = (r.parameters() != null) ? r.parameters().retryAfter() : null;
                retryAfterSec[0] = (ra != null) ? ra : 5;
                return 429;
            }
            if (r.errorCode() >= 500) return -1;   // server-side → transient
            return -2;                              // 400/403/404 → permanent (see log_errors.txt)
        } catch (Exception e) {
            // Sync execute() wraps transport/IO failures in unchecked exceptions,
            // so any exception here means a network-level problem → transient, retry.
            return -1;
        }
    }

    // ─── enqueue wrappers (all outgoing traffic) ───

    private void sendToChat(long chatId, String text) {
        outbox.enqueueText(chatId, text);
    }

    private void broadcastMessage(String text) {
        for (Long chatId : getBroadcastTargets()) outbox.enqueueText(chatId, text);
    }

    private void notifyOtherUsers(long excludeUserId, String message) {
        Long groupChatId = SettingsManager.getGroupChatIdLong(appContext);
        if (groupChatId != null) {
            outbox.enqueueText(groupChatId, message);
            return;
        }
        for (Long chatId : SettingsManager.getAllowedUserIds(appContext)) {
            if (chatId != excludeUserId) outbox.enqueueText(chatId, message);
        }
    }

    private List<Long> getBroadcastTargets() {
        List<Long> targets = new java.util.ArrayList<>();
        Long groupChatId = SettingsManager.getGroupChatIdLong(appContext);
        if (groupChatId != null) targets.add(groupChatId);
        else targets.addAll(SettingsManager.getAllowedUserIds(appContext));
        return targets;
    }

    private void editMessage(long chatId, int messageId, String text) {
        outbox.enqueue(OutboxQueue.Lane.INTERACTIVE, "EDIT_TEXT", chatId, messageId, text, null, null, null, null);
    }

    private void editMainMenu(long chatId, int messageId) {
        outbox.enqueue(OutboxQueue.Lane.INTERACTIVE, "EDIT_MAIN", chatId, messageId, null, null, null, null, null);
    }

    private void editSettingsMenu(long chatId, int messageId) {
        outbox.enqueue(OutboxQueue.Lane.INTERACTIVE, "EDIT_SETTINGS", chatId, messageId, null, null, null, null, null);
    }

    public void sendTextMessage(String text) {
        if (!isRunning) return;
        for (Long chatId : getBroadcastTargets()) outbox.enqueueText(chatId, text);
    }

    public void sendAudioEvent(String artifactUri, int level, String responseFileName, boolean isPoni) {
        if (!isRunning) return;
        List<Long> targets = getBroadcastTargets();
        if (targets.isEmpty()) return;
        File file = new File(artifactUri.replace("file://", ""));
        if (!file.exists()) return;

        String emoji;
        if (isPoni) emoji = "⚪️";
        else switch (level) {
            case 1: emoji = "🔵"; break;
            case 2: emoji = "🟢"; break;
            case 3: emoji = "🟡"; break;
            case 4: emoji = "🟠"; break;
            case 5: emoji = "🔴"; break;
            default: emoji = "⚪️"; break;
        }
        String caption = emoji + " " + (responseFileName != null ? responseFileName : "No file found");
        boolean useEmb = SettingsManager.getUseEmbeddings(appContext);
        String performer = (useEmb && !isPoni) ? "Client" : "Someone";

        for (Long chatId : targets) {
            outbox.enqueueAudio(chatId, file.getAbsolutePath(), caption, "Speech Detected", performer);
        }
    }

    // ─── incoming ───

    private void handleMessage(Message message) {
        if (message.from() == null || message.chat() == null) return;
        long chatId = message.chat().id();
        long userId = message.from().id();
        if (!isIncomingAllowed(chatId, userId)) return;

        if (message.voice() != null || message.audio() != null) {
            boolean isAudio = message.voice() == null;
            String fileId = isAudio ? message.audio().fileId() : message.voice().fileId();
            String label = isAudio ? "Audio" : "Voice Message";

            String mime = isAudio ? message.audio().mimeType() : "audio/ogg";
            String ext = ".mp3";
            if (mime != null) {
                if (mime.contains("wav")) ext = ".wav";
                else if (mime.contains("ogg") || mime.contains("opus")) ext = ".ogg";
                else if (mime.contains("mp4") || mime.contains("aac")) ext = ".m4a";
                else if (mime.contains("mpeg") || mime.contains("mp3")) ext = ".mp3";
            }

            String senderName = "Telegram Bot";
            if (message.from().firstName() != null) {
                senderName = message.from().firstName();
                if (message.from().lastName() != null) senderName += " " + message.from().lastName();
            } else if (message.from().username() != null) {
                senderName = message.from().username();
            }

            // Telegram confirmation messages disabled (kept in code for future re-enable):
            // broadcastMessage("🎤 Voice Message from " + senderName);
            // broadcastMessage("🎤 Audio from " + senderName);
            // (original combined form, commented out:)
            // broadcastMessage("🎤 " + label + " from " + senderName);

            downloadAndQueueVoice(fileId, senderName, isAudio, ext);
            return;
        }

        String text = message.text();
        if (text == null) return;
        String cmd = text.trim();
        String userName = getUserName(message.from());

        if (cmd.equals("/control") || cmd.startsWith("/control@")) {
            sendMainMenu(chatId, message.messageId());
        } else if (cmd.equals("/start") || cmd.startsWith("/start@")) {
            if (!tryStartListening()) sendToChat(chatId, MIC_WARNING);
            else sendToChat(chatId, "🟩🟩🟩 Start 🟩");
            notifyOtherUsers(userId, userName + " hit Start");
        } else if (cmd.equals("/stop") || cmd.startsWith("/stop@")) {
            stopListeningNow();
            sendToChat(chatId, "🟥🟥 Stop 🟥");
            notifyOtherUsers(userId, userName + " hit Stop");
        }
    }

    private static final String MIC_WARNING =
            "⚠️ Microphone permission is not granted. Open the app once and allow it, then try again.";

    private boolean tryStartListening() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        VadService.startService(appContext);
        VadService.isVadListening = true;
        return true;
    }

    private void stopListeningNow() {
        VadService.stopService(appContext);
        VadService.isVadListening = false;
    }

    private void handleCallback(CallbackQuery callback) {
        if (callback.from() == null || callback.message() == null || callback.message().chat() == null) return;
        long chatId = callback.message().chat().id();
        int messageId = callback.message().messageId();
        long userId = callback.from().id();
        if (!isIncomingAllowed(chatId, userId)) return;

        String data = callback.data();
        String userName = getUserName(callback.from());

        if (data.equals("start_vad")) {
            boolean ok = tryStartListening();
            if (!ok) sendToChat(chatId, MIC_WARNING);
            notifyOtherUsers(userId, userName + " hit Start");
            editMainMenu(chatId, messageId);
        } else if (data.equals("stop_vad")) {
            stopListeningNow();
            notifyOtherUsers(userId, userName + " hit Stop");
            editMainMenu(chatId, messageId);
        } else if (data.equals("toggle_silent")) {
            boolean current = SettingsManager.isSilentMode(appContext);
            SettingsManager.saveSilentMode(appContext, !current);
            editMainMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Silent Mode " + (!current ? "ON" : "OFF"));
        } else if (data.equals("settings")) {
            editSettingsMenu(chatId, messageId);
        } else if (data.equals("back_main")) {
            editMainMenu(chatId, messageId);
        } else if (data.startsWith("toggle_wait")) {
            boolean current = SettingsManager.getWaitForEnd(appContext);
            SettingsManager.saveWaitForEnd(appContext, !current);
            editSettingsMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Wait For End " + (!current ? "ON" : "OFF"));
        } else if (data.equals("toggle_use_embeddings")) {
            boolean current = SettingsManager.getUseEmbeddings(appContext);
            SettingsManager.saveUseEmbeddings(appContext, !current);
            editSettingsMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Use Embeddings " + (!current ? "ON" : "OFF"));
        } else if (data.equals("toggle_repeat_reminder")) {
            boolean current = SettingsManager.getRepeatReminder(appContext);
            SettingsManager.saveRepeatReminder(appContext, !current);
            editSettingsMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Repeat Reminder " + (!current ? "ON" : "OFF"));
        } else if (data.startsWith("delay_")) {
            int current = SettingsManager.getDelay(appContext);
            int newValue = current;
            if (data.equals("delay_inc") && current < 10) newValue = current + 1;
            if (data.equals("delay_dec") && current > 0) newValue = current - 1;
            if (newValue != current) {
                SettingsManager.saveDelay(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Delay from " + current + "s to " + newValue + "s");
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("dur_")) {
            int current = SettingsManager.getDurationThreshold(appContext);
            int newValue = current;
            if (data.equals("dur_inc") && current < 10) newValue = current + 1;
            if (data.equals("dur_dec") && current > 1) newValue = current - 1;
            if (newValue != current) {
                SettingsManager.saveDurationThreshold(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Ignore Short from " + current + "s to " + newValue + "s");
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("thresh_")) {
            String[] parts = data.split("_");
            if (parts.length >= 3) {
                int level = Integer.parseInt(parts[1]);
                boolean inc = parts[2].equals("inc");
                int[] thresholds = SettingsManager.getThresholds(appContext);
                int oldValue = thresholds[level - 1];
                int newValue = oldValue;
                if (inc && oldValue < 100) newValue = oldValue + 5;
                if (!inc && oldValue > 0) newValue = oldValue - 5;
                if (newValue != oldValue) {
                    thresholds[level - 1] = newValue;
                    SettingsManager.saveThresholds(appContext, thresholds);
                    notifyOtherUsers(userId, userName + " changed Level " + level + " from " + oldValue + "% to " + newValue + "%");
                }
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("poi_thresh_")) {
            int current = SettingsManager.getPoiThreshold(appContext);
            int newValue = current;
            if (data.equals("poi_thresh_inc") && current < 95) newValue = current + 5;
            if (data.equals("poi_thresh_dec") && current > 55) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.savePoiThreshold(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed POI Threshold from " +
                        String.format(java.util.Locale.US, "%.2f", current / 100f) + " to " +
                        String.format(java.util.Locale.US, "%.2f", newValue / 100f));
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("poni_thresh_")) {
            int current = SettingsManager.getPoniThreshold(appContext);
            int newValue = current;
            if (data.equals("poni_thresh_inc") && current < 95) newValue = current + 5;
            if (data.equals("poni_thresh_dec") && current > 55) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.savePoniThreshold(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed PONI Threshold from " +
                        String.format(java.util.Locale.US, "%.2f", current / 100f) + " to " +
                        String.format(java.util.Locale.US, "%.2f", newValue / 100f));
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rem_min_")) {
            int current = SettingsManager.getReminderSpeechMin(appContext);
            int newValue = current;
            if (data.equals("rem_min_inc") && current < 180) newValue = current + 5;
            if (data.equals("rem_min_dec") && current > 30) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveReminderSpeechMin(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Reminder After (Min) from " + current + "s to " + newValue + "s");
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rem_max_")) {
            int current = SettingsManager.getReminderSpeechMax(appContext);
            int newValue = current;
            if (data.equals("rem_max_inc") && current < 180) newValue = current + 5;
            if (data.equals("rem_max_dec") && current > 30) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveReminderSpeechMax(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Reminder After (Max) from " + current + "s to " + newValue + "s");
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rep_min_")) {
            int current = SettingsManager.getRepeatReminderMin(appContext);
            int newValue = current;
            if (data.equals("rep_min_inc") && current < 30) newValue = current + 5;
            if (data.equals("rep_min_dec") && current > 5) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveRepeatReminderMin(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Repeat Reminder After (Min) from " + current + "s to " + newValue + "s");
            }
            editSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rep_max_")) {
            int current = SettingsManager.getRepeatReminderMax(appContext);
            int newValue = current;
            if (data.equals("rep_max_inc") && current < 30) newValue = current + 5;
            if (data.equals("rep_max_dec") && current > 5) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveRepeatReminderMax(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Repeat Reminder After (Max) from " + current + "s to " + newValue + "s");
            }
            editSettingsMenu(chatId, messageId);
        }

        bot.execute(new com.pengrad.telegrambot.request.AnswerCallbackQuery(callback.id()));
    }

    // ─── menus ───

    private String mainMenuText() {
        return "Nurse VAD Control Panel";
    }

    private InlineKeyboardMarkup buildMainMenuMarkup(boolean listening) {
        boolean silent = SettingsManager.isSilentMode(appContext);
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton(listening ? "🟥 Stop (Listening)" : "🟩 Start (Idle)")
                                .callbackData(listening ? "stop_vad" : "start_vad")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("Toggle Silent Mode (" + (silent ? "ON" : "OFF") + ")").callbackData("toggle_silent")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("⚙️ Settings").callbackData("settings")
                }
        );
    }

    private void sendMainMenu(long chatId, int replyToId) {
        SendMessage msg = new SendMessage(chatId, mainMenuText())
                .replyMarkup(buildMainMenuMarkup(VadService.isVadListening));
        if (replyToId > 0) msg.replyToMessageId(replyToId);
        // Menu send is interactive but must not itself be queued before bot is ready:
        outbox.enqueue(OutboxQueue.Lane.INTERACTIVE, "TEXT_RAW_MENU", chatId, replyToId,
                mainMenuText(), null, null, null, null);
    }

    private InlineKeyboardMarkup buildSettingsMarkup() {
        boolean wait = SettingsManager.getWaitForEnd(appContext);
        int delay = SettingsManager.getDelay(appContext);
        int dur = SettingsManager.getDurationThreshold(appContext);
        int[] thresh = SettingsManager.getThresholds(appContext);
        float poiTh = SettingsManager.getPoiThreshold(appContext) / 100f;
        float poniTh = SettingsManager.getPoniThreshold(appContext) / 100f;
        boolean repeatRem = SettingsManager.getRepeatReminder(appContext);
        boolean useEmb = SettingsManager.getUseEmbeddings(appContext);
        int repMin = SettingsManager.getRepeatReminderMin(appContext);
        int repMax = SettingsManager.getRepeatReminderMax(appContext);
        int remMin = SettingsManager.getReminderSpeechMin(appContext);
        int remMax = SettingsManager.getReminderSpeechMax(appContext);

        return new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{ new InlineKeyboardButton("Toggle Wait (" + (wait ? "ON" : "OFF") + ")").callbackData("toggle_wait") },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-1s").callbackData("delay_dec"),
                    new InlineKeyboardButton("Delay: " + delay + "s").callbackData("noop"),
                    new InlineKeyboardButton("+1s").callbackData("delay_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-1s").callbackData("dur_dec"),
                    new InlineKeyboardButton("Ignore: " + dur + "s").callbackData("noop"),
                    new InlineKeyboardButton("+1s").callbackData("dur_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5").callbackData("thresh_1_dec"),
                    new InlineKeyboardButton("Level 1: " + thresh[0]).callbackData("noop"),
                    new InlineKeyboardButton("+5").callbackData("thresh_1_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5").callbackData("thresh_2_dec"),
                    new InlineKeyboardButton("Level 2: " + thresh[1]).callbackData("noop"),
                    new InlineKeyboardButton("+5").callbackData("thresh_2_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5").callbackData("thresh_3_dec"),
                    new InlineKeyboardButton("Level 3: " + thresh[2]).callbackData("noop"),
                    new InlineKeyboardButton("+5").callbackData("thresh_3_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5").callbackData("thresh_4_dec"),
                    new InlineKeyboardButton("Level 4: " + thresh[3]).callbackData("noop"),
                    new InlineKeyboardButton("+5").callbackData("thresh_4_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5").callbackData("thresh_5_dec"),
                    new InlineKeyboardButton("Level 5: " + thresh[4]).callbackData("noop"),
                    new InlineKeyboardButton("+5").callbackData("thresh_5_inc")
                },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("Use Embeddings (" + (useEmb ? "ON" : "OFF") + ")").callbackData("toggle_use_embeddings") },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-0.05").callbackData("poi_thresh_dec"),
                    new InlineKeyboardButton(String.format(java.util.Locale.US, "POI Threshold: %.2f", poiTh)).callbackData("noop"),
                    new InlineKeyboardButton("+0.05").callbackData("poi_thresh_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-0.05").callbackData("poni_thresh_dec"),
                    new InlineKeyboardButton(String.format(java.util.Locale.US, "PONI Threshold: %.2f", poniTh)).callbackData("noop"),
                    new InlineKeyboardButton("+0.05").callbackData("poni_thresh_inc")
                },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("Repeat Reminder (" + (repeatRem ? "ON" : "OFF") + ")").callbackData("toggle_repeat_reminder") },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5s").callbackData("rem_min_dec"),
                    new InlineKeyboardButton("Rem After Min: " + remMin + "s").callbackData("noop"),
                    new InlineKeyboardButton("+5s").callbackData("rem_min_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5s").callbackData("rem_max_dec"),
                    new InlineKeyboardButton("Rem After Max: " + remMax + "s").callbackData("noop"),
                    new InlineKeyboardButton("+5s").callbackData("rem_max_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5s").callbackData("rep_min_dec"),
                    new InlineKeyboardButton("Rep After Min: " + repMin + "s").callbackData("noop"),
                    new InlineKeyboardButton("+5s").callbackData("rep_min_inc")
                },
                new InlineKeyboardButton[]{
                    new InlineKeyboardButton("-5s").callbackData("rep_max_dec"),
                    new InlineKeyboardButton("Rep After Max: " + repMax + "s").callbackData("noop"),
                    new InlineKeyboardButton("+5s").callbackData("rep_max_inc")
                },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("🔙 Back").callbackData("back_main") }
        );
    }

    // ─── voice/audio download with retry ───

    private void downloadAndQueueVoice(String fileId, String senderName, boolean isAudio, String ext) {
        downloadWithRetry(fileId, senderName, 0, isAudio, ext);
    }

    private void downloadWithRetry(String fileId, String senderName, int attempt, boolean isAudio, String ext) {
        final int MAX_RETRIES = 3;
        bot.execute(new GetFile(fileId), new Callback<GetFile, GetFileResponse>() {
            @Override
            public void onResponse(GetFile request, GetFileResponse response) {
                if (response.isOk()) {
                    String fileUrl = bot.getFullFilePath(response.file());
                    new Thread(() -> {
                        try {
                            URL url = new URL(fileUrl);
                            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                            connection.setConnectTimeout(15000);
                            connection.setReadTimeout(15000);
                            connection.connect();

                            File tempFile = new File(VadService.getAudioDir(appContext),
                                    "tg_media_" + System.currentTimeMillis() + ext);
                            FileOutputStream output = new FileOutputStream(tempFile);
                            InputStream input = connection.getInputStream();
                            byte[] data = new byte[4096];
                            int count;
                            while ((count = input.read(data)) != -1) output.write(data, 0, count);
                            output.close();
                            input.close();
                            connection.disconnect();

                            Intent i = new Intent(appContext, VadService.class);
                            i.setAction("PLAY_TELEGRAM_VOICE");
                            i.putExtra("PATH", tempFile.getAbsolutePath());
                            i.putExtra("SENDER", senderName);
                            i.putExtra("IS_AUDIO", isAudio);
                            appContext.startService(i);
                        } catch (Exception e) {
                            Log.e("TelegramManager", "Download attempt " + (attempt + 1) + " failed", e);
                            handleDownloadRetry(fileId, senderName, attempt, MAX_RETRIES, isAudio);
                        }
                    }).start();
                } else {
                    handleDownloadRetry(fileId, senderName, attempt, MAX_RETRIES, isAudio);
                }
            }

            @Override
            public void onFailure(GetFile request, IOException e) {
                handleDownloadRetry(fileId, senderName, attempt, MAX_RETRIES, isAudio);
            }
        });
    }

    private void handleDownloadRetry(String fileId, String senderName, int attempt, int maxRetries, boolean isAudio) {
        String label = isAudio ? "Audio" : "Voice message";
        if (attempt < maxRetries - 1) {
            long delay = (long) Math.pow(2, attempt) * 1000;
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    downloadWithRetry(fileId, senderName, attempt + 1, isAudio, null), delay);
        } else {
            DebugLogger.log("All " + label.toLowerCase() + " download attempts failed for sender: " + senderName);
            EventRepository.getInstance().addEvent(
                    new LogEvent(LogEvent.Type.WARNING, label + " download failed from " + senderName));
            broadcastMessage("⚠️ Failed to download " + label.toLowerCase() + " from " + senderName);
        }
    }
}