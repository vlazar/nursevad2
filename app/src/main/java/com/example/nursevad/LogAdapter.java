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
    }

    private final OnPlayClickListener listener;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public LogAdapter(OnPlayClickListener listener) {
        super(new DiffUtil.ItemCallback<LogEvent>() {
            @Override
            public boolean areItemsTheSame(@NonNull LogEvent oldItem, @NonNull LogEvent newItem) {
                return oldItem.timestamp == newItem.timestamp && oldItem.type == newItem.type;
            }

            @Override
            public boolean areContentsTheSame(@NonNull LogEvent oldItem, @NonNull LogEvent newItem) {
                return oldItem.timestamp == newItem.timestamp && 
                       oldItem.type == newItem.type && 
                       oldItem.level == newItem.level &&
                       (oldItem.displayName != null ? oldItem.displayName.equals(newItem.displayName) : newItem.displayName == null);
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LogEvent event = getItem(position);
        holder.bind(event);
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        ImageView swatch;
        TextView tvTitle;
        TextView tvPlayed;
        ImageButton btnPlay;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            swatch = itemView.findViewById(R.id.swatch);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvPlayed = itemView.findViewById(R.id.tvPlayed);
            btnPlay = itemView.findViewById(R.id.btnPlay);
        }

        void bind(LogEvent event) {
            String time = sdf.format(new Date(event.timestamp));
            
            if (event.type == LogEvent.Type.SPEECH) {
                int color = getColorForLevel(event.level);
                swatch.setBackgroundColor(color);
                swatch.setImageResource(0); // Clear icon
                swatch.setAlpha(1.0f);
                
                tvTitle.setText(time + " - Level " + event.level);
                tvTitle.setTextColor(Color.BLACK);
                tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                itemView.setBackgroundColor(Color.TRANSPARENT);
                
                if (event.displayName != null && !event.displayName.isEmpty()) {
                    tvPlayed.setText("Played: " + event.displayName);
                    tvPlayed.setVisibility(View.VISIBLE);
                } else {
                    tvPlayed.setVisibility(View.GONE);
                }
                
                btnPlay.setVisibility(View.VISIBLE);
                btnPlay.setOnClickListener(v -> {
                    if (listener != null && event.uriString != null) {
                        listener.onPlayClick(event.uriString);
                    }
                });

            } else {
                // START or STOP events
                btnPlay.setVisibility(View.GONE);
                tvPlayed.setVisibility(View.GONE);
                swatch.setBackgroundColor(Color.TRANSPARENT);
                
                if (event.type == LogEvent.Type.START) {
                    swatch.setImageResource(android.R.drawable.ic_media_play);
                    swatch.setColorFilter(Color.GRAY); // Gray icon
                    tvTitle.setText(time + " - Start");
                    tvTitle.setTextColor(Color.parseColor("#2E7D32")); // Dark Green
                    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                    itemView.setBackgroundColor(Color.parseColor("#E8F5E9")); // Light Green BG
                } else {
                    swatch.setImageResource(android.R.drawable.ic_menu_close_clear_cancel); // Stop/Cancel icon
                    swatch.setColorFilter(Color.GRAY); // Gray icon
                    tvTitle.setText(time + " - Stop");
                    tvTitle.setTextColor(Color.parseColor("#C62828")); // Dark Red
                    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                    itemView.setBackgroundColor(Color.parseColor("#FFEBEE")); // Light Red BG
                }
            }
        }

        private int getColorForLevel(int level) {
            switch (level) {
                case 1: return Color.parseColor("#4CAF50"); // Green
                case 2: return Color.parseColor("#8BC34A"); // Light Green
                case 3: return Color.parseColor("#FFEB3B"); // Yellow
                case 4: return Color.parseColor("#FF9800"); // Orange
                case 5: return Color.parseColor("#F44336"); // Red
                default: return Color.GRAY;
            }
        }
    }
}