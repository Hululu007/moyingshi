package com.github.aiyingshi.osc.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.github.aiyingshi.osc.tv.R;
import com.github.aiyingshi.osc.tv.bean.History;
import com.github.aiyingshi.osc.tv.ui.activity.HistoryResumeCoordinator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 观看活动底部面板（Arcane 点历史格 / 总览卡后弹出的那张）：
 * 标题 + 完成，筛选 chips（全部/电影/剧集），总小时 + 编辑；
 * 记录按天分组，组头是“yyyy-MM-dd · 当天总时长”，每行是浅灰卡片：片名 + 集数备注 + 右侧已看时长；
 * 点行续播，编辑态点行删除并刷新我的页。
 */
public class ProfileActivitySheetDialog extends BaseBottomSheetDialog {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_MOVIE = 1;
    private static final int FILTER_SERIES = 2;

    private String title;
    private long startMillis;
    private long endMillis;
    private List<History> records;
    private Runnable onChanged;

    private int filter = FILTER_ALL;
    private boolean editing;
    private LinearLayout contentContainer;
    private TextView totalView;
    private TextView editView;
    private final List<TextView> chipViews = new ArrayList<>();

    public static ProfileActivitySheetDialog create(String title, long startMillis, long endMillis,
                                                    List<History> allHistory, Runnable onChanged) {
        ProfileActivitySheetDialog dialog = new ProfileActivitySheetDialog();
        dialog.title = title;
        dialog.startMillis = startMillis;
        dialog.endMillis = endMillis;
        dialog.records = new ArrayList<>(allHistory);
        dialog.onChanged = onChanged;
        return dialog;
    }

