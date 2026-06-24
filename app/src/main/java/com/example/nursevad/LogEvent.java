package com.example.nursevad;

public class LogEvent {
    public enum Type { SPEECH, START, STOP }
    public Type type;
    public long timestamp;
    public int level;
    public String uriString; // Response audio URI
    public String displayName; // Cleaned response audio name
    public String recordedSpeechUri; // URI to the recorded user speech

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
}