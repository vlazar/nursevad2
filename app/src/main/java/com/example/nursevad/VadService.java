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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
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

    private FileOutputStream fos;
    private File recordedFile;

    private Map<Integer, List<AudioFile>> levelFiles = new HashMap<>();
    private Map<Integer, List<AudioFile>> queues = new HashMap<>();
    private Map<Integer, String> lastPlayed = new HashMap<>();

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable delayedResponseRunnable = new Runnable() {
        @Override
        public void run() {
            if (isSpeaking) finalizeSpeechEvent();
        }
    };

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
            if ("STOP_PLAYBACK".equals(intent.getAction())) {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                    mediaPlayer = null;
                    isPaused = false;
                    EventBus.getInstance().postPlayingUri(null);
                    if (isRunning) EventBus.getInstance().postStatus("Listening...");
                }
                return START_STICKY;
            }
        }

        if (!isRunning) {
            isRunning = true;
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            
            if (vad == null) {
                try { vad = new SileroVad(this); } catch (Throwable t) {}
            }
            
            loadAudioFiles();
            startRecording();
            EventBus.getInstance().postStatus("Listening...");
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.START));
        }
        return START_STICKY;
    }

    private void startRecording() {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < 0) return;
            
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) return;
            
            audioRecord.startRecording();
            recordingThread = new Thread(() -> {
                short[] buffer = new short[512];
                while (isRunning) {
                    if (isPaused) {
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                        continue;
                    }
                    int read = audioRecord.read(buffer, 0, 512);
                    if (read == 512) processAudio(buffer);
                }
            });
            recordingThread.start();
        } catch (Exception e) {}
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
        
        EventBus.getInstance().postDebug("Vol: " + percent + "% | VAD Prob: " + String.format("%.3f", prob));

        if (prob > 0.5 && !isSpeaking) {
            isSpeaking = true;
            speechStartMs = SystemClock.elapsedRealtime();
            accumulatedDb = 0; frameCount = 0; silenceFrames = 0;
            
            try {
                recordedFile = new File(getCacheDir(), "speech_" + speechStartMs + ".wav");
                fos = new FileOutputStream(recordedFile);
                writeWavHeader(fos, 16000, 1, 16);
            } catch (Exception e) {}

            boolean waitForEnd = SettingsManager.getWaitForEnd(this);
            int delaySec = SettingsManager.getDelay(this);
            
            if (!waitForEnd) {
                if (delaySec > 0) {
                    handler.postDelayed(delayedResponseRunnable, delaySec * 1000L);
                } else {
                    handler.post(delayedResponseRunnable);
                }
            }
        }

        if (isSpeaking) {
            if (fos != null) {
                byte[] byteBuffer = new byte[chunk.length * 2];
                for (int i = 0; i < chunk.length; i++) {
                    byteBuffer[i * 2] = (byte) (chunk[i] & 0xff);
                    byteBuffer[i * 2 + 1] = (byte) ((chunk[i] >> 8) & 0xff);
                }
                try { fos.write(byteBuffer); } catch (IOException e) {}
            }

            accumulatedDb += db;
            frameCount++;
            if (prob < 0.4) silenceFrames++;
            else silenceFrames = 0;

            if (silenceFrames > 15) {
                handler.removeCallbacks(delayedResponseRunnable);
                finalizeSpeechEvent();
            }
        }
    }

    private void finalizeSpeechEvent() {
        isSpeaking = false;
        silenceFrames = 0;
        handler.removeCallbacks(delayedResponseRunnable);
        
        if (fos != null) {
            try {
                fos.close();
                updateWavHeader(recordedFile);
            } catch (IOException e) {}
            fos = null;
        }
        
        long durationMs = SystemClock.elapsedRealtime() - speechStartMs;
        int thresholdSec = SettingsManager.getDurationThreshold(this);
        
        if (durationMs >= thresholdSec * 1000) {
            double avgDb = accumulatedDb / frameCount;
            int avgPercent = (int) Math.round((avgDb + 50.0) / 50.0 * 100.0);
            avgPercent = Math.max(0, Math.min(100, avgPercent));
            int level = getLevel(avgPercent);
            
            AudioFile file = pickFile(level);
            String recordedUri = (recordedFile != null) ? Uri.fromFile(recordedFile).toString() : null;
            
            LogEvent event = new LogEvent(LogEvent.Type.SPEECH, level, file, recordedUri);
            EventRepository.getInstance().addEvent(event);
            
            if (file != null) {
                triggerPlay(file);
            } else {
                EventBus.getInstance().postStatus("Listening... (No files in Level " + level + ")");
            }
        } else {
            EventBus.getInstance().postStatus("Listening...");
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

    private void triggerPlay(AudioFile file) {
        if (file == null) return;
        isPaused = true;
        EventBus.getInstance().postStatus("Playing: " + file.displayName);
        EventBus.getInstance().postPlayingUri(file.uri);
        
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(file.uri));
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                isPaused = false;
                EventBus.getInstance().postPlayingUri(null);
                EventBus.getInstance().postStatus("Listening...");
            });
            mediaPlayer.start();
        } catch (IOException e) {
            isPaused = false;
            EventBus.getInstance().postPlayingUri(null);
            EventBus.getInstance().postStatus("Listening... (Playback error)");
        }
    }

    public void playSpecificFile(String uriString) {
        isPaused = true;
        EventBus.getInstance().postPlayingUri(uriString);
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            
            Uri uri = Uri.parse(uriString);
            if ("file".equals(uri.getScheme())) {
                mediaPlayer.setDataSource(uri.getPath());
            } else {
                mediaPlayer.setDataSource(this, uri);
            }
            
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                isPaused = false;
                EventBus.getInstance().postPlayingUri(null);
                if (isRunning) EventBus.getInstance().postStatus("Listening...");
            });
            mediaPlayer.start();
            EventBus.getInstance().postStatus("Playing recorded speech");
        } catch (Exception e) { 
            isPaused = false; 
            EventBus.getInstance().postPlayingUri(null);
        }
    }

    private AudioFile pickFile(int level) {
        List<AudioFile> available = levelFiles.get(level);
        if (available == null || available.isEmpty()) return null;

        List<AudioFile> queue = queues.get(level);
        if (queue == null || queue.isEmpty()) {
            queue = new ArrayList<>(available);
            Collections.shuffle(queue);
            String last = lastPlayed.get(level);
            if (last != null && queue.size() > 1 && queue.get(0).uri.equals(last)) {
                Collections.swap(queue, 0, 1);
            }
            queues.put(level, queue);
        }
        AudioFile picked = queue.remove(0);
        lastPlayed.put(level, picked.uri);
        return picked;
    }

    private void loadAudioFiles() {
        String uriStr = SettingsManager.getFolderUri(this);
        if (uriStr == null) return;
        DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(uriStr));
        if (root == null) return;

        StringBuilder debugMsg = new StringBuilder();
        DocumentFile[] rootFiles = root.listFiles();
        if (rootFiles == null) rootFiles = new DocumentFile[0];

        for (int i = 1; i <= 5; i++) {
            List<AudioFile> levelFilesList = new ArrayList<>();
            DocumentFile levelDir = null;

            levelDir = root.findFile("Level " + i);
            if (levelDir == null || !levelDir.isDirectory()) {
                for (DocumentFile f : rootFiles) {
                    if (f.isDirectory() && f.getName() != null && f.getName().contains(String.valueOf(i))) {
                        levelDir = f; break;
                    }
                }
            }
            if ((levelDir == null || !levelDir.isDirectory()) && root.getName() != null && root.getName().contains(String.valueOf(i))) {
                levelDir = root;
            }

            if (levelDir != null && levelDir.isDirectory()) {
                DocumentFile[] audioFiles = levelDir.listFiles();
                if (audioFiles != null) {
                    for (DocumentFile f : audioFiles) {
                        if (f != null && f.getName() != null && !f.getName().startsWith(".") && f.isFile()) {
                            // Fix: replace "_" and "-" with spaces
                            String name = f.getName(); 
                            String cleanName = name.replaceAll("\\.[^.]+$", "").replace("_", " ").replace("-", " ").replaceAll("\\s+", " ").trim();
                            levelFilesList.add(new AudioFile(f.getUri().toString(), cleanName));
                        }
                    }
                }
            }
            levelFiles.put(i, levelFilesList);
            debugMsg.append("L").append(i).append(":").append(levelFilesList.size()).append(" ");
        }
        EventBus.getInstance().postDebug("Files found -> " + debugMsg.toString());
    }

    private void writeWavHeader(FileOutputStream out, int sampleRate, int channels, int bitsPerSample) throws IOException {
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; 
        header[20] = 1; header[21] = 0; 
        header[22] = (byte) channels; header[23] = 0; 
        header[24] = (byte) (sampleRate & 0xff); header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff); header[27] = (byte) ((sampleRate >> 24) & 0xff);
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        header[28] = (byte) (byteRate & 0xff); header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff); header[31] = (byte) ((byteRate >> 24) & 0xff);
        int blockAlign = channels * bitsPerSample / 8;
        header[32] = (byte) (blockAlign & 0xff); header[33] = (byte) ((blockAlign >> 8) & 0xff);
        header[34] = (byte) (bitsPerSample & 0xff); header[35] = (byte) ((bitsPerSample >> 8) & 0xff);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        out.write(header, 0, 44);
    }

    private void updateWavHeader(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        long length = file.length();
        long chunkSize = length - 8;
        long dataSize = length - 44;
        raf.seek(4);
        raf.write((int) (chunkSize & 0xff)); raf.write((int) ((chunkSize >> 8) & 0xff));
        raf.write((int) ((chunkSize >> 16) & 0xff)); raf.write((int) ((chunkSize >> 24) & 0xff));
        raf.seek(40);
        raf.write((int) (dataSize & 0xff)); raf.write((int) ((dataSize >> 8) & 0xff));
        raf.write((int) ((dataSize >> 16) & 0xff)); raf.write((int) ((dataSize >> 24) & 0xff));
        raf.close();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacks(delayedResponseRunnable);
        if (fos != null) { try { fos.close(); } catch (IOException e) {} }
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