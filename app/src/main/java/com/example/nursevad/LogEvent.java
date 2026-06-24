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
        this.uriString = (uriString != null && !uriString.isEmpty()) ? uriString : null;
        
        if (this.uriString != null) {
            this.rawFileName = Uri.parse(this.uriString).getLastPathSegment();
            // Remove extension, underscores, and hyphens as requested
            this.displayName = rawFileName.replaceAll("\\.[^.]+$", "")
                                          .replace("_", "")
                                          .replace("-", "");
        } else {
            this.rawFileName = "";
            this.displayName = "No audio file configured";
        }
    }
}