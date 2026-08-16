package com.example.nursevad;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
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
    
    public static boolean isVadListening = false; 
    
    private boolean isPaused = false;
    private PowerManager.WakeLock wakeLock;
    private SileroVad vad;
    private MediaPlayer mediaPlayer;

    private boolean isSpeaking = false;
    private boolean speechEnded = false;
    private boolean isProcessingResponse = false;
    
    private long speechStartMs = 0;
    private double accumulatedRms = 0; 
    private int frameCount = 0;
    private int silenceFrames = 0;

    private FileOutputStream fos;
    private File recordedFile;

    private Map<Integer, List<AudioFile>> levelFiles = new HashMap<>();
    private Map<Integer, List<AudioFile>> queues = new HashMap<>();
    private Map<Integer, String> lastPlayed = new HashMap<>();

    private Handler handler = new Handler(Looper.getMainLooper());
    private Queue<String> telegramVoiceQueue = new LinkedList<>();

    private List<AudioFile> introFiles = new ArrayList<>();
    private Queue<AudioFile> introQueue = new LinkedList<>();
    private List<AudioFile> reminderFiles = new ArrayList<>();
    private List<AudioFile> reminderQueue = new ArrayList<>();
    private String lastPlayedReminderUri = null;
    
    private Runnable speechDelayRunnable;
    private Runnable reminderRunnable;
    private boolean isReminderArmed = false;

    public static void startService(Context context) {
        Intent i = new Intent(context, VadService.class);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stopService(Context context) {
        Intent i = new Intent(context, VadService.class);
        i.setAction("STOP");
        context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogger.init(this);
        DebugLogger.log("Service onCreate");
        
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
        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : "null";
        DebugLogger.log("onStartCommand called. isRunning=" + isRunning + ", action=" + action);

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
                DebugLogger.log("STOP action received. Stopping service and canceling timers.");
                if (reminderRunnable != null) handler.removeCallbacks(reminderRunnable);
                
                EventBus.getInstance().postVadRunning(false); 
                EventBus.getInstance().postVolume(0);
                EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
                stopSelf();
                return START_NOT_STICKY;
            }
            if ("PLAY_SPECIFIC".equals(intent.getAction())) {
                String uri = intent.getStringExtra("URI");
                if (uri != null) playSpecificFile(uri);
                return START_STICKY;
            }
            if ("PLAY_TELEGRAM_VOICE".equals(intent.getAction())) {
                String path = intent.getStringExtra("PATH");
                String sender = intent.getStringExtra("SENDER");
                if (path != null) {
                    LogEvent event = new LogEvent(LogEvent.Type.TELEGRAM_VOICE, path, sender != null ? sender : "Telegram Bot");
                    EventRepository.getInstance().addEvent(event);
                    
                    telegramVoiceQueue.add(path);
                    if (!isProcessingResponse && (mediaPlayer == null || !mediaPlayer.isPlaying())) {
                        playNextTelegramVoice();
                    }
                }
                return START_STICKY;
            }
            if ("STOP_PLAYBACK".equals(intent.getAction())) {
                if (mediaPlayer != null) {
                    try { mediaPlayer.stop(); } catch (Exception e) {}
                    mediaPlayer.release();
                    mediaPlayer = null;
                }
                telegramVoiceQueue.clear(); 
                onPlaybackComplete();
                return START_STICKY;
            }
        }

        if (!isRunning) {
            DebugLogger.log("Initializing VAD loop and adding START event.");
            isRunning = true;
            isVadListening = true;
            EventBus.getInstance().postVadRunning(true); 
            
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            
            if (vad == null) {
                try { vad = new SileroVad(this); } catch (Throwable t) {}
            }
            
            loadAudioFiles();
            
            // Load Speaker Embeddings if configured
            String embUri = SettingsManager.getEmbeddingsFolderUri(this);
            if (embUri != null) {
                SpeakerVerifier.getInstance(this).loadEmbeddings(embUri);
            }
            
            startRecording();
            
            EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.START));

            isReminderArmed = false;
            introQueue.clear();
            if (introFiles != null && !introFiles.isEmpty()) {
                introQueue.addAll(introFiles);
                isProcessingResponse = true;
                playNextIntro();
            } else {
                isProcessingResponse = false;
                EventBus.getInstance().postStatus("Listening...");
            }
            
            int reminderTrigger = SettingsManager.getReminderTrigger(this);
            if (reminderTrigger == 0 || reminderTrigger == 1) {
                scheduleReminder();
            }
        } else {
            DebugLogger.log("Service already running. Ignoring start command.");
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    // --- Helper Methods for Reminder Timer Pause/Resume ---
    private void pauseReminderTimer() {
        if (SettingsManager.getReminderTrigger(this) == 1 && reminderRunnable != null) {
            handler.removeCallbacks(reminderRunnable);
            DebugLogger.log("Reminder timer PAUSED (Trigger=1).");
        }
    }

    private void resumeReminderTimer() {
        if (SettingsManager.getReminderTrigger(this) == 1 && isRunning) {
            scheduleReminder();
            DebugLogger.log("Reminder timer RESUMED (Trigger=1).");
        }
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
                int ignoreFrames = 0; 
                
                while (isRunning) {
                    int read = audioRecord.read(buffer, 0, 512);
                    if (read == 512) {
                        if (isPaused) {
                            ignoreFrames = 10; 
                            continue; 
                        }
                        
                        if (ignoreFrames > 0) {
                            ignoreFrames--;
                            continue; 
                        }
                        
                        processAudio(buffer);
                    }
                }
            });
            recordingThread.start();
        } catch (Exception e) {
            DebugLogger.log("startRecording exception: " + e.getMessage());
        }
    }

    private void processAudio(short[] chunk) {
        double sum = 0;
        for (short s : chunk) sum += s * s;
        double meanSquare = sum / chunk.length;
        double rms = Math.sqrt(meanSquare);
        
        double safeRms = Math.max(1.0, rms);
        double db = 20 * Math.log10(safeRms / 32768.0);
        
        double normalizedDb = (db + 55.0) / 50.0;
        normalizedDb = Math.max(0.0, Math.min(1.0, normalizedDb));
        int percent = (int) Math.round(normalizedDb * 100.0);
        percent = Math.max(0, Math.min(100, percent));
        
        EventBus.getInstance().postVolume(percent);

        float prob = 0;
        if (vad != null) prob = vad.predict(chunk);
        
        String probStr = String.format("%.3f", prob).replace('.', ',');
        EventBus.getInstance().postDebug("Vol: " + percent + "% | VAD Prob: " + probStr);

        if (prob > 0.5 && !isSpeaking && !isProcessingResponse) {
            DebugLogger.log("Speech START detected. prob=" + prob);
            isSpeaking = true;
            speechEnded = false;
            speechStartMs = SystemClock.elapsedRealtime();
            accumulatedRms = 0; frameCount = 0; silenceFrames = 0;
            
            pauseReminderTimer();
            
            try {
                recordedFile = new File(getCacheDir(), "speech_" + speechStartMs + ".wav");
                fos = new FileOutputStream(recordedFile);
                writeWavHeader(fos, 16000, 1, 16);
            } catch (Exception e) {}

            boolean waitForEnd = SettingsManager.getWaitForEnd(this);
            int delaySec = SettingsManager.getDelay(this);
            
            if (!waitForEnd) {
                isProcessingResponse = true;
                speechDelayRunnable = () -> triggerResponse();
                if (delaySec > 0) {
                    handler.postDelayed(speechDelayRunnable, delaySec * 1000L);
                } else {
                    handler.post(speechDelayRunnable);
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

            accumulatedRms += rms;
            frameCount++;
            if (prob < 0.4) silenceFrames++;
            else silenceFrames = 0;

            if (silenceFrames > 15 && !speechEnded) {
                DebugLogger.log("Speech END detected. silenceFrames=" + silenceFrames);
                speechEnded = true;
                isSpeaking = false;
                
                if (fos != null) {
                    try {
                        fos.close();
                        updateWavHeader(recordedFile);
                    } catch (IOException e) {}
                    fos = null;
                }
                
                boolean waitForEnd = SettingsManager.getWaitForEnd(this);
                int delaySec = SettingsManager.getDelay(this);
                
                if (waitForEnd) {
                    isProcessingResponse = true;
                    speechDelayRunnable = () -> triggerResponse();
                    if (delaySec > 0) {
                        handler.postDelayed(speechDelayRunnable, delaySec * 1000L);
                    } else {
                        handler.post(speechDelayRunnable);
                    }
                }
            }
        }
    }

    private void triggerResponse() {
        DebugLogger.log("triggerResponse called. isSpeaking=" + isSpeaking + ", isReminderArmed=" + isReminderArmed);
        if (speechDelayRunnable != null) {
            handler.removeCallbacks(speechDelayRunnable);
            speechDelayRunnable = null;
        }
        
        long durationMs = SystemClock.elapsedRealtime() - speechStartMs;
        int thresholdSec = SettingsManager.getDurationThreshold(this);
        
        if (durationMs < thresholdSec * 1000) {
            if (isSpeaking) {
                speechDelayRunnable = () -> triggerResponse();
                handler.postDelayed(speechDelayRunnable, 100);
                return;
            } else {
                isProcessingResponse = false;
                isPaused = false;
                if (!telegramVoiceQueue.isEmpty()) {
                    playNextTelegramVoice();
                } else {
                    EventBus.getInstance().postStatus("Listening...");
                    resumeReminderTimer();
                }
                return;
            }
        }
        
        double avgRms = accumulatedRms / frameCount;
        double safeAvgRms = Math.max(1.0, avgRms);
        double avgDb = 20 * Math.log10(safeAvgRms / 32768.0);
        
        double normalizedAvgDb = (avgDb + 55.0) / 50.0;
        normalizedAvgDb = Math.max(0.0, Math.min(1.0, normalizedAvgDb));
        int avgPercent = (int) Math.round(normalizedAvgDb * 100.0);
        avgPercent = Math.max(0, Math.min(100, avgPercent));
        
        int level = getLevel(avgPercent);
        
        AudioFile file = pickFile(level);
        String recordedUri = (recordedFile != null) ? Uri.fromFile(recordedFile).toString() : null;
        
        final AudioFile finalFile = file;
        final String finalRecordedUri = recordedUri;
        final int finalLevel = level;

        // Run Speaker Verification and Event Finalization on a background thread
        Runnable finalizeEvent = () -> {
            boolean isPoi = true;
            String embUri = SettingsManager.getEmbeddingsFolderUri(this);
            if (embUri != null && recordedFile != null && recordedFile.exists()) {
                isPoi = SpeakerVerifier.getInstance(this).verify(recordedFile);
            }
            
            LogEvent event = new LogEvent(LogEvent.Type.SPEECH, finalLevel, finalFile, finalRecordedUri);
            event.isPoni = !isPoi;
            if (!isPoi) event.displayName = "PONI is talking";
            
            EventRepository.getInstance().addEvent(event);
            
            if (recordedFile != null && recordedFile.exists()) {
                String responseName = isPoi ? (finalFile != null ? finalFile.displayName : null) : "PONI is talking";
                TelegramManager.getInstance().sendAudioEvent(Uri.fromFile(recordedFile).toString(), finalLevel, responseName, !isPoi);
            }
            
            if (isPoi) {
                boolean playedSomething = false;
                if (SettingsManager.getReminderTrigger(this) == 0) {
                    if (isReminderArmed) {
                        playReminder();
                        isReminderArmed = false;
                        scheduleReminder(); 
                        playedSomething = true;
                    }
                } else if (SettingsManager.getReminderTrigger(this) == 1) {
                    resumeReminderTimer();
                }
                
                if (!playedSomething && finalFile != null) {
                    if (SettingsManager.isSilentMode(this)) {
                        EventBus.getInstance().postStatus("Listening... (Silent)");
                    } else {
                        triggerPlay(finalFile);
                        playedSomething = true;
                    }
                }
                
                if (!playedSomething) {
                    isPaused = false;
                    isProcessingResponse = false;
                    if (!telegramVoiceQueue.isEmpty()) {
                        playNextTelegramVoice();
                    } else {
                        if (finalFile == null) {
                            EventBus.getInstance().postStatus("Listening... (No files in Level " + finalLevel + ")");
                        } else {
                            EventBus.getInstance().postStatus("Listening...");
                        }
                        resumeReminderTimer();
                    }
                }
            } else {
                // PONI logic: Do not play response, just resume listening
                isPaused = false;
                isProcessingResponse = false;
                if (!telegramVoiceQueue.isEmpty()) {
                    playNextTelegramVoice();
                } else {
                    EventBus.getInstance().postStatus("Listening...");
                    resumeReminderTimer();
                }
            }
        };

        new Thread(finalizeEvent).start();
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
        DebugLogger.log("triggerPlay: " + file.displayName);
        isPaused = true;
        pauseReminderTimer(); 
        
        EventBus.getInstance().postVolume(0);
        EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
        
        EventBus.getInstance().postStatus("Playing: " + file.displayName);
        EventBus.getInstance().postPlayingUri(file.uri);
        
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(file.uri));
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> onPlaybackComplete());
            mediaPlayer.start();
        } catch (IOException e) {
            onPlaybackComplete();
        }
    }

    public void playSpecificFile(String uriString) {
        isPaused = true;
        pauseReminderTimer(); 
        
        EventBus.getInstance().postVolume(0);
        EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
        
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
            mediaPlayer.setOnCompletionListener(mp -> onPlaybackComplete());
            mediaPlayer.start();
            EventBus.getInstance().postStatus("Playing recorded speech...");
        } catch (Exception e) { 
            onPlaybackComplete();
        }
    }

    private void onPlaybackComplete() {
        DebugLogger.log("onPlaybackComplete. Queue empty=" + telegramVoiceQueue.isEmpty());
        if (!telegramVoiceQueue.isEmpty()) {
            playNextTelegramVoice();
        } else {
            isPaused = false;
            isProcessingResponse = false;
            EventBus.getInstance().postPlayingUri(null);
            if (isRunning) EventBus.getInstance().postStatus("Listening...");
            else EventBus.getInstance().postStatus("Idle");
            
            resumeReminderTimer();
        }
    }

    private void playNextTelegramVoice() {
        String path = telegramVoiceQueue.poll();
        if (path == null) {
            onPlaybackComplete();
            return;
        }
        
        isPaused = true;
        isProcessingResponse = true; 
        pauseReminderTimer(); 
        
        EventBus.getInstance().postVolume(0);
        EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
        EventBus.getInstance().postStatus("Playing Telegram Voice...");
        
        EventBus.getInstance().postPlayingUri(Uri.fromFile(new File(path)).toString());
        
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> onPlaybackComplete());
            mediaPlayer.start();
        } catch (Exception e) {
            onPlaybackComplete();
        }
    }

    private void playNextIntro() {
        AudioFile file = introQueue.poll();
        DebugLogger.log("playNextIntro: " + (file != null ? file.displayName : "null"));
        if (file == null) {
            isProcessingResponse = false;
            isPaused = false; 
            if (isRunning) EventBus.getInstance().postStatus("Listening...");
            resumeReminderTimer();
            return;
        }
        
        EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.INTRO, file));
        TelegramManager.getInstance().sendTextMessage("🔵 Intro " + file.displayName);
        
        isPaused = true;
        isProcessingResponse = true;
        pauseReminderTimer(); 
        
        EventBus.getInstance().postVolume(0);
        EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
        EventBus.getInstance().postStatus("Playing Intro...");
        
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(file.uri));
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> playNextIntro());
            mediaPlayer.start();
        } catch (Exception e) {
            playNextIntro();
        }
    }

    private void scheduleReminder() {
        if (reminderFiles == null || reminderFiles.isEmpty()) return;
        
        if (reminderRunnable != null) {
            handler.removeCallbacks(reminderRunnable);
        }
        
        int trigger = SettingsManager.getReminderTrigger(this);
        long delayMs;
        if (trigger == 0) {
            int min = SettingsManager.getReminderStartMin(this);
            int max = SettingsManager.getReminderStartMax(this);
            if (min > max) { int t = min; min = max; max = t; }
            int steps = (max - min) / 5;
            int randomSteps = steps > 0 ? new Random().nextInt(steps + 1) : 0;
            int randomMins = min + (randomSteps * 5);
            delayMs = randomMins * 60000L;
            
            reminderRunnable = () -> {
                DebugLogger.log("Reminder Timer FIRED (Trigger=Start). Arming reminder.");
                isReminderArmed = true;
            };
        } else {
            int min = SettingsManager.getReminderSpeechMin(this);
            int max = SettingsManager.getReminderSpeechMax(this);
            if (min > max) { int t = min; min = max; max = t; }
            int steps = (max - min) / 5;
            int randomSteps = steps > 0 ? new Random().nextInt(steps + 1) : 0;
            int randomSecs = min + (randomSteps * 5);
            delayMs = randomSecs * 1000L;
            
            reminderRunnable = () -> {
                DebugLogger.log("Reminder Timer FIRED (Trigger=Speech/Recurring). Playing reminder.");
                playReminder();
            };
        }
        DebugLogger.log("scheduleReminder called. Delay=" + delayMs + "ms");
        handler.postDelayed(reminderRunnable, delayMs);
    }

    private void playReminder() {
        if (reminderFiles == null || reminderFiles.isEmpty()) return;
        if (reminderQueue == null || reminderQueue.isEmpty()) {
            reminderQueue = new ArrayList<>(reminderFiles);
            Collections.shuffle(reminderQueue);
            if (lastPlayedReminderUri != null && reminderQueue.size() > 1 && reminderQueue.get(0).uri.equals(lastPlayedReminderUri)) {
                Collections.swap(reminderQueue, 0, 1);
            }
        }
        
        AudioFile file = reminderQueue.remove(0);
        lastPlayedReminderUri = file.uri;
        DebugLogger.log("playReminder: " + file.displayName);
        
        EventRepository.getInstance().addEvent(new LogEvent(LogEvent.Type.REMINDER, file));
        TelegramManager.getInstance().sendTextMessage("🔵 Reminder " + file.displayName);
        
        isPaused = true;
        isProcessingResponse = true;
        pauseReminderTimer(); 
        
        EventBus.getInstance().postVolume(0);
        EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
        EventBus.getInstance().postStatus("Playing Reminder...");
        
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(file.uri));
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> onPlaybackComplete());
            mediaPlayer.start();
        } catch (Exception e) {
            DebugLogger.log("playReminder exception: " + e.getMessage());
            onPlaybackComplete();
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
        
        introFiles.clear();
        DocumentFile introDir = root.findFile("Intro");
        if (introDir == null || !introDir.isDirectory()) {
            for (DocumentFile f : rootFiles) {
                if (f.isDirectory() && f.getName() != null && f.getName().equalsIgnoreCase("Intro")) {
                    introDir = f; break;
                }
            }
        }
        if (introDir != null && introDir.isDirectory()) {
            DocumentFile[] files = introDir.listFiles();
            if (files != null) {
                for (DocumentFile f : files) {
                    if (f != null && f.getName() != null && !f.getName().startsWith(".") && f.isFile()) {
                        String name = f.getName();
                        String cleanName = name.replaceAll("\\.[^.]+$", "").replace("_", " ").replace("-", " ").replaceAll("\\s+", " ").trim();
                        introFiles.add(new AudioFile(f.getUri().toString(), cleanName));
                    }
                }
            }
        }
        debugMsg.append("Intro:").append(introFiles.size()).append(" ");

        reminderFiles.clear();
        DocumentFile remDir = root.findFile("Reminder");
        if (remDir == null || !remDir.isDirectory()) {
            for (DocumentFile f : rootFiles) {
                if (f.isDirectory() && f.getName() != null && f.getName().equalsIgnoreCase("Reminder")) {
                    remDir = f; break;
                }
            }
        }
        if (remDir != null && remDir.isDirectory()) {
            DocumentFile[] files = remDir.listFiles();
            if (files != null) {
                for (DocumentFile f : files) {
                    if (f != null && f.getName() != null && !f.getName().startsWith(".") && f.isFile()) {
                        String name = f.getName();
                        String cleanName = name.replaceAll("\\.[^.]+$", "").replace("_", " ").replace("-", " ").replaceAll("\\s+", " ").trim();
                        reminderFiles.add(new AudioFile(f.getUri().toString(), cleanName));
                    }
                }
            }
        }
        debugMsg.append("Rem:").append(reminderFiles.size());

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
        DebugLogger.log("Service onDestroy");
        isRunning = false;
        isVadListening = false;
        EventBus.getInstance().postVadRunning(false); 
        
        handler.removeCallbacksAndMessages(null);
        if (fos != null) { try { fos.close(); } catch (IOException e) {} }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (audioRecord != null) audioRecord.stop();
        
        EventBus.getInstance().postVolume(0);
        EventBus.getInstance().postDebug("Vol: 0% | VAD Prob: 0,000");
        
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