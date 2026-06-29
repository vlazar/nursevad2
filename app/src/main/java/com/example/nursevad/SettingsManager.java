package com.example.nursevad;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREFS_NAME = "NurseVadPrefs";
    private static final String KEY_THRESHOLDS = "thresholds_";
    private static final String KEY_DURATION = "duration_threshold";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_FOLDER_NAME = "folder_name";
    private static final String KEY_DELAY = "delay_response";
    private static final String KEY_WAIT_FOR_END = "wait_for_end";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

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
}