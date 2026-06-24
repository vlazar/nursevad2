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

    public interface OnPlayClickListener { void onPlayClick(String uriString); }
    private final OnPlayClickListener listener;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public LogAdapter(OnPlayClickListener listener) {
        super(new DiffUtil.ItemCallback<LogEvent>() {
            @Override public boolean areItemsTheSame(@NonNull LogEvent o, @NonNull LogEvent n) { return o.timestamp == n.timestamp && o.type == n.type; }
            @Override public boolean areContentsTheSame(@NonNull LogEvent o, @NonNull LogEvent n) { return o.timestamp == n.timestamp && o.type == n.type && o.level == n.level; }
        });
        this.listener = listener;
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(getItem(position)); }

    class ViewHolder extends RecyclerView.ViewHolder {
        ImageView swatch; TextView tvTitle, tvPlayed; ImageButton btnPlay;
        ViewHolder(@NonNull View v) { super(v); swatch=v.findViewById(R.id.swatch); tvTitle=v.findViewById(R.id.tvTitle); tvPlayed=v.findViewById(R.id.tvPlayed); btnPlay=v.findViewById(R.id.btnPlay); }

        void bind(LogEvent e) {
            String time = sdf.format(new Date(e.timestamp));
            if (e.type == LogEvent.Type.SPEECH) {
                swatch.setBackgroundColor(getColorForLevel(e.level));
                swatch.setImageResource(0); swatch.setAlpha(1.0f);
                tvTitle.setText(time + " - Level " + e.level);
                tvTitle.setTextColor(Color.BLACK); tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                itemView.setBackgroundColor(Color.TRANSPARENT);
                
                tvPlayed.setText("Played: " + e.displayName);
                tvPlayed.setVisibility(View.VISIBLE);
                
                if (e.uriString != null && !e.uriString.isEmpty()) {
                    btnPlay.setVisibility(View.VISIBLE);
                    btnPlay.setOnClickListener(v -> listener.onPlayClick(e.uriString));
                } else {
                    btnPlay.setVisibility(View.GONE);
                }
            } else {
                btnPlay.setVisibility(View.GONE); tvPlayed.setVisibility(View.GONE);
                swatch.setBackgroundColor(Color.TRANSPARENT);
                if (e.type == LogEvent.Type.START) {
                    swatch.setImageResource(android.R.drawable.ic_media_play); swatch.setColorFilter(Color.GRAY);
                    tvTitle.setText(time + " - Start"); tvTitle.setTextColor(Color.parseColor("#2E7D32"));
                    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD); itemView.setBackgroundColor(Color.parseColor("#E8F5E9"));
                } else {
                    swatch.setImageResource(android.R.drawable.ic_menu_close_clear_cancel); swatch.setColorFilter(Color.GRAY);
                    tvTitle.setText(time + " - Stop"); tvTitle.setTextColor(Color.parseColor("#C62828"));
                    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD); itemView.setBackgroundColor(Color.parseColor("#FFEBEE"));
                }
            }
        }

        private int getColorForLevel(int l) {
            switch(l) {
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