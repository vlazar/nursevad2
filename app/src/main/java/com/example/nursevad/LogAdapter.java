package com.example.nursevad;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogAdapter extends ListAdapter<LogEvent, LogAdapter.ViewHolder> {
    public interface OnPlayClickListener {
        void onPlayClick(String uriString);
        void onStopClick();
    }

    private final OnPlayClickListener listener;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private String currentPlayingUri = null;

    public LogAdapter(OnPlayClickListener listener) {
        super(new DiffUtil.ItemCallback<LogEvent>() {
            @Override 
            public boolean areItemsTheSame(@NonNull LogEvent oldItem, @NonNull LogEvent newItem) { 
                return oldItem.id.equals(newItem.id); 
            }
            @Override 
            public boolean areContentsTheSame(@NonNull LogEvent oldItem, @NonNull LogEvent newItem) { 
                return oldItem.id.equals(newItem.id) && 
                       oldItem.type == newItem.type && 
                       oldItem.level == newItem.level && 
                       (oldItem.displayName != null ? oldItem.displayName.equals(newItem.displayName) : newItem.displayName == null) &&
                       (oldItem.recordedSpeechUri != null ? oldItem.recordedSpeechUri.equals(newItem.recordedSpeechUri) : newItem.recordedSpeechUri == null);
            }
        });
        this.listener = listener;
    }

    public void setPlayingUri(String uri) { this.currentPlayingUri = uri; notifyDataSetChanged(); }

    @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false)); }
    @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(getItem(position)); }

    class ViewHolder extends RecyclerView.ViewHolder {
        ImageView swatch; TextView tvTitle; TextView tvPlayed; ImageButton btnPlay;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            swatch = itemView.findViewById(R.id.swatch); tvTitle = itemView.findViewById(R.id.tvTitle); tvPlayed = itemView.findViewById(R.id.tvPlayed); btnPlay = itemView.findViewById(R.id.btnPlay);
        }

        void bind(LogEvent event) {
            String time = sdf.format(new Date(event.timestamp));
            
            if (event.type == LogEvent.Type.SPEECH) {
                swatch.setBackgroundColor(getColorForLevel(event.level)); swatch.setImageResource(0); swatch.setClickable(false);
                tvTitle.setText(time + " - Level " + event.level); tvTitle.setTextColor(Color.BLACK); tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                itemView.setBackgroundColor(Color.TRANSPARENT);
                if (event.displayName != null && !event.displayName.isEmpty()) { tvPlayed.setText(event.displayName); tvPlayed.setVisibility(View.VISIBLE); } else tvPlayed.setVisibility(View.GONE);
                
                boolean isPlaying = event.recordedSpeechUri != null && event.recordedSpeechUri.equals(currentPlayingUri);
                if (isPlaying) { btnPlay.setImageResource(R.drawable.ic_stop); btnPlay.setColorFilter(Color.parseColor("#E57373")); } 
                else { btnPlay.setImageResource(R.drawable.ic_play); btnPlay.setColorFilter(Color.parseColor("#9E9E9E")); }
                btnPlay.setVisibility(View.VISIBLE);
                btnPlay.setOnClickListener(v -> { if (listener != null && event.recordedSpeechUri != null) { if (isPlaying) listener.onStopClick(); else listener.onPlayClick(event.recordedSpeechUri); } });

            } else if (event.type == LogEvent.Type.TELEGRAM_VOICE) {
                itemView.setBackgroundColor(Color.parseColor("#E3F2FD")); swatch.setBackgroundColor(Color.TRANSPARENT);
                boolean isPlaying = event.recordedSpeechUri != null && event.recordedSpeechUri.equals(currentPlayingUri);
                if (isPlaying) { swatch.setImageResource(R.drawable.ic_stop); swatch.setColorFilter(Color.parseColor("#E57373")); } 
                else { swatch.setImageResource(R.drawable.ic_play); swatch.setColorFilter(Color.parseColor("#1976D2")); }
                tvTitle.setText(time + " - Voice Message"); tvTitle.setTextColor(Color.parseColor("#1976D2")); tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvPlayed.setText("from " + (event.senderName != null ? event.senderName : "Telegram Bot")); tvPlayed.setTextColor(Color.DKGRAY); tvPlayed.setVisibility(View.VISIBLE);
                btnPlay.setVisibility(View.INVISIBLE);
                swatch.setClickable(true); swatch.setFocusable(true);
                swatch.setOnClickListener(v -> { if (listener != null && event.recordedSpeechUri != null) { if (isPlaying) listener.onStopClick(); else listener.onPlayClick(event.recordedSpeechUri); } });

            } else if (event.type == LogEvent.Type.INTRO || event.type == LogEvent.Type.REMINDER) {
                itemView.setBackgroundColor(Color.TRANSPARENT); swatch.setBackgroundColor(Color.TRANSPARENT); swatch.setImageResource(0); swatch.setClickable(false);
                String typeStr = (event.type == LogEvent.Type.INTRO) ? "Intro" : "Reminder";
                tvTitle.setText(time + " - " + typeStr); tvTitle.setTextColor(Color.BLACK); tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvPlayed.setText(event.displayName); tvPlayed.setTextColor(Color.DKGRAY); tvPlayed.setVisibility(View.VISIBLE);
                btnPlay.setVisibility(View.INVISIBLE);

            } else { // START / STOP
                btnPlay.setVisibility(View.INVISIBLE); tvPlayed.setVisibility(View.GONE); swatch.setClickable(false); swatch.setBackgroundColor(Color.TRANSPARENT);
                if (event.type == LogEvent.Type.START) {
                    tvTitle.setText(time + " - Start"); tvTitle.setTextColor(Color.parseColor("#2E7D32")); tvTitle.setTypeface(null, android.graphics.Typeface.BOLD); itemView.setBackgroundColor(Color.parseColor("#E8F5E9"));
                } else {
                    tvTitle.setText(time + " - Stop"); tvTitle.setTextColor(Color.parseColor("#C62828")); tvTitle.setTypeface(null, android.graphics.Typeface.BOLD); itemView.setBackgroundColor(Color.parseColor("#FFEBEE"));
                }
            }
        }

        private int getColorForLevel(int level) {
            switch (level) { case 1: return Color.parseColor("#4CAF50"); case 2: return Color.parseColor("#8BC34A"); case 3: return Color.parseColor("#FFEB3B"); case 4: return Color.parseColor("#FF9800"); case 5: return Color.parseColor("#F44336"); default: return Color.GRAY; }
        }
    }
}