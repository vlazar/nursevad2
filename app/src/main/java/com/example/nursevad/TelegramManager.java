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
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendAudio;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.response.SendResponse;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Set;

public class TelegramManager {
    private static TelegramManager instance;
    private TelegramBot bot;
    private boolean isRunning = false;
    private Context appContext;

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
            Log.d("TelegramManager", "Bot not started: Missing token or no allowed users/group.");
            return;
        }

        try {
            bot = new TelegramBot(token);
            isRunning = true;

            bot.setUpdatesListener(updates -> {
                for (Update update : updates) {
                    try {
                        if (update.message() != null) {
                            handleMessage(update.message());
                        } else if (update.callbackQuery() != null) {
                            handleCallback(update.callbackQuery());
                        }
                    } catch (Exception ex) {
                        Log.e("TelegramManager", "Error processing update", ex);
                    }
                }
                return UpdatesListener.CONFIRMED_UPDATES_ALL;
            }, e -> {
                Log.e("TelegramManager", "Telegram Bot Error: " + e.getMessage());
            });

        } catch (Throwable t) {
            Log.e("TelegramManager", "Fatal error starting Telegram Bot", t);
            isRunning = false;
        }
    }

    public void stop() {
        if (bot != null) {
            bot.shutdown();
            bot = null;
        }
        isRunning = false;
    }

    public boolean isBotRunning() { return isRunning; }

    // ─── Guards & helpers ───

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

    private List<Long> getBroadcastTargets() {
        List<Long> targets = new java.util.ArrayList<>();
        Long groupChatId = SettingsManager.getGroupChatIdLong(appContext);
        if (groupChatId != null) {
            targets.add(groupChatId);
        } else {
            targets.addAll(SettingsManager.getAllowedUserIds(appContext));
        }
        return targets;
    }

    private void sendToChat(long chatId, String text) {
        bot.execute(new SendMessage(chatId, text), new Callback<SendMessage, SendResponse>() {
            @Override
            public void onResponse(SendMessage request, SendResponse response) {
                if (!response.isOk()) {
                    Log.e("TelegramManager", "Failed to send: " + response.description());
                }
            }
            @Override
            public void onFailure(SendMessage request, IOException e) {
                Log.e("TelegramManager", "Network error sending", e);
            }
        });
    }

    private void broadcastMessage(String text) {
        for (Long chatId : getBroadcastTargets()) {
            sendToChat(chatId, text);
        }
    }

    private void notifyOtherUsers(long excludeUserId, String message) {
        Long groupChatId = SettingsManager.getGroupChatIdLong(appContext);
        if (groupChatId != null) {
            sendToChat(groupChatId, message);
            return;
        }
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        for (Long chatId : allowedIds) {
            if (chatId != excludeUserId) {
                sendToChat(chatId, message);
            }
        }
    }

    // ─── Start / Stop with optimistic state ───

    private boolean tryStartListening() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        VadService.startService(appContext);
        VadService.isVadListening = true; // optimistic UI state until service confirms
        return true;
    }

    private void stopListeningNow() {
        VadService.stopService(appContext);
        VadService.isVadListening = false; // optimistic UI state until service confirms
    }

    private static final String MIC_WARNING =
            "⚠️ Microphone permission is not granted. Open the app once and allow it, then try again.";

    // ─── Incoming messages ───

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

            broadcastMessage("🎤 " + label + " from " + senderName);
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
            if (!tryStartListening()) {
                sendToChat(chatId, MIC_WARNING);
            } else {
                sendToChat(chatId, "🟩🟩🟩 Start 🟩🟩🟩");
            }
            notifyOtherUsers(userId, userName + " hit Start");
        } else if (cmd.equals("/stop") || cmd.startsWith("/stop@")) {
            stopListeningNow();
            sendToChat(chatId, "🟥🟥🟥 Stop 🟥🟥🟥");
            notifyOtherUsers(userId, userName + " hit Stop");
        }
        // Any other message is ignored
    }

    // ─── Callbacks ───

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
            editMainMenuWithState(chatId, messageId, ok);
        } else if (data.equals("stop_vad")) {
            stopListeningNow();
            notifyOtherUsers(userId, userName + " hit Stop");
            editMainMenuWithState(chatId, messageId, false);
        } else if (data.equals("toggle_silent")) {
            boolean current = SettingsManager.isSilentMode(appContext);
            SettingsManager.saveSilentMode(appContext, !current);
            editMainMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Silent Mode " + (!current ? "ON" : "OFF"));
        } else if (data.equals("settings")) {
            sendSettingsMenu(chatId, messageId);
        } else if (data.equals("back_main")) {
            editMainMenu(chatId, messageId);
        } else if (data.startsWith("toggle_wait")) {
            boolean current = SettingsManager.getWaitForEnd(appContext);
            SettingsManager.saveWaitForEnd(appContext, !current);
            sendSettingsMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Wait For End " + (!current ? "ON" : "OFF"));
        } else if (data.equals("toggle_use_embeddings")) {
            boolean current = SettingsManager.getUseEmbeddings(appContext);
            SettingsManager.saveUseEmbeddings(appContext, !current);
            sendSettingsMenu(chatId, messageId);
            notifyOtherUsers(userId, userName + " Toggled Use Embeddings " + (!current ? "ON" : "OFF"));
        } else if (data.equals("toggle_repeat_reminder")) {
            boolean current = SettingsManager.getRepeatReminder(appContext);
            SettingsManager.saveRepeatReminder(appContext, !current);
            sendSettingsMenu(chatId, messageId);
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
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("dur_")) {
            int current = SettingsManager.getDurationThreshold(appContext);
            int newValue = current;
            if (data.equals("dur_inc") && current < 10) newValue = current + 1;
            if (data.equals("dur_dec") && current > 1) newValue = current - 1;
            if (newValue != current) {
                SettingsManager.saveDurationThreshold(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Ignore Short from " + current + "s to " + newValue + "s");
            }
            sendSettingsMenu(chatId, messageId);
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
            sendSettingsMenu(chatId, messageId);
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
            sendSettingsMenu(chatId, messageId);
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
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rem_min_")) {
            int current = SettingsManager.getReminderSpeechMin(appContext);
            int newValue = current;
            if (data.equals("rem_min_inc") && current < 180) newValue = current + 5;
            if (data.equals("rem_min_dec") && current > 30) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveReminderSpeechMin(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Reminder After (Min) from " + current + "s to " + newValue + "s");
            }
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rem_max_")) {
            int current = SettingsManager.getReminderSpeechMax(appContext);
            int newValue = current;
            if (data.equals("rem_max_inc") && current < 180) newValue = current + 5;
            if (data.equals("rem_max_dec") && current > 30) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveReminderSpeechMax(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Reminder After (Max) from " + current + "s to " + newValue + "s");
            }
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rep_min_")) {
            int current = SettingsManager.getRepeatReminderMin(appContext);
            int newValue = current;
            if (data.equals("rep_min_inc") && current < 30) newValue = current + 5;
            if (data.equals("rep_min_dec") && current > 5) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveRepeatReminderMin(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Repeat Reminder After (Min) from " + current + "s to " + newValue + "s");
            }
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rep_max_")) {
            int current = SettingsManager.getRepeatReminderMax(appContext);
            int newValue = current;
            if (data.equals("rep_max_inc") && current < 30) newValue = current + 5;
            if (data.equals("rep_max_dec") && current > 5) newValue = current - 5;
            if (newValue != current) {
                SettingsManager.saveRepeatReminderMax(appContext, newValue);
                notifyOtherUsers(userId, userName + " changed Repeat Reminder After (Max) from " + current + "s to " + newValue + "s");
            }
            sendSettingsMenu(chatId, messageId);
        }

        bot.execute(new AnswerCallbackQuery(callback.id()));
    }

    // ─── Main menu ───

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
        bot.execute(msg);
    }

    private void editMainMenu(long chatId, int messageId) {
        editMainMenuWithState(chatId, messageId, VadService.isVadListening);
    }

    private void editMainMenuWithState(long chatId, int messageId, boolean listening) {
        EditMessageText edit = new EditMessageText(chatId, messageId, mainMenuText())
                .replyMarkup(buildMainMenuMarkup(listening));
        bot.execute(edit);
    }

    // ─── Settings menu ───

    private void sendSettingsMenu(long chatId, int messageId) {
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

        String text = "⚙️ Nurse VAD Settings";

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(
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

        EditMessageText edit = new EditMessageText(chatId, messageId, text).replyMarkup(markup);
        bot.execute(edit);
    }

    // ─── Voice message download with retry ───

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

                            File tempFile = new File(appContext.getCacheDir(), "tg_media_" + System.currentTimeMillis() + ext);
                            FileOutputStream output = new FileOutputStream(tempFile);
                            InputStream input = connection.getInputStream();

                            byte[] data = new byte[4096];
                            int count;
                            while ((count = input.read(data)) != -1) {
                                output.write(data, 0, count);
                            }
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
            DebugLogger.log(label + " download retry " + (attempt + 2) + " in " + delay + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                downloadWithRetry(fileId, senderName, attempt + 1, isAudio, null);
            }, delay);
        } else {
            DebugLogger.log("All " + label.toLowerCase() + " download attempts failed for sender: " + senderName);
            EventRepository.getInstance().addEvent(
                new LogEvent(LogEvent.Type.WARNING, label + " download failed from " + senderName)
            );
            broadcastMessage("⚠️ Failed to download " + label.toLowerCase() + " from " + senderName);
        }
    }

    // ─── Outgoing event messages ───

    public void sendAudioEvent(String wavUri, int level, String responseFileName, boolean isPoni) {
        if (!isRunning || bot == null) return;
        List<Long> targets = getBroadcastTargets();
        if (targets.isEmpty()) return;

        try {
            File file = new File(wavUri.replace("file://", ""));
            if (!file.exists()) return;

            String emoji;
            if (isPoni) {
                emoji = "⚪️";
            } else {
                switch (level) {
                    case 1:  emoji = "🔵"; break;
                    case 2:  emoji = "🟢"; break;
                    case 3:  emoji = "🟡"; break;
                    case 4:  emoji = "🟠"; break;
                    case 5:  emoji = "🔴"; break;
                    default: emoji = "⚪️"; break;
                }
            }

            // Always show the response file name (or fallback); never "PONI is talking"
            String caption = emoji + " " + (responseFileName != null ? responseFileName : "No file found");

            boolean useEmb = SettingsManager.getUseEmbeddings(appContext);
            String performer = (useEmb && !isPoni) ? "Client" : "Someone";

            for (Long chatId : targets) {
                SendAudio sendAudio = new SendAudio(chatId, file)
                        .caption(caption)
                        .title("Speech Detected")
                        .performer(performer);

                bot.execute(sendAudio, new Callback<SendAudio, SendResponse>() {
                    @Override
                    public void onResponse(SendAudio request, SendResponse response) {
                        if (!response.isOk()) {
                            Log.e("TelegramManager", "Failed to send audio: " + response.description());
                        }
                    }
                    @Override
                    public void onFailure(SendAudio request, IOException e) {
                        Log.e("TelegramManager", "Network error sending audio", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("TelegramManager", "Error preparing audio file", e);
        }
    }

    public void sendTextMessage(String text) {
        if (!isRunning || bot == null) return;
        for (Long chatId : getBroadcastTargets()) {
            sendToChat(chatId, text);
        }
    }
}