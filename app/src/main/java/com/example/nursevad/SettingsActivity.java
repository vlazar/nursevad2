package com.example.nursevad;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

public class SettingsActivity extends AppCompatActivity {

    private SeekBar[] sbThresholds = new SeekBar[5];
    private TextView[] tvThresholds = new TextView[5];
    private SeekBar sbDuration;
    private TextView tvDuration;
    private TextView tvFolderName;
    
    private String selectedFolderUri;
    private String selectedFolderName;

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

        initThresholds();
        initDuration();
        initFolderPicker();
        initSaveButton();
        
        loadSettings();
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
                    tvThresholds[index].setText(progress + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    private void initDuration() {
        sbDuration = findViewById(R.id.sbDuration);
        tvDuration = findViewById(R.id.tvDuration);
        
        sbDuration.setMax(9); // 0 to 9 represents 1s to 10s
        sbDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int seconds = progress + 1;
                tvDuration.setText("shorter than " + seconds + " seconds");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void initFolderPicker() {
        tvFolderName = findViewById(R.id.tvFolderName);
        Button btnSelectFolder = findViewById(R.id.btnSelectFolder);
        btnSelectFolder.setOnClickListener(v -> folderPickerLauncher.launch(null));
    }

    private void initSaveButton() {
        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            int[] thresholds = new int[5];
            for (int i = 0; i < 5; i++) {
                thresholds[i] = sbThresholds[i].getProgress();
            }
            SettingsManager.saveThresholds(this, thresholds);
            
            int duration = sbDuration.getProgress() + 1;
            SettingsManager.saveDurationThreshold(this, duration);
            
            if (selectedFolderUri != null) {
                SettingsManager.saveFolder(this, selectedFolderUri, selectedFolderName);
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

        String folderName = SettingsManager.getFolderName(this);
        selectedFolderUri = SettingsManager.getFolderUri(this);
        selectedFolderName = folderName;
        if (folderName != null) {
            tvFolderName.setText(folderName);
        } else {
            tvFolderName.setText("No folder selected");
        }
    }
}