package com.github.aiyingshi.osc.tv.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.github.aiyingshi.osc.tv.R;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 日历底部面板（Arcane 点日期条右上角日历图标弹出的那张）：
 * 标题 + 完成，上月 / 年月 / 下月，星期头（周日开头），月历格；
 * 今天用主色字，选中日是灰色圆角底，相邻月的日子置灰；点某天跳到那一周并关面板。
 */
public class ProfileCalendarSheetDialog extends BaseBottomSheetDialog {

    public interface OnDaySelected {
        void onDaySelected(LocalDate day);
    }

    private LocalDate selectedDate;
    private YearMonth displayedMonth;
    private OnDaySelected listener;
    private TextView monthTitle;
    private LinearLayout gridContainer;

    public static ProfileCalendarSheetDialog create(LocalDate selectedDate, OnDaySelected listener) {
        ProfileCalendarSheetDialog dialog = new ProfileCalendarSheetDialog();
        dialog.selectedDate = selectedDate;
        dialog.displayedMonth = YearMonth.from(selectedDate);
        dialog.listener = listener;
        return dialog;
    }

    public void show(FragmentManager manager) {
        if (manager.findFragmentByTag("ProfileCalendarSheet") != null) return;
        show(manager, "ProfileCalendarSheet");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.profile_calendar_title);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        TextView done = new TextView(context);
        done.setText(R.string.profile_done);
        done.setTextSize(16);
        done.setTextColor(primaryColor());
        done.setPadding(dp(8), dp(4), dp(8), dp(4));
        done.setOnClickListener(v -> dismiss());
        header.addView(title);
        header.addView(spacer);
        header.addView(done);
        root.addView(header);

        LinearLayout nav = new LinearLayout(context);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(12), 0, dp(4));
        TextView prev = new TextView(context);
        prev.setText(R.string.profile_calendar_prev);
        prev.setTextSize(15);
        prev.setTextColor(primaryColor());
        prev.setPadding(dp(8), dp(4), dp(8), dp(4));
        prev.setOnClickListener(v -> {
            displayedMonth = displayedMonth.minusMonths(1);
            rebuildGrid();
        });
        monthTitle = new TextView(context);
        monthTitle.setTextSize(16);
        monthTitle.setTypeface(null, Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        monthTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = new TextView(context);
        next.setText(R.string.profile_calendar_next);
        next.setTextSize(15);
        next.setTextColor(primaryColor());
        next.setPadding(dp(8), dp(4), dp(8), dp(4));
        next.setOnClickListener(v -> {
            displayedMonth = displayedMonth.plusMonths(1);
            rebuildGrid();
        });
        nav.addView(prev);
        nav.addView(monthTitle);
        nav.addView(next);
        root.addView(nav);

        LinearLayout weekdayRow = new LinearLayout(context);
        weekdayRow.setOrientation(LinearLayout.HORIZONTAL);
        weekdayRow.setPadding(0, dp(4), 0, dp(4));
        DayOfWeek[] days = {DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY};
        for (DayOfWeek day : days) {
            TextView cell = new TextView(context);
            cell.setText(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            cell.setTextSize(13);
            cell.setTextColor(secondaryColor());
            cell.setGravity(Gravity.CENTER);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            weekdayRow.addView(cell);
        }
        root.addView(weekdayRow);

        gridContainer = new LinearLayout(context);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(gridContainer);
        rebuildGrid();
        return new SimpleBinding(root);
    }

    private void rebuildGrid() {
        if (monthTitle == null || gridContainer == null) return;
        monthTitle.setText(monthTitleText(displayedMonth));
        gridContainer.removeAllViews();
        Context context = requireContext();
        LocalDate today = LocalDate.now();
        LocalDate first = displayedMonth.atDay(1);
        LocalDate gridStart = first.minusDays(first.getDayOfWeek().getValue() % 7);
        for (int week = 0; week < 6; week++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 7; col++) {
                final LocalDate day = gridStart.plusDays(week * 7L + col);
                TextView cell = new TextView(context);
                cell.setText(String.valueOf(day.getDayOfMonth()));
                cell.setTextSize(16);
                cell.setGravity(Gravity.CENTER);
                cell.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
                boolean inMonth = day.getMonth() == displayedMonth.getMonth();
                boolean isToday = day.equals(today);
                boolean isSelected = day.equals(selectedDate);
                if (isSelected) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(12));
                    bg.setColor(containerHighColor());
                    cell.setBackground(bg);
                }
                if (isToday) {
                    cell.setTypeface(null, Typeface.BOLD);
                    cell.setTextColor(primaryColor());
                } else if (!inMonth) {
                    cell.setTextColor(secondaryColor());
                    cell.setAlpha(0.45f);
                } else {
                    cell.setTextColor(onSurfaceColor());
                }
                cell.setOnClickListener(v -> {
                    selectedDate = day;
                    if (listener != null) listener.onDaySelected(day);
                    dismiss();
                });
                row.addView(cell);
            }
            gridContainer.addView(row);
        }
    }

    private static String monthTitleText(YearMonth month) {
        if (Locale.getDefault().getLanguage().equals("zh")) {
            return month.getYear() + " 年 " + month.getMonthValue() + " 月";
        }
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.getYear();
    }

    private int primaryColor() {
        return resolveAttr(androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#FF0B57D0"));
    }

    private int secondaryColor() {
        return resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#FF8A8A8A"));
    }

    private int onSurfaceColor() {
        return resolveAttr(com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#FF1A1C1E"));
    }

    private int containerHighColor() {
        return resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#FFE1E2E9"));
    }

    private int resolveAttr(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(attr, value, true) && value.data != 0) {
            return value.data;
        }
        return fallback;
    }

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }

    private static class SimpleBinding implements ViewBinding {
        private final View root;

        private SimpleBinding(View root) {
            this.root = root;
        }

        @NonNull
        @Override
        public View getRoot() {
            return root;
        }
    }
}
