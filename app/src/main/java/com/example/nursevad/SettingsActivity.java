package com.example.nursevad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

    // Reminder UI
    private RadioGroup rgTrigger;
    private RadioButton rbStart, rbSpeech;
    private SeekBar sbRemMin, sbRemMax;
    private TextView tvRemMin, tvRemMax;
    
    private String selectedFolderUri;
    private String selectedFolderName;

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private boolean isSaving = false; 

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

        prefs = getSharedPreferences("NurseVadPrefs", Context.MODE_PRIVATE);
        prefListener = (sharedPreferences, key) -> {
            if (!isSaving) {
                runOnUiThread(this::loadSettings);
            }
        };

        initThresholds();
        initDuration();
        initDelay();
        initWaitForEnd();
        initReminder();
        initFolderPicker();
        initTelegramSettings();
        initSaveButton();
        loadSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
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
                @Override 
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { 
                    int snapped = Math.round(progress / 5.0f) * 5;
                    if (snapped > 100) snapped = 100;
                    if (snapped < 0) snapped = 0;
                    if (progress != snapped) {
                        seekBar.setProgress(snapped);
                        return; 
                    }
                    tvThresholds[index].setText(snapped + "%"); 
                }
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
        sbDelay.setMax(10);
        sbDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { tvDelay.setText(progress + " seconds"); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void initWaitForEnd() {
        switchWaitForEnd = findViewById(R.id.switchWaitForEnd);
    }

    private void initReminder() {
        rgTrigger = findViewById(R.id.rgReminderTrigger);
        rbStart = findViewById(R.id.rbTriggerStart);
        rbSpeech = findViewById(R.id.rbTriggerSpeech);
        sbRemMin = findViewById(R.id.sbReminderMin);
        sbRemMax = findViewById(R.id.sbReminderMax);
        tvRemMin = findViewById(R.id.tvReminderMin);
        tvRemMax = findViewById(R.id.tvReminderMax);

        rgTrigger.setOnCheckedChangeListener((group, checkedId) -> updateReminderUI());

        SeekBar.OnSeekBarChangeListener remListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateReminderUI(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        sbRemMin.setOnSeekBarChangeListener(remListener);
        sbRemMax.setOnSeekBarChangeListener(remListener);
    }

    private void updateReminderUI() {
        boolean isStart = rbStart.isChecked();
        if (isStart) {
            sbRemMin.setMax(22); // (120 - 10) / 5
            sbRemMax.setMax(22);
            tvRemMin.setText((sbRemMin.getProgress() * 5 + 10) + " min");
            tvRemMax.setText((sbRemMax.getProgress() * 5 + 10) + " min");
        } else {
            sbRemMin.setMax(30); // (180 - 30) / 5
            sbRemMax.setMax(30);
            tvRemMin.setText((sbRemMin.getProgress() * 5 + 30) + " sec");
            tvRemMax.setText((sbRemMax.getProgress() * 5 + 30) + " sec");
        }
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
            isSaving = true;
            
            int[] thresholds = new int[5];
            for (int i = 0; i < 5; i++) thresholds[i] = sbThresholds[i].getProgress();
            SettingsManager.saveThresholds(this, thresholds);
            
            SettingsManager.saveDurationThreshold(this, sbDuration.getProgress() + 1);
            SettingsManager.saveDelay(this, sbDelay.getProgress());
            SettingsManager.saveWaitForEnd(this, switchWaitForEnd.isChecked());
            
            SettingsManager.saveReminderTrigger(this, rbStart.isChecked() ? 0 : 1);
            if (rbStart.isChecked()) {
                SettingsManager.saveReminderStartMin(this, sbRemMin.getProgress() * 5 + 10);
                SettingsManager.saveReminderStartMax(this, sbRemMax.getProgress() * 5 + 10);
            } else {
                SettingsManager.saveReminderSpeechMin(this, sbRemMin.getProgress() * 5 + 30);
                SettingsManager.saveReminderSpeechMax(this, sbRemMax.getProgress() * 5 + 30);
            }

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
            
            isSaving = false;
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

        int trigger = SettingsManager.getReminderTrigger(this);
        if (trigger == 0) rbStart.setChecked(true); else rbSpeech.setChecked(true);
        
        if (rbStart.isChecked()) {
            sbRemMin.setProgress((SettingsManager.getReminderStartMin(this) - 10) / 5);
            sbRemMax.setProgress((SettingsManager.getReminderStartMax(this) - 10) / 5);
        } else {
            sbRemMin.setProgress((SettingsManager.getReminderSpeechMin(this) - 30) / 5);
            sbRemMax.setProgress((SettingsManager.getReminderSpeechMax(this) - 30) / 5);
        }
        updateReminderUI();

        String folderName = SettingsManager.getFolderName(this);
        selectedFolderUri = SettingsManager.getFolderUri(this);
        selectedFolderName = folderName;
        tvFolderName.setText(folderName != null ? folderName : "No folder selected");
        
        if (!etBotToken.hasFocus()) etBotToken.setText(SettingsManager.getBotToken(this));
        if (!etUserIds.hasFocus()) {
            Set<Long> ids = SettingsManager.getAllowedUserIds(this);
            StringBuilder sb = new StringBuilder();
            for (Long id : ids) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(id);
            }
            etUserIds.setText(sb.toString());
        }
    }
}