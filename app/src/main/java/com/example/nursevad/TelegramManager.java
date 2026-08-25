package com.example.nursevad;

import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
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
import com.pengrad.telegrambot.model.request.ParseMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
        
        if (token == null || token.isEmpty() || initialIds.isEmpty()) {
            Log.d("TelegramManager", "Bot not started: Missing token or user IDs.");
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

    private boolean isAuthorized(long userId, Set<Long> allowedIds) {
        return allowedIds.contains(userId);
    }

    private void handleMessage(Message message) {
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        if (message.from() == null || !isAuthorized(message.from().id(), allowedIds)) return;
        
        if (message.voice() != null) {
            String senderName = "Telegram Bot";
            if (message.from().firstName() != null) {
                senderName = message.from().firstName();
                if (message.from().lastName() != null) senderName += " " + message.from().lastName();
            } else if (message.from().username() != null) {
                senderName = message.from().username();
            }
            
            broadcastMessage("🔵 Voice Message from " + senderName);
            downloadAndQueueVoice(message.voice().fileId(), senderName);
            return;
        }
        
        sendMainMenu(message.chat().id(), message.messageId());
    }

    private void broadcastMessage(String text) {
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        for (Long chatId : allowedIds) {
            bot.execute(new SendMessage(chatId, text), new Callback<SendMessage, SendResponse>() {
                @Override
                public void onResponse(SendMessage request, SendResponse response) {
                    if (!response.isOk()) {
                        Log.e("TelegramManager", "Failed to broadcast: " + response.description());
                    }
                }
                @Override
                public void onFailure(SendMessage request, IOException e) {
                    Log.e("TelegramManager", "Network error broadcasting", e);
                }
            });
        }
    }

    private void downloadAndQueueVoice(String fileId, String senderName) {
        downloadWithRetry(fileId, senderName, 0);
    }

    private void downloadWithRetry(String fileId, String senderName, int attempt) {
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

                            File tempFile = new File(appContext.getCacheDir(), "tg_voice_" + System.currentTimeMillis() + ".ogg");
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
                            appContext.startService(i);

                        } catch (Exception e) {
                            Log.e("TelegramManager", "Download attempt " + (attempt + 1) + " failed", e);
                            handleDownloadRetry(fileId, senderName, attempt, MAX_RETRIES);
                        }
                    }).start();
                } else {
                    Log.e("TelegramManager", "GetFile returned error on attempt " + (attempt + 1));
                    handleDownloadRetry(fileId, senderName, attempt, MAX_RETRIES);
                }
            }

            @Override
            public void onFailure(GetFile request, IOException e) {
                Log.e("TelegramManager", "GetFile network failure on attempt " + (attempt + 1), e);
                handleDownloadRetry(fileId, senderName, attempt, MAX_RETRIES);
            }
        });
    }

    private void handleDownloadRetry(String fileId, String senderName, int attempt, int maxRetries) {
        if (attempt < maxRetries - 1) {
            long delay = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s
            DebugLogger.log("Voice download retry " + (attempt + 2) + " in " + delay + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                downloadWithRetry(fileId, senderName, attempt + 1);
            }, delay);
        } else {
            // All retries exhausted — log warning to events list and Telegram
            DebugLogger.log("All voice download attempts failed for sender: " + senderName);
            EventRepository.getInstance().addEvent(
                new LogEvent(LogEvent.Type.WARNING, "Voice message download failed from " + senderName)
            );
            broadcastMessage("⚠️ Failed to download voice message from " + senderName);
        }
    }

    private void handleCallback(CallbackQuery callback) {
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        if (callback.from() == null || !isAuthorized(callback.from().id(), allowedIds)) return;
        
        long chatId = callback.message().chat().id();
        int messageId = callback.message().messageId();
        String data = callback.data();

        if (data.equals("start_vad")) {
            VadService.startService(appContext);
            editMessage(chatId, messageId, "🟩 Start\n🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩");
        } else if (data.equals("stop_vad")) {
            VadService.stopService(appContext);
            editMessage(chatId, messageId, "🟥 Stop\n🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥🟥");
        } else if (data.equals("toggle_silent")) {
            boolean current = SettingsManager.isSilentMode(appContext);
            SettingsManager.saveSilentMode(appContext, !current);
            editMainMenu(chatId, messageId);
        } else if (data.equals("toggle_use_embeddings")) {
            boolean current = SettingsManager.getUseEmbeddings(appContext);
            SettingsManager.saveUseEmbeddings(appContext, !current);
            editMainMenu(chatId, messageId);
        } else if (data.equals("status")) {
            String state = VadService.isVadListening ? "Listening..." : "Idle";
            editMessage(chatId, messageId, "📊 Current State: " + state);
        } else if (data.equals("settings")) {
            sendSettingsMenu(chatId, messageId);
        } else if (data.equals("back_main")) {
            editMainMenu(chatId, messageId);
        } else if (data.startsWith("toggle_wait")) {
            boolean current = SettingsManager.getWaitForEnd(appContext);
            SettingsManager.saveWaitForEnd(appContext, !current);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("delay_")) {
            int current = SettingsManager.getDelay(appContext);
            if (data.equals("delay_inc") && current < 10) SettingsManager.saveDelay(appContext, current + 1);
            if (data.equals("delay_dec") && current > 0) SettingsManager.saveDelay(appContext, current - 1);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("dur_")) {
            int current = SettingsManager.getDurationThreshold(appContext);
            if (data.equals("dur_inc") && current < 10) SettingsManager.saveDurationThreshold(appContext, current + 1);
            if (data.equals("dur_dec") && current > 1) SettingsManager.saveDurationThreshold(appContext, current - 1);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("thresh_")) {
            handleThresholdCallback(data);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("poi_thresh_")) {
            int current = SettingsManager.getPoiThreshold(appContext);
            if (data.equals("poi_thresh_inc") && current < 95) SettingsManager.savePoiThreshold(appContext, current + 5);
            if (data.equals("poi_thresh_dec") && current > 55) SettingsManager.savePoiThreshold(appContext, current - 5);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("poni_thresh_")) {
            int current = SettingsManager.getPoniThreshold(appContext);
            if (data.equals("poni_thresh_inc") && current < 95) SettingsManager.savePoniThreshold(appContext, current + 5);
            if (data.equals("poni_thresh_dec") && current > 55) SettingsManager.savePoniThreshold(appContext, current - 5);
            sendSettingsMenu(chatId, messageId);
        } else if (data.equals("toggle_repeat_reminder")) {
            boolean current = SettingsManager.getRepeatReminder(appContext);
            SettingsManager.saveRepeatReminder(appContext, !current);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rem_min_")) {
            int current = SettingsManager.getReminderSpeechMin(appContext);
            if (data.equals("rem_min_inc") && current < 180) SettingsManager.saveReminderSpeechMin(appContext, current + 5);
            if (data.equals("rem_min_dec") && current > 30) SettingsManager.saveReminderSpeechMin(appContext, current - 5);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rem_max_")) {
            int current = SettingsManager.getReminderSpeechMax(appContext);
            if (data.equals("rem_max_inc") && current < 180) SettingsManager.saveReminderSpeechMax(appContext, current + 5);
            if (data.equals("rem_max_dec") && current > 30) SettingsManager.saveReminderSpeechMax(appContext, current - 5);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rep_min_")) {
            int current = SettingsManager.getRepeatReminderMin(appContext);
            if (data.equals("rep_min_inc") && current < 30) SettingsManager.saveRepeatReminderMin(appContext, current + 5);
            if (data.equals("rep_min_dec") && current > 5) SettingsManager.saveRepeatReminderMin(appContext, current - 5);
            sendSettingsMenu(chatId, messageId);
        } else if (data.startsWith("rep_max_")) {
            int current = SettingsManager.getRepeatReminderMax(appContext);
            if (data.equals("rep_max_inc") && current < 30) SettingsManager.saveRepeatReminderMax(appContext, current + 5);
            if (data.equals("rep_max_dec") && current > 5) SettingsManager.saveRepeatReminderMax(appContext, current - 5);
            sendSettingsMenu(chatId, messageId);
        }
        
        bot.execute(new AnswerCallbackQuery(callback.id()));
    }

    private void handleThresholdCallback(String data) {
        String[] parts = data.split("_");
        if (parts.length < 3) return;
        int level = Integer.parseInt(parts[1]) - 1; 
        boolean inc = parts[2].equals("inc");
        
        int[] thresholds = SettingsManager.getThresholds(appContext);
        if (inc && thresholds[level] < 100) thresholds[level] += 5;
        if (!inc && thresholds[level] > 0) thresholds[level] -= 5;
        
        SettingsManager.saveThresholds(appContext, thresholds);
    }

    private void sendMainMenu(long chatId, int replyToId) {
        String state = VadService.isVadListening ? "🟢 Listening..." : "⚪ Idle";
        boolean silent = SettingsManager.isSilentMode(appContext);
        boolean useEmb = SettingsManager.getUseEmbeddings(appContext);
        String text = "*Nurse VAD Control Panel*\nState: " + state;
        
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("▶ Start").callbackData("start_vad"),
                        new InlineKeyboardButton("⏹ Stop").callbackData("stop_vad")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("Toggle Silent Mode (" + (silent ? "ON" : "OFF") + ")").callbackData("toggle_silent")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("Use Embeddings (" + (useEmb ? "ON" : "OFF") + ")").callbackData("toggle_use_embeddings")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("📊 Status").callbackData("status")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("⚙️ Settings").callbackData("settings")
                }
        );

        SendMessage msg = new SendMessage(chatId, text).parseMode(ParseMode.Markdown).replyMarkup(markup);
        if (replyToId > 0) msg.replyToMessageId(replyToId);
        bot.execute(msg);
    }

    private void editMainMenu(long chatId, int messageId) {
        String state = VadService.isVadListening ? "🟢 Listening..." : "⚪ Idle";
        boolean silent = SettingsManager.isSilentMode(appContext);
        boolean useEmb = SettingsManager.getUseEmbeddings(appContext);
        String text = "*Nurse VAD Control Panel*\nState: " + state;
        
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("▶ Start").callbackData("start_vad"),
                        new InlineKeyboardButton("⏹ Stop").callbackData("stop_vad")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("Toggle Silent Mode (" + (silent ? "ON" : "OFF") + ")").callbackData("toggle_silent")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("Use Embeddings (" + (useEmb ? "ON" : "OFF") + ")").callbackData("toggle_use_embeddings")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("📊 Status").callbackData("status")
                },
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("⚙️ Settings").callbackData("settings")
                }
        );

        EditMessageText edit = new EditMessageText(chatId, messageId, text)
                .parseMode(ParseMode.Markdown)
                .replyMarkup(markup);
        bot.execute(edit);
    }

    private void sendSettingsMenu(long chatId, int messageId) {
        boolean wait = SettingsManager.getWaitForEnd(appContext);
        int delay = SettingsManager.getDelay(appContext);
        int dur = SettingsManager.getDurationThreshold(appContext);
        int[] thresh = SettingsManager.getThresholds(appContext);
        float poiTh = SettingsManager.getPoiThreshold(appContext) / 100f;
        float poniTh = SettingsManager.getPoniThreshold(appContext) / 100f;
        boolean repeatRem = SettingsManager.getRepeatReminder(appContext);
        int repMin = SettingsManager.getRepeatReminderMin(appContext);
        int repMax = SettingsManager.getRepeatReminderMax(appContext);
        int remMin = SettingsManager.getReminderSpeechMin(appContext);
        int remMax = SettingsManager.getReminderSpeechMax(appContext);

        String text = "*⚙️ Settings*\n" +
                "Wait for End: " + (wait ? "ON" : "OFF") + "\n" +
                "Delay: " + delay + "s\n" +
                "Ignore Short: " + dur + "s\n" +
                "Thresholds: " + thresh[0] + "%, " + thresh[1] + "%, " + thresh[2] + "%, " + thresh[3] + "%, " + thresh[4] + "%";

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

        EditMessageText edit = new EditMessageText(chatId, messageId, text)
                .parseMode(ParseMode.Markdown)
                .replyMarkup(markup);
        bot.execute(edit);
    }

    private void editMessage(long chatId, int messageId, String text) {
        bot.execute(new EditMessageText(chatId, messageId, text).parseMode(ParseMode.Markdown));
    }

    public void sendAudioEvent(String wavUri, int level, String responseFileName, boolean isPoni) {
        if (!isRunning || bot == null) return;
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        if (allowedIds.isEmpty()) return;

        try {
            File file = new File(wavUri.replace("file://", ""));
            if (!file.exists()) return;

            String emoji = isPoni ? "⚪️" : (level == 3 ? "🌕" : level == 4 ? "🟠" : level == 5 ? "🔴" : "🟢");
            String caption = emoji + " " + (isPoni ? "PONI is talking" : (responseFileName != null ? responseFileName : "No file found"));

            for (Long chatId : allowedIds) {
                SendAudio sendAudio = new SendAudio(chatId, file)
                        .caption(caption)
                        .title("Nurse VAD Recording");
                
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
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        for (Long chatId : allowedIds) {
            bot.execute(new SendMessage(chatId, text), new Callback<SendMessage, SendResponse>() {
                @Override
                public void onResponse(SendMessage request, SendResponse response) {
                    if (!response.isOk()) {
                        Log.e("TelegramManager", "Failed to send text: " + response.description());
                    }
                }
                @Override
                public void onFailure(SendMessage request, IOException e) {
                    Log.e("TelegramManager", "Network error sending text", e);
                }
            });
        }
    }
}