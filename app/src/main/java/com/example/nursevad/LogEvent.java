package com.example.nursevad;

public class LogEvent {
    public enum Type { SPEECH, START, STOP, TELEGRAM_VOICE, INTRO, REMINDER }
    public Type type;
    public long timestamp;
    public int level;
    public String uriString; 
    public String displayName; 
    public String recordedSpeechUri; 
    public String senderName; 

    public LogEvent(Type type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public LogEvent(Type type, int level, AudioFile audioFile, String recordedSpeechUri) {
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
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.recordedSpeechUri = android.net.Uri.fromFile(new java.io.File(filePath)).toString();
        this.senderName = senderName;
        this.displayName = "Voice Message";
    }

    // Constructor for Intro and Reminder
    public LogEvent(Type type, AudioFile file) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.displayName = file.displayName;
        this.uriString = file.uri;
    }
}