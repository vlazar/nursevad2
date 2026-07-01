package com.example.nursevad;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Set;

public class SettingsManager {
    private static final String PREFS_NAME = "NurseVadPrefs";
    private static final String SECURE_PREFS_NAME = "NurseVadSecurePrefs";
    
    // Standard Keys
    private static final String KEY_THRESHOLDS = "thresholds_";
    private static final String KEY_DURATION = "duration_threshold";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_FOLDER_NAME = "folder_name";
    private static final String KEY_DELAY = "delay_response";
    private static final String KEY_WAIT_FOR_END = "wait_for_end";

    // Secure Keys
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_USER_IDS = "user_ids";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences getSecurePrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            // Fallback to standard prefs if encryption fails (rare)
            return getPrefs(context);
        }
    }

    // --- Standard Settings ---
    public static int[] getThresholds(Context context) {
        SharedPreferences prefs = getPrefs(context);
        int[] defaults = {20, 40, 60, 80, 100};
        int[] result = new int[5];
        for (int i = 0; i < 5; i++) result[i] = prefs.getInt(KEY_THRESHOLDS + i, defaults[i]);
        return result;
    }
    public static void saveThresholds(Context context, int[] thresholds) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        for (int i = 0; i < 5; i++) editor.putInt(KEY_THRESHOLDS + i, thresholds[i]);
        editor.apply();
    }
    public static int getDurationThreshold(Context context) { return getPrefs(context).getInt(KEY_DURATION, 1); }
    public static void saveDurationThreshold(Context context, int duration) { getPrefs(context).edit().putInt(KEY_DURATION, duration).apply(); }
    public static String getFolderUri(Context context) { return getPrefs(context).getString(KEY_FOLDER_URI, null); }
    public static String getFolderName(Context context) { return getPrefs(context).getString(KEY_FOLDER_NAME, null); }
    public static void saveFolder(Context context, String uri, String name) {
        getPrefs(context).edit().putString(KEY_FOLDER_URI, uri).putString(KEY_FOLDER_NAME, name).apply();
    }
    public static int getDelay(Context context) { return getPrefs(context).getInt(KEY_DELAY, 0); }
    public static void saveDelay(Context context, int delay) { getPrefs(context).edit().putInt(KEY_DELAY, delay).apply(); }
    public static boolean getWaitForEnd(Context context) { return getPrefs(context).getBoolean(KEY_WAIT_FOR_END, true); }
    public static void saveWaitForEnd(Context context, boolean wait) { getPrefs(context).edit().putBoolean(KEY_WAIT_FOR_END, wait).apply(); }

    // --- Secure Telegram Settings ---
    public static String getBotToken(Context context) {
        return getSecurePrefs(context).getString(KEY_BOT_TOKEN, "");
    }
    public static void saveBotToken(Context context, String token) {
        getSecurePrefs(context).edit().putString(KEY_BOT_TOKEN, token).apply();
    }

    public static Set<Long> getAllowedUserIds(Context context) {
        String idsStr = getSecurePrefs(context).getString(KEY_USER_IDS, "");
        Set<Long> ids = new HashSet<>();
        if (idsStr != null && !idsStr.trim().isEmpty()) {
            for (String s : idsStr.split("[,\\s]+")) {
                try { ids.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return ids;
    }
    public static void saveAllowedUserIds(Context context, String idsString) {
        getSecurePrefs(context).edit().putString(KEY_USER_IDS, idsString).apply();
    }
}