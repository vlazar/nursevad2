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

    private static SharedPreferences prefs;

    private static SharedPreferences getPrefs(Context context) {
        if (prefs == null) {
            // MODE_PRIVATE ensures this file is securely sandboxed and inaccessible to other apps
            prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }

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
        editor.commit(); // Synchronous write
    }

    public static int getDurationThreshold(Context context) { return getPrefs(context).getInt(KEY_DURATION, 1); }
    public static void saveDurationThreshold(Context context, int duration) { getPrefs(context).edit().putInt(KEY_DURATION, duration).commit(); }
    
    public static String getFolderUri(Context context) { return getPrefs(context).getString(KEY_FOLDER_URI, null); }
    public static String getFolderName(Context context) { return getPrefs(context).getString(KEY_FOLDER_NAME, null); }
    public static void saveFolder(Context context, String uri, String name) {
        getPrefs(context).edit().putString(KEY_FOLDER_URI, uri).putString(KEY_FOLDER_NAME, name).commit();
    }

    public static int getDelay(Context context) { return getPrefs(context).getInt(KEY_DELAY, 0); }
    public static void saveDelay(Context context, int delay) { getPrefs(context).edit().putInt(KEY_DELAY, delay).commit(); }

    public static boolean getWaitForEnd(Context context) { return getPrefs(context).getBoolean(KEY_WAIT_FOR_END, true); }
    public static void saveWaitForEnd(Context context, boolean wait) { getPrefs(context).edit().putBoolean(KEY_WAIT_FOR_END, wait).commit(); }

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

    public static boolean isSilentMode(Context context) { return getPrefs(context).getBoolean("silent_mode", false); }
    public static void saveSilentMode(Context context, boolean silent) { getPrefs(context).edit().putBoolean("silent_mode", silent).commit(); }

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

    public static String getEmbeddingsFolderUri(Context context) { return getPrefs(context).getString(KEY_EMBEDDINGS_URI, null); }
    public static String getEmbeddingsFolderName(Context context) { return getPrefs(context).getString(KEY_EMBEDDINGS_NAME, null); }
    public static void saveEmbeddingsFolder(Context context, String uri, String name) {
        getPrefs(context).edit().putString(KEY_EMBEDDINGS_URI, uri).putString(KEY_EMBEDDINGS_NAME, name).commit();
    }
}