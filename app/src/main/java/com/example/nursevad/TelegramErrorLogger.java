package com.example.nursevad;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Writes failed Telegram API requests (HTTP >= 400: 404, 429, 5xx ...) to log_errors.txt. */
public class TelegramErrorLogger {
    private static File logFile;

    public static void init(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        logFile = new File(dir, "log_errors.txt");
    }

    public static synchronized void log(String message) {
        if (logFile == null) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
            pw.println(new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date())
                    + " | " + message);
        } catch (Exception e) {
            android.util.Log.e("TgErrorLogger", "write failed", e);
        }
    }

    /** OkHttp application interceptor: logs URL + request/response headers + body for every HTTP >= 400. */
    public static Interceptor interceptor() {
        return chain -> {
            Request req = chain.request();
            Response res;
            try {
                res = chain.proceed(req);
            } catch (java.io.IOException e) {
                // Connectivity failures on the long-poll request are expected offline; don't spam.
                if (!req.url().encodedPath().endsWith("getUpdates")) {
                    log("IOERROR " + req.method() + " " + req.url() + " : " + e);
                }
                throw e;
            }
            if (res.code() >= 400) {
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP ").append(res.code()).append(' ').append(req.method()).append(' ').append(req.url()).append('\n');
                sb.append("--- request headers ---\n").append(req.headers());
                String body = "";
                try { body = res.peekBody(32768).string(); } catch (Exception ignored) {}
                sb.append("--- response headers ---\n").append(res.headers());
                sb.append("--- response body ---\n").append(body);
                log(sb.toString());
            }
            return res;
        };
    }
}