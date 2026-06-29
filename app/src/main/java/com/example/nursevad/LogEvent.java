package com.example.nursevad;

public class LogEvent {
    public enum Type { SPEECH, START, STOP }
    public Type type;
    public long timestamp;
    public int level;
    public String uriString; 
    public String displayName; 
    public String recordedSpeechUri; 

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
            // Fix: Replace "_" and "-" with spaces
            this.displayName = audioFile.displayName; 
        } else {
            this.uriString = null;
            this.displayName = "No file found";
        }
    }
}