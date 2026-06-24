package com.example.nursevad;

import android.net.Uri;

public class LogEvent {
    public enum Type { SPEECH, START, STOP }
    public Type type;
    public long timestamp;
    public int level;
    public String rawFileName;
    public String displayName;
    public String uriString;

    public LogEvent(Type type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public LogEvent(Type type, int level, String uriString) {
        this.type = type;
        this.level = level;
        this.timestamp = System.currentTimeMillis();
        this.uriString = uriString;
        this.rawFileName = Uri.parse(uriString).getLastPathSegment();
        this.displayName = rawFileName.replaceAll("\\.[^.]+$", "").replace("_", "").replace("-", "");
    }
}