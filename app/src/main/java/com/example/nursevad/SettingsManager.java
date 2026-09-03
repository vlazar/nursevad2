package com.example.nursevad;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class SettingsManager {
    private static final String PREFS_NAME = "NurseVadPrefs";

    private static final String KEY_THRESHOLDS = "thresholds_";
    private static final String KEY_DURATION = "duration_threshold";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_FOLDER_NAME = "folder_name";
    private static final String KEY_DELAY = "delay_response";
    private static final String KEY_WAIT_FOR_END = "wait_for_end";
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_USER_IDS = "user_ids";
    private static final String KEY_EMBEDDINGS_URI = "embeddings_uri";
    private static final String KEY_EMBEDDINGS_NAME = "embeddings_name";
    private static final String KEY_USE_EMBEDDINGS = "use_embeddings";
    private static final String KEY_POI_THRESHOLD = "poi_threshold";
    private static final String KEY_PONI_THRESHOLD = "poni_threshold";
    private static final String KEY_REPEAT_REMINDER = "repeat_reminder";
    private static final String KEY_REPEAT_REMINDER_MIN = "repeat_reminder_min";
    private static final String KEY_REPEAT_REMINDER_MAX = "repeat_reminder_max";
    private static final String KEY_SILENT_MODE = "silent_mode";

    private static SharedPreferences prefs;

    private static SharedPreferences getPrefs(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    // --- Thresholds ---
    public static int[] getThresholds(Context context) {
        SharedPreferences p = getPrefs(context);
        int[] defaults = {20, 40, 60, 80, 100};
        int[] result = new int[5];
        for (int i = 0; i < 5; i++) result[i] = p.getInt(KEY_THRESHOLDS + i, defaults[i]);
        return result;
    }
    public static void saveThresholds(Context context, int[] thresholds) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        for (int i = 0; i < 5; i++) editor.putInt(KEY_THRESHOLDS + i, thresholds[i]);
        editor.commit();
    }

    // --- Duration ---
    public static int getDurationThreshold(Context context) { return getPrefs(context).getInt(KEY_DURATION, 1); }
    public static void saveDurationThreshold(Context context, int duration) { getPrefs(context).edit().putInt(KEY_DURATION, duration).commit(); }

    // --- Audio Folder ---
    public static String getFolderUri(Context context) { return getPrefs(context).getString(KEY_FOLDER_URI, null); }
    public static String getFolderName(Context context) { return getPrefs(context).getString(KEY_FOLDER_NAME, null); }
    public static void saveFolder(Context context, String uri, String name) {
        getPrefs(context).edit().putString(KEY_FOLDER_URI, uri).putString(KEY_FOLDER_NAME, name).commit();
    }

    // --- Delay ---
    public static int getDelay(Context context) { return getPrefs(context).getInt(KEY_DELAY, 0); }
    public static void saveDelay(Context context, int delay) { getPrefs(context).edit().putInt(KEY_DELAY, delay).commit(); }

    // --- Wait For End ---
    public static boolean getWaitForEnd(Context context) { return getPrefs(context).getBoolean(KEY_WAIT_FOR_END, true); }
    public static void saveWaitForEnd(Context context, boolean wait) { getPrefs(context).edit().putBoolean(KEY_WAIT_FOR_END, wait).commit(); }

    // --- Telegram Bot ---
    public static String getBotToken(Context context) { return getPrefs(context).getString(KEY_BOT_TOKEN, ""); }
    public static void saveBotToken(Context context, String token) { getPrefs(context).edit().putString(KEY_BOT_TOKEN, token).commit(); }

    public static Set<Long> getAllowedUserIds(Context context) {
        String idsStr = getPrefs(context).getString(KEY_USER_IDS, "");
        Set<Long> ids = new HashSet<>();
        if (idsStr != null && !idsStr.trim().isEmpty()) {
            for (String s : idsStr.split("[,\\s]+")) {
                try { ids.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return ids;
    }
    public static void saveAllowedUserIds(Context context, String idsString) {
        getPrefs(context).edit().putString(KEY_USER_IDS, idsString).commit();
    }

    // --- Embeddings Folder ---
    public static String getEmbeddingsFolderUri(Context context) { return getPrefs(context).getString(KEY_EMBEDDINGS_URI, null); }
    public static String getEmbeddingsFolderName(Context context) { return getPrefs(context).getString(KEY_EMBEDDINGS_NAME, null); }
    public static void saveEmbeddingsFolder(Context context, String uri, String name) {
        getPrefs(context).edit().putString(KEY_EMBEDDINGS_URI, uri).putString(KEY_EMBEDDINGS_NAME, name).commit();
    }

    // --- Use Embeddings Toggle ---
    public static boolean getUseEmbeddings(Context context) { return getPrefs(context).getBoolean(KEY_USE_EMBEDDINGS, true); }
    public static void saveUseEmbeddings(Context context, boolean use) { getPrefs(context).edit().putBoolean(KEY_USE_EMBEDDINGS, use).commit(); }

    // --- POI/PONI Thresholds (stored as int hundredths: 75 = 0.75) ---
    public static int getPoiThreshold(Context context) { return getPrefs(context).getInt(KEY_POI_THRESHOLD, 75); }
    public static void savePoiThreshold(Context context, int val) { getPrefs(context).edit().putInt(KEY_POI_THRESHOLD, val).commit(); }
    public static int getPoniThreshold(Context context) { return getPrefs(context).getInt(KEY_PONI_THRESHOLD, 75); }
    public static void savePoniThreshold(Context context, int val) { getPrefs(context).edit().putInt(KEY_PONI_THRESHOLD, val).commit(); }

    // --- Repeat Reminder ---
    public static boolean getRepeatReminder(Context context) { return getPrefs(context).getBoolean(KEY_REPEAT_REMINDER, true); }
    public static void saveRepeatReminder(Context context, boolean val) { getPrefs(context).edit().putBoolean(KEY_REPEAT_REMINDER, val).commit(); }
    public static int getRepeatReminderMin(Context context) { return getPrefs(context).getInt(KEY_REPEAT_REMINDER_MIN, 10); }
    public static void saveRepeatReminderMin(Context context, int val) { getPrefs(context).edit().putInt(KEY_REPEAT_REMINDER_MIN, val).commit(); }
    public static int getRepeatReminderMax(Context context) { return getPrefs(context).getInt(KEY_REPEAT_REMINDER_MAX, 10); }
    public static void saveRepeatReminderMax(Context context, int val) { getPrefs(context).edit().putInt(KEY_REPEAT_REMINDER_MAX, val).commit(); }

    // --- Silent Mode ---
    public static boolean isSilentMode(Context context) { return getPrefs(context).getBoolean(KEY_SILENT_MODE, false); }
    public static void saveSilentMode(Context context, boolean silent) { getPrefs(context).edit().putBoolean(KEY_SILENT_MODE, silent).commit(); }

    // --- Reminder Trigger ---
    public static int getReminderTrigger(Context context) { return getPrefs(context).getInt("reminder_trigger", 0); }
    public static void saveReminderTrigger(Context context, int trigger) { getPrefs(context).edit().putInt("reminder_trigger", trigger).commit(); }
    public static int getReminderStartMin(Context context) { return getPrefs(context).getInt("rem_start_min", 90); }
    public static void saveReminderStartMin(Context context, int val) { getPrefs(context).edit().putInt("rem_start_min", val).commit(); }
    public static int getReminderStartMax(Context context) { return getPrefs(context).getInt("rem_start_max", 95); }
    public static void saveReminderStartMax(Context context, int val) { getPrefs(context).edit().putInt("rem_start_max", val).commit(); }
    public static int getReminderSpeechMin(Context context) { return getPrefs(context).getInt("rem_speech_min", 90); }
    public static void saveReminderSpeechMin(Context context, int val) { getPrefs(context).edit().putInt("rem_speech_min", val).commit(); }
    public static int getReminderSpeechMax(Context context) { return getPrefs(context).getInt("rem_speech_max", 120); }
    public static void saveReminderSpeechMax(Context context, int val) { getPrefs(context).edit().putInt("rem_speech_max", val).commit(); }
}