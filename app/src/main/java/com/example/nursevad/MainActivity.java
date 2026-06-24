package com.example.nursevad;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {
    private boolean isListening = false;
    private LogAdapter adapter;
    private TextView statusText;
    private ProgressBar volumeMeter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request Microphone Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
        }

        // Request Notification Permission (Required for Android 13+ Foreground Services)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        statusText = findViewById(R.id.statusText);
        volumeMeter = findViewById(R.id.volumeMeter);
        Button btnStartStop = findViewById(R.id.btnStartStop);
        ImageButton btnClear = findViewById(R.id.btnClear);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter(uri -> {
            Intent i = new Intent(this, VadService.class);
            i.setAction("PLAY_SPECIFIC");
            i.putExtra("URI", uri);
            startService(i);
        });
        recyclerView.setAdapter(adapter);

        EventRepository.getInstance().getLiveEvents().observe(this, events -> adapter.submitList(events));

        btnStartStop.setOnClickListener(v -> {
            Intent i = new Intent(this, VadService.class);
            if (!isListening) {
                ContextCompat.startForegroundService(this, i);
                btnStartStop.setText("Stop");
                isListening = true;
            } else {
                i.setAction("STOP");
                startService(i);
                btnStartStop.setText("Start");
                isListening = false;
                statusText.setText("Idle");
            }
        });

        btnClear.setOnClickListener(v -> EventRepository.getInstance().clearEvents());
        
        EventBus.getInstance().getStatus().observe(this, status -> {
            statusText.setText(status);
            // Visual indicator for errors
            if (status != null && status.startsWith("ERR:")) {
                statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            } else {
                statusText.setTextColor(getResources().getColor(android.R.color.black));
            }
        });
        EventBus.getInstance().getVolume().observe(this, vol -> volumeMeter.setProgress(vol));
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