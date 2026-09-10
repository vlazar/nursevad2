package com.example.nursevad;

import java.util.UUID;

public class LogEvent {
    public enum Type { SPEECH, START, STOP, TELEGRAM_VOICE, INTRO, REMINDER, WARNING }
    public String id;
    public Type type;
    public long timestamp;
    public int level;
    public String uriString;
    public String displayName;
    public String recordedSpeechUri;
    public String senderName;
    public boolean isPoni = false;

    public LogEvent(Type type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public LogEvent(Type type, int level, AudioFile audioFile, String recordedSpeechUri) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.level = level;
        this.timestamp = System.currentTimeMillis();
        this.recordedSpeechUri = recordedSpeechUri;
        if (audioFile != null) {
            this.uriString = audioFile.uri;
            this.displayName = audioFile.displayName;
        } else {
            this.uriString = null;
            this.displayName = "No file found";
        }
    }

    public LogEvent(Type type, String filePath, String senderName) {
        this(type, filePath, senderName, "Voice Message");
    }

    // NEW: label-aware constructor ("Voice Message" or "Audio")
    public LogEvent(Type type, String filePath, String senderName, String mediaLabel) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.recordedSpeechUri = android.net.Uri.fromFile(new java.io.File(filePath)).toString();
        this.senderName = senderName;
        this.displayName = mediaLabel;
    }

    public LogEvent(Type type, AudioFile file) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.displayName = file.displayName;
        this.uriString = file.uri;
    }

    public LogEvent(Type type, String message) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.displayName = message;
    }
}