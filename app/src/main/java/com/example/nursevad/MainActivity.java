package com.example.nursevad;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private boolean isListening = false;
    private LogAdapter adapter;
    private TextView statusText;
    private TextView tvDebug;
    private ProgressBar volumeMeter;
    private RecyclerView recyclerView;
    private Button btnStartStop;
    private SwitchCompat switchSilent;

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private boolean isUpdatingSilentSwitch = false;

    private static final String COLOR_START_GREEN = "#2E7D32";
    private static final String COLOR_STOP_RED = "#C62828";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            isListening = savedInstanceState.getBoolean("isListening", false);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        startTelegramServiceIfConfigured();

        statusText = findViewById(R.id.statusText);
        tvDebug = findViewById(R.id.tvDebug);
        volumeMeter = findViewById(R.id.volumeMeter);
        btnStartStop = findViewById(R.id.btnStartStop);
        ImageButton btnClear = findViewById(R.id.btnClear);
        recyclerView = findViewById(R.id.recyclerView);

        switchSilent = findViewById(R.id.switchSilent);
        switchSilent.setChecked(SettingsManager.isSilentMode(this));
        switchSilent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isUpdatingSilentSwitch) {
                SettingsManager.saveSilentMode(this, isChecked);
            }
        });

        // Listen for external silent mode changes (e.g., from Telegram bot)
        prefs = getSharedPreferences("NurseVadPrefs", Context.MODE_PRIVATE);
        prefListener = (sharedPreferences, key) -> {
            if ("silent_mode".equals(key) && !isUpdatingSilentSwitch) {
                runOnUiThread(() -> {
                    isUpdatingSilentSwitch = true;
                    switchSilent.setChecked(SettingsManager.isSilentMode(this));
                    isUpdatingSilentSwitch = false;
                });
            }
        };

        // Set initial button state
        updateStartStopButton(isListening);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new LogAdapter(new LogAdapter.OnPlayClickListener() {
            @Override
            public void onPlayClick(String uriString) {
                Intent i = new Intent(MainActivity.this, VadService.class);
                i.setAction("PLAY_SPECIFIC");
                i.putExtra("URI", uriString);
                startService(i);
            }

            @Override
            public void onStopClick() {
                Intent i = new Intent(MainActivity.this, VadService.class);
                i.setAction("STOP_PLAYBACK");
                startService(i);
            }
        });
        recyclerView.setAdapter(adapter);

        EventRepository.getInstance().getLiveEvents().observe(this, events -> {
            adapter.submitList(events);
            recyclerView.scrollToPosition(0);
        });

        btnStartStop.setOnClickListener(v -> {
            Intent i = new Intent(this, VadService.class);
            if (!isListening) {
                ContextCompat.startForegroundService(this, i);
                isListening = true;
            } else {
                i.setAction("STOP");
                startService(i);
                isListening = false;
                statusText.setText("Idle");
            }
            updateStartStopButton(isListening);
        });

        btnClear.setOnClickListener(v -> EventRepository.getInstance().clearEvents());

        EventBus.getInstance().getStatus().observe(this, status -> {
            statusText.setText(status);
            if (status != null && status.startsWith("ERR:")) {
                statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                statusText.setTextColor(getResources().getColor(android.R.color.black));
            }
        });

        EventBus.getInstance().getVolume().observe(this, vol -> volumeMeter.setProgress(vol));
        EventBus.getInstance().getDebug().observe(this, msg -> tvDebug.setText(msg));
        EventBus.getInstance().getPlayingUri().observe(this, uri -> adapter.setPlayingUri(uri));

        EventBus.getInstance().getVadRunning().observe(this, isRunning -> {
            this.isListening = isRunning;
            updateStartStopButton(isRunning);
        });
    }

    private void updateStartStopButton(boolean running) {
        if (running) {
            btnStartStop.setText("Stop");
            btnStartStop.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_STOP_RED)));
            btnStartStop.setTextColor(Color.WHITE);
        } else {
            btnStartStop.setText("Start");
            btnStartStop.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(COLOR_START_GREEN)));
            btnStartStop.setTextColor(Color.WHITE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        // Refresh the switch state in case it was changed while this activity was not visible
        // (e.g., via Telegram bot while Settings screen was open)
        isUpdatingSilentSwitch = true;
        switchSilent.setChecked(SettingsManager.isSilentMode(this));
        isUpdatingSilentSwitch = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    private void startTelegramServiceIfConfigured() {
        String token = SettingsManager.getBotToken(this);
        Set<Long> ids = SettingsManager.getAllowedUserIds(this);

        Intent i = new Intent(this, TelegramService.class);
        if (token != null && !token.isEmpty() && !ids.isEmpty()) {
            ContextCompat.startForegroundService(this, i);
        } else {
            i.setAction("STOP");
            startService(i);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isListening", isListening);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations() && isListening) {
            Intent i = new Intent(this, VadService.class);
            i.setAction("STOP");
            startService(i);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}