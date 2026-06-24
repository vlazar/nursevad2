package com.example.nursevad;

import android.app.*;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class VadService extends Service {
    private static final String CHANNEL_ID = "VadServiceChannel";
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private PowerManager.WakeLock wakeLock;
    private SileroVad vad;
    private MediaPlayer mediaPlayer;

    private boolean isSpeaking = false;
    private long speechStartMs = 0;
    private double accumulatedDb = 0;
    private int frameCount = 0;
    private int silenceFrames = 0;

    private Map<Integer, List<String>> levelFiles = new HashMap<>();
    private Map<Integer, List<String>> queues = new HashMap<>();
    private Map<Integer, String> lastPlayed = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File logDir = getExternalFilesDir(null);
                if (logDir != null) {
                    File logFile = new File(logDir, "crash_log.txt");
                    PrintWriter writer = new PrintWriter(logFile);
                    writer.println("Time: " + new Date().toString() + " [SERVICE CRASH]");
                    e.printStackTrace(writer);
                    writer.close();
                }
            } catch (Exception ex) {}
            if (defaultHandler != null) defaultHandler.uncaughtException(t, e);
            else System.exit(1);
        });

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NurseVAD::Wakelock");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Nurse VAD")
                .setContentText("Initializing...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        
        try {
            startForeground(1, notification);
        } catch (Exception e) {
            EventBus.getInstance().postStatus("ERR: Foreground failed: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            if ("STOP".equals(intent.getAction())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if ("PLAY_SPECIFIC".equals(intent.getAction())) {
                String uri = intent.getStringExtra("URI");
                if (uri != null) playSpecificFile(uri);
                return START_STICKY;
            }
        }

        if (!isRunning) {
            isRunning = true;
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            
            if (vad == null) {
                try {
                    vad = new SileroVad(this);
                } catch (Throwable t) {
                    EventBus.getInstance().postStatus("ERR: VAD Init crashed: " + t.getMessage());
                }
            }
            
            loadAudioFiles();
            startRecording();
            EventBus.getInstance().postStatus("Listening..."); // Immediate status
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.START));
        }
        return START_STICKY;
    }

    private void startRecording() {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < 0) {
                EventBus.getInstance().postStatus("ERR: Device doesn't support 16kHz audio.");
                return;
            }
            
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                EventBus.getInstance().postStatus("ERR: AudioRecord failed. Grant MIC permission?");
                return;
            }
            
            audioRecord.startRecording();
            recordingThread = new Thread(() -> {
                short[] buffer = new short[512];
                while (isRunning) {
                    if (isPaused) {
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                        continue;
                    }
                    int read = audioRecord.read(buffer, 0, 512);
                    if (read == 512) {
                        processAudio(buffer);
                    }
                }
            });
            recordingThread.start();
        } catch (Exception e) {
            EventBus.getInstance().postStatus("ERR: startRecording crashed: " + e.getMessage());
        }
    }

    private void processAudio(short[] chunk) {
        double sum = 0;
        for (short s : chunk) sum += s * s;
        double rms = Math.sqrt(sum / chunk.length);
        double db = 20 * Math.log10(rms / 32768.0);
        int percent = (int) Math.round((db + 50.0) / 50.0 * 100.0);
        percent = Math.max(0, Math.min(100, percent));
        
        EventBus.getInstance().postVolume(percent);

        float prob = 0;
        if (vad != null) prob = vad.predict(chunk);
        
        // Real-time debug overlay
        EventBus.getInstance().postDebug("Vol: " + percent + "% | VAD Prob: " + String.format("%.3f", prob));

        if (prob > 0.5 && !isSpeaking) {
            isSpeaking = true;
            speechStartMs = SystemClock.elapsedRealtime();
            accumulatedDb = 0; frameCount = 0; silenceFrames = 0;
        }

        if (isSpeaking) {
            accumulatedDb += db;
            frameCount++;
            if (prob < 0.4) silenceFrames++;
            else silenceFrames = 0;

            if (silenceFrames > 15) { 
                isSpeaking = false;
                long durationMs = SystemClock.elapsedRealtime() - speechStartMs;
                int thresholdSec = SettingsManager.getDurationThreshold(this);
                
                if (durationMs >= thresholdSec * 1000) {
                    double avgDb = accumulatedDb / frameCount;
                    int avgPercent = (int) Math.round((avgDb + 50.0) / 50.0 * 100.0);
                    avgPercent = Math.max(0, Math.min(100, avgPercent));
                    int level = getLevel(avgPercent);
                    
                    String file = pickFile(level);
                    LogEvent event = new LogEvent(LogEvent.Type.SPEECH, level, file);
                    EventRepository.getInstance().addEvent(event);
                    
                    if (file != null) {
                        triggerPlay(file);
                    } else {
                        EventBus.getInstance().postStatus("Listening... (No files in Level " + level + ")");
                    }
                }
            }
        }
    }

    private int getLevel(int percent) {
        int[] thresholds = SettingsManager.getThresholds(this);
        if (percent < thresholds[0]) return 1;
        if (percent < thresholds[1]) return 2;
        if (percent < thresholds[2]) return 3;
        if (percent < thresholds[3]) return 4;
        return 5;
    }

    private void triggerPlay(String file) {
        if (file == null) return;

        isPaused = true;
        String fileName = Uri.parse(file).getLastPathSegment();
        EventBus.getInstance().postStatus("Playing: " + fileName);
        
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(file));
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                isPaused = false;
                EventBus.getInstance().postStatus("Listening...");
            });
            mediaPlayer.start();
        } catch (IOException e) {
            isPaused = false;
            EventBus.getInstance().postStatus("Listening... (Playback error)");
        }
    }

    public void playSpecificFile(String uriString) {
        isPaused = true;
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(uriString));
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> isPaused = false);
            mediaPlayer.start();
        } catch (IOException e) { isPaused = false; }
    }

    private String pickFile(int level) {
        List<String> available = levelFiles.get(level);
        if (available == null || available.isEmpty()) return null;

        List<String> queue = queues.get(level);
        if (queue == null || queue.isEmpty()) {
            queue = new ArrayList<>(available);
            Collections.shuffle(queue);
            String last = lastPlayed.get(level);
            if (last != null && queue.size() > 1 && queue.get(0).equals(last)) {
                Collections.swap(queue, 0, 1);
            }
            queues.put(level, queue);
        }
        String picked = queue.remove(0);
        lastPlayed.put(level, picked);
        return picked;
    }

    private void loadAudioFiles() {
        String uriStr = SettingsManager.getFolderUri(this);
        if (uriStr == null) return;
        DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(uriStr));
        if (root == null) return;

        for (int i = 1; i <= 5; i++) {
            DocumentFile levelDir = root.findFile("Level " + i);
            if (levelDir != null && levelDir.isDirectory()) {
                List<String> files = new ArrayList<>();
                for (DocumentFile f : levelDir.listFiles()) {
                    if (!f.getName().startsWith(".") && f.isFile()) {
                        files.add(f.getUri().toString());
                    }
                }
                levelFiles.put(i, files);
            }
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (audioRecord != null) audioRecord.stop();
        EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.STOP));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VAD Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}