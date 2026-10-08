package com.example.nursevad;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugLogger {
    private static final String TAG = "NurseVAD_Debug";
    private static final long MAX_BYTES = 10L * 1024 * 1024; // 10 MB rotation cap
    private static File logFile;
    private static File errFile;
    private static Context appContext;
    private static boolean initialized = false;

    public static synchronized void init(Context context) {
        if (initialized) return; // idempotent: two services share one logger
        appContext = context.getApplicationContext();
        File dir = appContext.getExternalFilesDir(null);
        if (dir == null) dir = appContext.getFilesDir();
        logFile = new File(dir, "log.txt");
        errFile = new File(dir, "log_errors.txt");
        initialized = true;
        write(logFile, "=== Log started at " + new Date().toString() + " ===", true);
    }

    public static void log(String message) {
        Log.d(TAG, message);
        write(logFile, stamp() + " | " + Thread.currentThread().getName() + " | " + message, false);
    }

    /** Forensic channel: failed Telegram API requests, 429s, transient errors. */
    public static void logError(String message) {
        Log.e(TAG, message);
        write(errFile, stamp() + " | " + Thread.currentThread().getName() + " | " + message, false);
    }

    /** Bot tokens live in the URL path — never log them raw. */
    public static String maskUrl(String url) {
        if (url == null || appContext == null) return url;
        String token = SettingsManager.getBotToken(appContext);
        if (token == null || token.isEmpty()) return url;
        return url.replace("/bot" + token + "/", "/bot***:masked/");
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private static synchronized void write(File f, String line, boolean truncate) {
        if (f == null) return;
        try {
            if (!truncate) rotateIfNeeded(f);
            FileWriter fw = new FileWriter(f, !truncate);
            PrintWriter pw = new PrintWriter(fw);
            pw.println(line);
            pw.close();
        } catch (Exception e) {
            Log.e(TAG, "write failed", e);
        }
    }

    private static void rotateIfNeeded(File f) {
        if (f.exists() && f.length() > MAX_BYTES) {
            File old = new File(f.getAbsolutePath() + ".old");
            if (old.exists()) old.delete();
            if (f.renameTo(old)) Log.w(TAG, "Rotated " + f.getName() + " (>10MB)");
        }
    }
}