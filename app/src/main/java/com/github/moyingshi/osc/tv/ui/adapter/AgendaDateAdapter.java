package com.github.moyingshi.osc.tv.ui.adapter;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.following.FollowingAgenda;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 追更页顶部日期条：横向 14 天，每格显示日期数字、星期与当天播出部数。
 * 点某天只看那天播出的剧，再点一次回到全部；选中态在代码里着色，不另建 drawable。
 */
public class AgendaDateAdapter extends RecyclerView.Adapter<AgendaDateAdapter.Holder> {

    public interface Listener {
        void onDaySelected(LocalDate day);
    }

    public static final class Day {
        public final LocalDate date;
        public final int count;

        public Day(LocalDate date, int count) {
            this.date = date;
            this.count = count;
        }
    }

    private final Listener listener;
    private final List<Day> days = new ArrayList<>();
    private LocalDate selected;

    public AgendaDateAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setDays(List<Day> days, LocalDate selected) {
        this.days.clear();
        if (days != null) this.days.addAll(days);
        this.selected = selected;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_agenda_day, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Day day = days.get(position);
        Context context = holder.itemView.getContext();
        boolean isSelected = selected != null && selected.equals(day.date);
        boolean isToday = FollowingAgenda.today().equals(day.date);
        holder.number.setText(String.valueOf(day.date.getDayOfMonth()));
        String[] weekdays = context.getResources().getStringArray(R.array.following_weekdays);
        int weekday = FollowingAgenda.weekdayOf(FollowingAgenda.dayStartMillis(day.date));
        String weekText = weekday >= 1 && weekday <= weekdays.length ? weekdays[weekday - 1] : "";
        holder.week.setText(isToday ? context.getString(R.string.following_agenda_today_short)
                : context.getString(R.string.following_agenda_weekday, weekText));
        if (day.count > 0) {
            holder.count.setVisibility(View.VISIBLE);
            holder.count.setText(context.getString(R.string.following_agenda_day_count, day.count));
        } else {
            holder.count.setVisibility(View.GONE);
        }
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 12));
        if (isSelected) {
            background.setColor(ContextCompat.getColor(context, R.color.following_agenda_day_selected_bg));
            holder.number.setTextColor(ContextCompat.getColor(context, R.color.following_agenda_day_selected_text));
            holder.week.setTextColor(ContextCompat.getColor(context, R.color.following_agenda_day_selected_text));
            holder.count.setTextColor(ContextCompat.getColor(context, R.color.following_agenda_day_selected_text));
        } else {
            background.setColor(ContextCompat.getColor(context, R.color.following_agenda_day_bg));
            holder.number.setTextColor(ContextCompat.getColor(context, R.color.following_text_primary));
            holder.week.setTextColor(ContextCompat.getColor(context, R.color.following_text_secondary));
            holder.count.setTextColor(ContextCompat.getColor(context, R.color.following_text_primary));
        }
        holder.itemView.setBackground(background);
        holder.itemView.setOnClickListener(view -> {
            if (listener != null) listener.onDaySelected(day.date);
        });
    }

    @Override
    public int getItemCount() {
        return days.size();
    }

    private static float dp(Context context, int value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView number;
        final TextView week;
        final TextView count;

        Holder(@NonNull View itemView) {
            super(itemView);
            number = itemView.findViewById(R.id.dayNumber);
            week = itemView.findViewById(R.id.dayWeek);
            count = itemView.findViewById(R.id.dayCount);
        }
    }
}