    public void show(FragmentManager manager) {
        if (manager.findFragmentByTag("ProfileActivitySheet") != null) return;
        show(manager, "ProfileActivitySheet");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        Context context = requireContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        TextView done = new TextView(context);
        done.setText(R.string.profile_done);
        done.setTextSize(16);
        done.setTextColor(primaryColor());
        done.setPadding(dp(8), dp(4), dp(8), dp(4));
        done.setOnClickListener(v -> dismiss());
        header.addView(titleView);
        header.addView(spacer);
        header.addView(done);
        root.addView(header);

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(12), 0, 0);
        int[] chipLabels = {R.string.profile_activity_all, R.string.profile_activity_movies, R.string.profile_activity_series};
        for (int i = 0; i < chipLabels.length; i++) {
            final int which = i;
            TextView chip = new TextView(context);
            chip.setText(chipLabels[i]);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                filter = which;
                rebuild();
            });
            chips.addView(chip);
            chipViews.add(chip);
        }
        root.addView(chips);

        LinearLayout totalRow = new LinearLayout(context);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        totalRow.setPadding(0, dp(12), 0, dp(4));
        totalView = new TextView(context);
        totalView.setTextSize(16);
        totalView.setTypeface(null, Typeface.BOLD);
        View totalSpacer = new View(context);
        totalSpacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        editView = new TextView(context);
        editView.setText(R.string.profile_activity_edit);
        editView.setTextSize(14);
        editView.setTextColor(primaryColor());
        editView.setPadding(dp(8), dp(4), dp(8), dp(4));
        editView.setOnClickListener(v -> {
            editing = !editing;
            rebuild();
        });
        totalRow.addView(totalView);
        totalRow.addView(totalSpacer);
        totalRow.addView(editView);
        root.addView(totalRow);

        ScrollView scroll = new ScrollView(context);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(0, dp(8), 0, 0);
        scroll.addView(contentContainer);
        root.addView(scroll);

        return new SimpleBinding(root);
    }

    @Override
    protected void initView() {
        rebuild();
    }

    private void rebuild() {
        updateChips();
        editView.setText(editing ? R.string.profile_done : R.string.profile_activity_edit);
        List<History> filtered = new ArrayList<>();
        double totalHours = 0;
        for (History item : records) {
            long at = item.getCreateTime();
            if (at < startMillis || at >= endMillis) continue;
            if (!matchesFilter(item)) continue;
            filtered.add(item);
            totalHours += watchedHours(item);
        }
        totalView.setText(getString(R.string.profile_hours, totalHours));
        contentContainer.removeAllViews();
        if (filtered.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.profile_activity_empty);
            empty.setTextSize(14);
            empty.setTextColor(secondaryColor());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            contentContainer.addView(empty);
            return;
        }
        Map<LocalDate, List<History>> groups = new LinkedHashMap<>();
        for (History item : filtered) {
            LocalDate date = Instant.ofEpochMilli(item.getCreateTime())
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            List<History> group = groups.get(date);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(date, group);
            }
            group.add(item);
        }
        for (Map.Entry<LocalDate, List<History>> entry : groups.entrySet()) {
            long dayMs = 0;
            for (History item : entry.getValue()) dayMs += watchedMillis(item);
            TextView groupHeader = new TextView(requireContext());
            groupHeader.setText(getString(R.string.profile_activity_day_total,
                    entry.getKey().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    formatWatched(dayMs)));
            groupHeader.setTextSize(14);
            groupHeader.setTypeface(null, Typeface.BOLD);
            groupHeader.setPadding(0, dp(10), 0, dp(6));
            contentContainer.addView(groupHeader);
            for (History item : entry.getValue()) {
                contentContainer.addView(buildRow(item));
            }
        }
    }

    private View buildRow(History item) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowLp);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(containerHighColor());
        row.setBackground(bg);

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(context);
        name.setText(item.getVodName());
        name.setTextSize(15);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(name);
        String remarks = item.getVodRemarks();
        if (!TextUtils.isEmpty(remarks)) {
            TextView sub = new TextView(context);
            sub.setText(remarks);
            sub.setTextSize(13);
            sub.setTextColor(secondaryColor());
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(2), 0, 0);
            textColumn.addView(sub);
        }
        row.addView(textColumn);

        TextView right = new TextView(context);
        if (editing) {
            right.setText(R.string.profile_activity_delete);
            right.setTextColor(Color.parseColor("#FFF44336"));
        } else {
            right.setText(formatWatched(watchedMillis(item)));
            right.setTextColor(secondaryColor());
        }
        right.setTextSize(13);
        right.setPadding(dp(8), 0, 0, 0);
        row.addView(right);

        row.setOnClickListener(v -> {
            if (editing) {
                item.deleteDisplayItem();
                records.remove(item);
                if (onChanged != null) onChanged.run();
                rebuild();
            } else {
                Activity activity = getActivity();
                dismiss();
                if (activity != null) HistoryResumeCoordinator.open(activity, item);
            }
        });
        return row;
    }

    private void updateChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            TextView chip = chipViews.get(i);
            boolean selected = i == filter;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(selected ? primaryColor() : containerHighColor());
            chip.setBackground(bg);
            chip.setTextColor(selected ? Color.WHITE : onSurfaceColor());
        }
    }

    private boolean matchesFilter(History item) {
        if (filter == FILTER_ALL) return true;
        String type = item.getTypeName();
        if (filter == FILTER_MOVIE) return type != null && type.contains("电影");
        return type != null && (type.contains("电视") || type.contains("剧"));
    }

    /** 已看毫秒：有进度按进度算，看完按全长算；没进度记 0。 */
    private static long watchedMillis(History item) {
        if (item.getDuration() <= 0 || item.getPosition() <= 0) return 0;
        return Math.min(item.getPosition(), item.getDuration());
    }

    private static double watchedHours(History item) {
        return watchedMillis(item) / 3600000.0;
    }

    /** 短时长：60 秒内显示秒，60 分钟内显示分钟，否则显示小时（两位小数）。 */
    private String formatWatched(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return getString(R.string.profile_duration_seconds, seconds);
        if (seconds < 3600) return getString(R.string.profile_duration_minutes, seconds / 60);
        return getString(R.string.profile_hours, seconds / 3600.0);
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
