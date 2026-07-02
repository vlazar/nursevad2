package com.example.nursevad;

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
        
        // Intercept Voice Messages
        if (message.voice() != null) {
            downloadAndQueueVoice(message.voice().fileId());
            return;
        }
        
        sendMainMenu(message.chat().id(), message.messageId());
    }

    private void downloadAndQueueVoice(String fileId) {
        bot.execute(new GetFile(fileId), new Callback<GetFile, GetFileResponse>() {
            @Override
            public void onResponse(GetFile request, GetFileResponse response) {
                if (response.isOk()) {
                    String filePath = response.file().filePath();
                    String fileUrl = bot.getFullFilePath(filePath);
                    
                    // Run download on a background thread to avoid blocking the Telegram update thread
                    new Thread(() -> {
                        try {
                            URL url = new URL(fileUrl);
                            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
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
                            
                            // Send to VadService queue
                            Intent i = new Intent(appContext, VadService.class);
                            i.setAction("PLAY_TELEGRAM_VOICE");
                            i.putExtra("PATH", tempFile.getAbsolutePath());
                            appContext.startService(i);
                            
                        } catch (Exception e) {
                            Log.e("TelegramManager", "Failed to download voice", e);
                        }
                    }).start();
                }
            }

            @Override
            public void onFailure(GetFile request, IOException e) {
                Log.e("TelegramManager", "Failed to get file info", e);
            }
        });
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
        } else if (data.equals("status")) {
            String state = VadService.isVadListening ? "Listening..." : "Idle";
            editMessage(chatId, messageId, "📊 Current State: " + state);
        } else if (data.equals("settings")) {
            sendSettingsMenu(chatId, messageId);
        } else if (data.equals("back_main")) {
            sendMainMenu(chatId, messageId);
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
        String text = "*Nurse VAD Control Panel*\nState: " + state;
        
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("▶ Start").callbackData("start_vad"),
                        new InlineKeyboardButton("⏹ Stop").callbackData("stop_vad")
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

    private void sendSettingsMenu(long chatId, int messageId) {
        boolean wait = SettingsManager.getWaitForEnd(appContext);
        int delay = SettingsManager.getDelay(appContext);
        int dur = SettingsManager.getDurationThreshold(appContext);
        int[] thresh = SettingsManager.getThresholds(appContext);

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

    public void sendAudioEvent(String wavUri, int level, String responseFileName) {
        if (!isRunning || bot == null) return;
        Set<Long> allowedIds = SettingsManager.getAllowedUserIds(appContext);
        if (allowedIds.isEmpty()) return;

        try {
            File file = new File(wavUri.replace("file://", ""));
            if (!file.exists()) return;

            String emoji = "🟢";
            if (level == 3) emoji = "🌕";
            else if (level == 4) emoji = "🟠";
            else if (level == 5) emoji = "🔴";

            String caption = emoji + " " + (responseFileName != null ? responseFileName : "No response");

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
}