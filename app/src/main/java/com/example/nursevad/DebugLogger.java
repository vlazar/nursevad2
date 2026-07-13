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
    private static File logFile;
    private static final String TAG = "NurseVAD_Debug";

    public static void init(Context context) {
        try {
            File logDir = context.getExternalFilesDir(null);
            if (logDir != null) {
                logFile = new File(logDir, "log.txt");
                PrintWriter writer = new PrintWriter(logFile);
                writer.println("=== Log started at " + new Date().toString() + " ===");
                writer.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init logger", e);
        }
    }

    public static void log(String message) {
        Log.d(TAG, message);
        if (logFile == null) return;
        try {
            FileWriter fw = new FileWriter(logFile, true);
            PrintWriter pw = new PrintWriter(fw);
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
            pw.println(sdf.format(new Date()) + " | " + Thread.currentThread().getName() + " | " + message);
            pw.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write log", e);
        }
    }
}