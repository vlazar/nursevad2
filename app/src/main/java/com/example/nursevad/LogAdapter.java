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
            @Override public boolean areItemsTheSame(@NonNull LogEvent oldItem, @NonNull LogEvent newItem) {
                return oldItem.id.equals(newItem.id);
            }
            @Override public boolean areContentsTheSame(@NonNull LogEvent oldItem, @NonNull LogEvent newItem) {
                return oldItem.id.equals(newItem.id) && 
                       oldItem.type == newItem.type && 
                       oldItem.level == newItem.level &&
                       oldItem.isPoni == newItem.isPoni &&
                       (oldItem.displayName != null ? oldItem.displayName.equals(newItem.displayName) : newItem.displayName == null) &&
                       (oldItem.recordedSpeechUri != null ? oldItem.recordedSpeechUri.equals(newItem.recordedSpeechUri) : newItem.recordedSpeechUri == null);
            }
        });
        this.listener = listener;
    }

    public void setPlayingUri(String uri) { this.currentPlayingUri = uri; notifyDataSetChanged(); }

    @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LogEvent event = getItem(position);
        DebugLogger.log("LogAdapter BIND pos=" + position + " type=" + event.type + 
                " id=" + event.id + " ts=" + event.timestamp + " isPoni=" + event.isPoni);
        holder.bind(event);
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        ImageView swatch; TextView tvTitle; TextView tvPlayed; ImageButton btnPlay;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            swatch = itemView.findViewById(R.id.swatch);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvPlayed = itemView.findViewById(R.id.tvPlayed);
            btnPlay = itemView.findViewById(R.id.btnPlay);
        }

        void bind(LogEvent event) {
            String time = sdf.format(new Date(event.timestamp));
            
            // RESET ALL visual properties first to prevent recycling artifacts
            swatch.setBackgroundColor(Color.TRANSPARENT);
            swatch.setImageResource(0);
            swatch.setColorFilter(Color.TRANSPARENT);
            swatch.setClickable(false);
            swatch.setOnClickListener(null);
            tvTitle.setText("");
            tvTitle.setTextColor(Color.BLACK);
            tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
            tvPlayed.setText("");
            tvPlayed.setVisibility(View.GONE);
            btnPlay.setVisibility(View.INVISIBLE);
            btnPlay.setOnClickListener(null);
            itemView.setBackgroundColor(Color.TRANSPARENT);
            
            if (event.type == LogEvent.Type.SPEECH) {
                if (event.isPoni) {
                    swatch.setBackgroundColor(Color.GRAY);
                    tvTitle.setTextColor(Color.GRAY);
                    tvPlayed.setTextColor(Color.GRAY);
                } else {
                    swatch.setBackgroundColor(getColorForLevel(event.level));
                    tvTitle.setTextColor(Color.BLACK);
                    tvPlayed.setTextColor(Color.DKGRAY);
                }
                
                tvTitle.setText(time + " - Level " + event.level);
                tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                
                if (event.displayName != null && !event.displayName.isEmpty()) {
                    tvPlayed.setText(event.displayName);
                    tvPlayed.setVisibility(View.VISIBLE);
                }
                
                boolean isPlaying = event.recordedSpeechUri != null && event.recordedSpeechUri.equals(currentPlayingUri);
                if (!event.isPoni) {
                    btnPlay.setVisibility(View.VISIBLE);
                    if (isPlaying) {
                        btnPlay.setImageResource(R.drawable.ic_stop);
                        btnPlay.setColorFilter(Color.parseColor("#E57373"));
                    } else {
                        btnPlay.setImageResource(R.drawable.ic_play);
                        btnPlay.setColorFilter(Color.parseColor("#9E9E9E"));
                    }
                    btnPlay.setOnClickListener(v -> {
                        if (listener != null && event.recordedSpeechUri != null) {
                            if (isPlaying) listener.onStopClick();
                            else listener.onPlayClick(event.recordedSpeechUri);
                        }
                    });
                }

            } else if (event.type == LogEvent.Type.TELEGRAM_VOICE) {
                itemView.setBackgroundColor(Color.parseColor("#E3F2FD"));
                boolean isPlaying = event.recordedSpeechUri != null && event.recordedSpeechUri.equals(currentPlayingUri);
                if (isPlaying) {
                    swatch.setImageResource(R.drawable.ic_stop);
                    swatch.setColorFilter(Color.parseColor("#E57373"));
                } else {
                    swatch.setImageResource(R.drawable.ic_play);
                    swatch.setColorFilter(Color.parseColor("#1976D2"));
                }
                tvTitle.setText(time + " - Voice Message");
                tvTitle.setTextColor(Color.parseColor("#1976D2"));
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvPlayed.setText("from " + (event.senderName != null ? event.senderName : "Telegram Bot"));
                tvPlayed.setTextColor(Color.DKGRAY);
                tvPlayed.setVisibility(View.VISIBLE);
                swatch.setClickable(true);
                swatch.setFocusable(true);
                swatch.setOnClickListener(v -> {
                    if (listener != null && event.recordedSpeechUri != null) {
                        if (isPlaying) listener.onStopClick();
                        else listener.onPlayClick(event.recordedSpeechUri);
                    }
                });

            } else if (event.type == LogEvent.Type.INTRO || event.type == LogEvent.Type.REMINDER) {
                String typeStr = (event.type == LogEvent.Type.INTRO) ? "Intro" : "Reminder";
                tvTitle.setText(time + " - " + typeStr);
                tvTitle.setTextColor(Color.BLACK);
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvPlayed.setText(event.displayName);
                tvPlayed.setTextColor(Color.DKGRAY);
                tvPlayed.setVisibility(View.VISIBLE);

            } else if (event.type == LogEvent.Type.START) {
                tvTitle.setText(time + " - Start");
                tvTitle.setTextColor(Color.parseColor("#2E7D32"));
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                itemView.setBackgroundColor(Color.parseColor("#E8F5E9"));

            } else if (event.type == LogEvent.Type.STOP) {
                tvTitle.setText(time + " - Stop");
                tvTitle.setTextColor(Color.parseColor("#C62828"));
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                itemView.setBackgroundColor(Color.parseColor("#FFEBEE"));
            }
        }

        private int getColorForLevel(int level) {
            switch (level) {
                case 1: return Color.parseColor("#4CAF50");
                case 2: return Color.parseColor("#8BC34A");
                case 3: return Color.parseColor("#FFEB3B");
                case 4: return Color.parseColor("#FF9800");
                case 5: return Color.parseColor("#F44336");
                default: return Color.GRAY;
            }
        }
    }
}