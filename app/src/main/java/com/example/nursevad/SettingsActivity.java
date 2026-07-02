package com.example.nursevad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    private SeekBar[] sbThresholds = new SeekBar[5];
    private TextView[] tvThresholds = new TextView[5];
    private SeekBar sbDuration;
    private TextView tvDuration;
    private TextView tvFolderName;
    
    private SeekBar sbDelay;
    private TextView tvDelay;
    private SwitchCompat switchWaitForEnd;
    
    private TextInputEditText etBotToken;
    private TextInputEditText etUserIds;
    
    private String selectedFolderUri;
    private String selectedFolderName;

    // NEW: For reactive settings sync
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private final ActivityResultLauncher<Uri> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    selectedFolderUri = uri.toString();
                    DocumentFile df = DocumentFile.fromTreeUri(this, uri);
                    selectedFolderName = df != null ? df.getName() : "Selected Folder";
                    tvFolderName.setText(selectedFolderName);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialize preference listener
        prefs = getSharedPreferences("NurseVadPrefs", Context.MODE_PRIVATE);
        prefListener = (sharedPreferences, key) -> {
            // Reload UI whenever a setting is changed externally (e.g. by Telegram Bot)
            runOnUiThread(this::loadSettings);
        };

        initThresholds();
        initDuration();
        initDelay();
        initWaitForEnd();
        initFolderPicker();
        initTelegramSettings();
        initSaveButton();
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register listener when screen is visible
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        loadSettings(); // Ensure we have latest data when returning to screen
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unregister to prevent leaks
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    private void initThresholds() {
        int[] seekBarIds = {R.id.sbThreshold1, R.id.sbThreshold2, R.id.sbThreshold3, R.id.sbThreshold4, R.id.sbThreshold5};
        int[] textViewIds = {R.id.tvThreshold1, R.id.tvThreshold2, R.id.tvThreshold3, R.id.tvThreshold4, R.id.tvThreshold5};
        for (int i = 0; i < 5; i++) {
            sbThresholds[i] = findViewById(seekBarIds[i]);
            tvThresholds[i] = findViewById(textViewIds[i]);
            sbThresholds[i].setMax(100);
            final int index = i;
            sbThresholds[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { tvThresholds[index].setText(progress + "%"); }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    private void initDuration() {
        sbDuration = findViewById(R.id.sbDuration);
        tvDuration = findViewById(R.id.tvDuration);
        sbDuration.setMax(9); 
        sbDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { tvDuration.setText("shorter than " + (progress + 1) + " seconds"); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void initDelay() {
        sbDelay = findViewById(R.id.sbDelay);
        tvDelay = findViewById(R.id.tvDelay);
        sbDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { tvDelay.setText(progress + " seconds"); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void initWaitForEnd() {
        switchWaitForEnd = findViewById(R.id.switchWaitForEnd);
    }

    private void initFolderPicker() {
        tvFolderName = findViewById(R.id.tvFolderName);
        Button btnSelectFolder = findViewById(R.id.btnSelectFolder);
        btnSelectFolder.setOnClickListener(v -> folderPickerLauncher.launch(null));
    }

    private void initTelegramSettings() {
        etBotToken = findViewById(R.id.etBotToken);
        etUserIds = findViewById(R.id.etUserIds);
    }

    private void initSaveButton() {
        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            int[] thresholds = new int[5];
            for (int i = 0; i < 5; i++) thresholds[i] = sbThresholds[i].getProgress();
            SettingsManager.saveThresholds(this, thresholds);
            
            SettingsManager.saveDurationThreshold(this, sbDuration.getProgress() + 1);
            SettingsManager.saveDelay(this, sbDelay.getProgress());
            SettingsManager.saveWaitForEnd(this, switchWaitForEnd.isChecked());
            
            if (selectedFolderUri != null) SettingsManager.saveFolder(this, selectedFolderUri, selectedFolderName);
            
            String token = etBotToken.getText() != null ? etBotToken.getText().toString().trim() : "";
            String idsStr = etUserIds.getText() != null ? etUserIds.getText().toString().trim() : "";
            
            SettingsManager.saveBotToken(this, token);
            SettingsManager.saveAllowedUserIds(this, idsStr);
            
            Intent tgIntent = new Intent(this, TelegramService.class);
            if (!token.isEmpty() && !idsStr.isEmpty()) {
                ContextCompat.startForegroundService(this, tgIntent);
            } else {
                tgIntent.setAction("STOP");
                startService(tgIntent);
            }
            
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void loadSettings() {
        int[] thresholds = SettingsManager.getThresholds(this);
        for (int i = 0; i < 5; i++) {
            sbThresholds[i].setProgress(thresholds[i]);
            tvThresholds[i].setText(thresholds[i] + "%");
        }
        int duration = SettingsManager.getDurationThreshold(this);
        sbDuration.setProgress(duration - 1);
        tvDuration.setText("shorter than " + duration + " seconds");

        int delay = SettingsManager.getDelay(this);
        sbDelay.setProgress(delay);
        tvDelay.setText(delay + " seconds");

        switchWaitForEnd.setChecked(SettingsManager.getWaitForEnd(this));

        String folderName = SettingsManager.getFolderName(this);
        selectedFolderUri = SettingsManager.getFolderUri(this);
        selectedFolderName = folderName;
        tvFolderName.setText(folderName != null ? folderName : "No folder selected");
        
        etBotToken.setText(SettingsManager.getBotToken(this));
        Set<Long> ids = SettingsManager.getAllowedUserIds(this);
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(id);
        }
        etUserIds.setText(sb.toString());
    }
}