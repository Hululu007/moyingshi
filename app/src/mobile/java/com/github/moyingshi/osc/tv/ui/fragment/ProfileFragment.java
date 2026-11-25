package com.github.moyingshi.osc.tv.ui.fragment;

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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.widget.ViewPager2;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.FragmentProfileBinding;
import com.github.moyingshi.osc.tv.databinding.ItemProfileCardBinding;
import com.github.moyingshi.osc.tv.following.Following;
import com.github.moyingshi.osc.tv.following.FollowingAgenda;
import com.github.moyingshi.osc.tv.profile.ProfileCard;
import com.github.moyingshi.osc.tv.profile.ProfileLayout;
import com.github.moyingshi.osc.tv.profile.ProfileStats;
import com.github.moyingshi.osc.tv.ui.activity.FollowingActivity;
import com.github.moyingshi.osc.tv.ui.activity.HistoryActivity;
import com.github.moyingshi.osc.tv.ui.activity.KeepActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.ui.custom.ProfileContributionView;
import com.github.moyingshi.osc.tv.ui.custom.ProfileDotsChartView;
import com.github.moyingshi.osc.tv.ui.custom.ProfileMonthChartView;
import com.github.moyingshi.osc.tv.ui.dialog.ProfileEditorDialog;
import com.github.moyingshi.osc.tv.utils.Task;
import com.google.android.material.card.MaterialCardView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 我的页：按 Arcane 截图 1:1 复刻的观影数据页。
 * 版式：页头“我的”+日历图标 → 7 天日期条 → 历史观看格 → 追剧行 →
 * 今天三页横滑（今天/本周/本月）→ 收藏/在看双 tile → 本月曲线+刷剧 TOP3 → 类型分布 → 管理模块。
 * 模块顺序与显隐仍走 ProfileLayout；TREND1/2 不再单独成卡，并入本月卡的 TOP3；
 * 收藏与在看合成一行双 tile。数据一次后台取完（ProfileStats.load）。
 */
public class ProfileFragment extends BaseFragment {

    private FragmentProfileBinding mBinding;

    /** 日期条选中日（默认今天）与条内控件，点选后只刷新条本身，不重载数据。 */
    private LocalDate selectedDate;
    private List<LocalDate> stripDates;
    private List<View> stripCells;
    private TextView stripStatus;
    private ProfileStats stripStats;

    public static ProfileFragment newInstance() {
        return new ProfileFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentProfileBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.calendarBtn.setOnClickListener(v -> FollowingActivity.start(requireActivity()));
        mBinding.manageBtn.setOnClickListener(v -> ProfileEditorDialog.create(this::load).show(getChildFragmentManager()));
        load();
    }

    private void load() {
        Task.execute(() -> {
            ProfileStats stats = ProfileStats.load();
            List<ProfileCard> cards = ProfileLayout.visibleCards();
            App.post(() -> {
                if (mBinding == null || getView() == null) return;
                render(stats, cards);
            });
        });
    }

    private void render(ProfileStats stats, List<ProfileCard> cards) {
        mBinding.cardContainer.removeAllViews();
        mBinding.emptyHint.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        boolean tilesDone = false;
        for (ProfileCard card : cards) {
            switch (card) {
                case CALENDAR:
                    mBinding.cardContainer.addView(buildCalendarSection(stats));
                    break;
                case TRACKING:
                    mBinding.cardContainer.addView(buildTrackingSection(stats));
                    break;
                case TREND1:
                case TREND2:
                    // 并入本月卡的刷剧 TOP3，不再单独成卡。
                    break;
                case FAVORITES:
                case WATCHING:
                    if (!tilesDone) {
                        mBinding.cardContainer.addView(buildTileRow(stats, cards));
                        tilesDone = true;
                    }
                    break;
                default: {
                    ItemProfileCardBinding cardBinding = ItemProfileCardBinding.inflate(inflater, mBinding.cardContainer, false);
                    cardBinding.cardTitle.setText(card.titleRes);
                    fillCard(card, cardBinding, stats, cards);
                    mBinding.cardContainer.addView(cardBinding.getRoot());
                    break;
                }
            }
        }
    }

    private void fillCard(ProfileCard card, ItemProfileCardBinding binding, ProfileStats stats, List<ProfileCard> cards) {
        LinearLayout content = binding.cardContent;
        switch (card) {
            case HISTORY:
                navigate(binding, () -> HistoryActivity.start(requireActivity()));
                ProfileContributionView grid = new ProfileContributionView(requireContext());
                grid.setData(stats.dailyHours);
                content.addView(grid);
                TextView openHistory = new TextView(requireContext());
                openHistory.setText(getString(R.string.profile_open_history) + " ›");
                openHistory.setTextSize(14);
                openHistory.setTextColor(secondaryColor());
                openHistory.setPadding(0, dp(10), 0, 0);
                openHistory.setOnClickListener(v -> HistoryActivity.start(requireActivity()));
                content.addView(openHistory);
                break;
            case OVERVIEW:
                binding.cardHeader.setVisibility(View.GONE);
                fillOverview(content, stats);
                break;
            case MONTH:
                binding.cardMore.setVisibility(View.GONE);
                fillMonth(content, stats, cards);
                break;
            case GENRES:
                binding.cardMore.setVisibility(View.GONE);
                fillGenres(content, stats);
                break;
            default:
                break;
        }
    }

    /** 卡片头可点进对应页；不可点的卡把 › 藏掉。 */
    private void navigate(ItemProfileCardBinding binding, Runnable target) {
        binding.cardHeader.setOnClickListener(v -> target.run());
    }

    // ---------- 日历：7 天日期条 ----------

    private View buildCalendarSection(ProfileStats stats) {
        Context context = requireContext();
        stripStats = stats;
        LocalDate today = LocalDate.now();
        LocalDate sunday = today.minusDays(today.getDayOfWeek().getValue() % 7);
        stripDates = new ArrayList<>();
        for (int i = 0; i < 7; i++) stripDates.add(sunday.plusDays(i));
        selectedDate = today;

        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, 0, 0, dp(12));

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(4), dp(4), dp(4), 0);
        stripCells = new ArrayList<>();
        for (LocalDate date : stripDates) {
            final LocalDate day = date;
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cell.setPadding(0, dp(6), 0, dp(6));
            TextView dayNum = new TextView(context);
            dayNum.setText(String.valueOf(date.getDayOfMonth()));
            dayNum.setTextSize(16);
            dayNum.setTypeface(null, Typeface.BOLD);
            dayNum.setGravity(Gravity.CENTER);
            TextView weekday = new TextView(context);
            weekday.setText(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            weekday.setTextSize(12);
            weekday.setTextColor(secondaryColor());
            weekday.setGravity(Gravity.CENTER);
            View dot = new View(context);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(4), dp(4)));
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(primaryColor());
            dot.setBackground(dotBg);
            dot.setVisibility(hasAiringOn(stats, date) ? View.VISIBLE : View.INVISIBLE);
            cell.addView(dayNum);
            cell.addView(weekday);
            cell.addView(dot);
            cell.setOnClickListener(v -> {
                selectedDate = day;
                refreshStripSelection();
                refreshStripStatus();
            });
            strip.addView(cell);
            stripCells.add(cell);
        }
        section.addView(strip);

        stripStatus = new TextView(context);
        stripStatus.setTextSize(14);
        stripStatus.setTextColor(secondaryColor());
        stripStatus.setGravity(Gravity.CENTER);
        stripStatus.setPadding(0, dp(8), 0, dp(4));
        stripStatus.setOnClickListener(v -> FollowingActivity.start(requireActivity()));
        section.addView(stripStatus);

        refreshStripSelection();
        refreshStripStatus();
        return section;
    }

    private void refreshStripSelection() {
        if (stripCells == null || stripDates == null) return;
        LocalDate today = LocalDate.now();
        for (int i = 0; i < stripCells.size(); i++) {
            LinearLayout cell = (LinearLayout) stripCells.get(i);
            LocalDate date = stripDates.get(i);
            TextView dayNum = (TextView) cell.getChildAt(0);
            boolean isSelected = date.equals(selectedDate);
            boolean isToday = date.equals(today);
            if (isSelected) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(12));
                bg.setColor(containerHighColor());
                cell.setBackground(bg);
            } else {
                cell.setBackground(null);
            }
            dayNum.setTextColor(isToday || isSelected ? primaryColor() : onSurfaceColor());
        }
    }

    private void refreshStripStatus() {
        if (stripStatus == null || selectedDate == null || stripStats == null) return;
        List<String> names = new ArrayList<>();
        for (Following item : stripStats.upcomingThisMonth) {
            if (toDate(item.nextAirAt).equals(selectedDate)) names.add(item.vodName);
        }
        if (names.isEmpty()) {
            stripStatus.setText(R.string.profile_no_updates);
        } else {
            String shown = names.size() > 2
                    ? names.get(0) + "、" + names.get(1)
                    : TextUtils.join("、", names);
            stripStatus.setText(getString(R.string.profile_day_airings, names.size(), shown));
        }
    }

    private boolean hasAiringOn(ProfileStats stats, LocalDate date) {
        for (Following item : stats.upcomingThisMonth) {
            if (toDate(item.nextAirAt).equals(date)) return true;
        }
        return false;
    }

    private static LocalDate toDate(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    // ---------- 追剧行（无卡片） ----------

    private View buildTrackingSection(ProfileStats stats) {
        Context context = requireContext();
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(16), dp(4), dp(16), dp(4));
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(0, 0, 0, dp(12));
        section.setLayoutParams(sectionLp);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(getString(R.string.profile_tracking_title, stats.followingCount));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(secondaryColor());
        chevron.setPadding(dp(4), 0, 0, 0);
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        TextView more = new TextView(context);
        more.setText("…");
        more.setTextSize(20);
        more.setTextColor(secondaryColor());
        titleRow.addView(title);
        titleRow.addView(chevron);
        titleRow.addView(spacer);
        titleRow.addView(more);
        section.addView(titleRow);

        TextView status = new TextView(context);
        status.setTextSize(14);
        status.setTextColor(secondaryColor());
        status.setPadding(0, dp(4), 0, 0);
        if (stats.followingCount == 0) {
            status.setText(R.string.profile_tracking_empty);
        } else if (stats.nextAiring != null) {
            String airLine = FollowingAgenda.airLineText(context, stats.nextAiring);
            status.setText(airLine != null
                    ? getString(R.string.profile_tracking_next, stats.nextAiring.vodName, airLine)
                    : getString(R.string.profile_tracking_waiting, stats.unwatchedTotal));
        } else if (stats.unwatchedTotal > 0) {
            status.setText(getString(R.string.profile_tracking_waiting, stats.unwatchedTotal));
        } else {
            status.setText(R.string.profile_tracking_no_next);
        }
        section.addView(status);
        section.setOnClickListener(v -> FollowingActivity.start(requireActivity()));
        return section;
    }

    // ---------- 总览：今天 / 本周 / 本月三页横滑 ----------

    private void fillOverview(LinearLayout content, ProfileStats stats) {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate sunday = monday.plusDays(6);
        List<View> pages = new ArrayList<>();
        pages.add(buildStatPage(getString(R.string.profile_today),
                FollowingAgenda.shortDate(toMillis(today)),
                stats.todayHours, stats.todayMovies, stats.todaySeries,
                stats.todayHourly, hourLabels()));
        pages.add(buildStatPage(getString(R.string.profile_this_week),
                FollowingAgenda.shortDate(toMillis(monday)) + "–" + FollowingAgenda.shortDate(toMillis(sunday)),
                stats.weekHours, stats.weekMovies, stats.weekSeries,
                stats.weekDaily, weekdayLabels()));
        pages.add(buildStatPage(getString(R.string.profile_this_month),
                monthName(today),
                stats.monthHours, stats.monthMovies, stats.monthSeries,
                mergeDaily(stats.monthDailySeries, stats.monthDailyMovies), monthLabels(today.lengthOfMonth())));

        ViewPager2 pager = new ViewPager2(requireContext());
        pager.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        pager.setAdapter(new PagerAdapter(pages));
        content.addView(pager);

        LinearLayout dots = new LinearLayout(requireContext());
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(8), 0, 0);
        List<View> dotViews = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            if (i > 0) lp.setMarginStart(dp(6));
            dot.setLayoutParams(lp);
            dots.addView(dot);
            dotViews.add(dot);
        }
        content.addView(dots);
        updateDots(dotViews, 0);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(dotViews, position);
            }
        });
    }

    private void updateDots(List<View> dots, int selected) {
        for (int i = 0; i < dots.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(i == selected ? primaryColor() : outlineColor());
            dots.get(i).setBackground(bg);
        }
    }

    private View buildStatPage(String title, String dateLabel, double hours, int movies, int series,
                               double[] values, String[] labels) {
        Context context = requireContext();
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        TextView dateView = new TextView(context);
        dateView.setText(dateLabel);
        dateView.setTextSize(13);
        dateView.setTextColor(secondaryColor());
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        titleRow.addView(titleView);
        titleRow.addView(spacer);
        titleRow.addView(dateView);
        page.addView(titleRow);
        TextView hoursView = new TextView(context);
        hoursView.setText(getString(R.string.profile_hours, hours));
        hoursView.setTextSize(22);
        hoursView.setTypeface(null, Typeface.BOLD);
        hoursView.setPadding(0, dp(8), 0, 0);
        page.addView(hoursView);
        TextView counts = new TextView(context);
        counts.setText(getString(R.string.profile_movies_count, movies) + "  "
                + getString(R.string.profile_series_count, series));
        counts.setTextSize(13);
        counts.setTextColor(secondaryColor());
        counts.setPadding(0, dp(2), 0, 0);
        page.addView(counts);
        ProfileDotsChartView chart = new ProfileDotsChartView(context);
        chart.setData(values, labels);
        chart.setPadding(0, dp(8), 0, 0);
        page.addView(chart);
        return page;
    }

    private static String[] hourLabels() {
        String[] labels = new String[24];
        for (int i = 0; i < labels.length; i++) labels[i] = "";
        labels[0] = "00:00";
        labels[12] = "12:00";
        labels[23] = "24:00";
        return labels;
    }

    private static String[] weekdayLabels() {
        String[] labels = new String[7];
        java.time.DayOfWeek[] days = {
                java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY,
                java.time.DayOfWeek.SUNDAY};
        for (int i = 0; i < days.length; i++) {
            labels[i] = days[i].getDisplayName(TextStyle.SHORT, Locale.getDefault());
        }
        return labels;
    }

    private static String[] monthLabels(int days) {
        String[] labels = new String[days];
        for (int i = 0; i < labels.length; i++) labels[i] = "";
        int[] marks = {1, 8, 15, 22, days};
        for (int day : marks) {
            if (day >= 1 && day <= days) labels[day - 1] = String.valueOf(day);
        }
        return labels;
    }

    private static double[] mergeDaily(double[] series, double[] movies) {
        int n = Math.max(series.length, movies.length);
        double[] merged = new double[n];
        for (int i = 0; i < n; i++) {
            double s = i < series.length ? series[i] : 0;
            double m = i < movies.length ? movies[i] : 0;
            merged[i] = s + m;
        }
        return merged;
    }

    private static long toMillis(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String monthName(LocalDate date) {
        if (Locale.getDefault().getLanguage().equals("zh")) {
            return date.getMonthValue() + "月";
        }
        return date.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    /** 三页预建视图的简单适配器：每页固定一个 holder，不复用。 */
    private static class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.Holder> {
        private final List<View> pages;

        PagerAdapter(List<View> pages) {
            this.pages = pages;
        }

        static class Holder extends RecyclerView.ViewHolder {
            Holder(View view) {
                super(view);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(pages.get(viewType));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }
    }

    // ---------- 收藏 / 在看双 tile ----------

    private View buildTileRow(ProfileStats stats, List<ProfileCard> cards) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(rowLp);
        boolean first = true;
        if (cards.contains(ProfileCard.FAVORITES)) {
            MaterialCardView tile = buildTile(String.valueOf(stats.keepCount),
                    getString(R.string.profile_card_favorites),
                    () -> KeepActivity.start(requireActivity()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(120), 1f);
            if (!first) lp.setMarginStart(dp(12));
            tile.setLayoutParams(lp);
            row.addView(tile);
            first = false;
        }
        if (cards.contains(ProfileCard.WATCHING)) {
            MaterialCardView tile = buildTile(String.valueOf(stats.watching.size()),
                    getString(R.string.profile_card_watching),
                    () -> HistoryActivity.start(requireActivity()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(120), 1f);
            if (!first) lp.setMarginStart(dp(12));
            tile.setLayoutParams(lp);
            row.addView(tile);
        }
        return row;
    }

    private MaterialCardView buildTile(String number, String label, Runnable target) {
        Context context = requireContext();
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(containerHighestColor());
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setStrokeColor(getResources().getColor(R.color.setting_card_divider, null));
        card.setStrokeWidth(dp(1));
        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(12), dp(16), dp(16));
        TextView more = new TextView(context);
        more.setText("…");
        more.setTextSize(20);
        more.setTextColor(secondaryColor());
        more.setGravity(Gravity.END);
        inner.addView(more);
        TextView numberView = new TextView(context);
        numberView.setText(number);
        numberView.setTextSize(24);
        numberView.setTypeface(null, Typeface.BOLD);
        inner.addView(numberView);
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(secondaryColor());
        labelView.setPadding(0, dp(2), 0, 0);
        inner.addView(labelView);
        card.addView(inner);
        card.setOnClickListener(v -> target.run());
        return card;
    }

    // ---------- 本月观看 ----------

    private void fillMonth(LinearLayout content, ProfileStats stats, List<ProfileCard> cards) {
        Context context = requireContext();
        LocalDate today = LocalDate.now();
        TextView monthLabel = new TextView(context);
        monthLabel.setText(monthName(today));
        monthLabel.setTextSize(13);
        monthLabel.setTextColor(secondaryColor());
        content.addView(monthLabel);
        TextView hours = new TextView(context);
        hours.setText(getString(R.string.profile_hours, stats.monthHours));
        hours.setTextSize(22);
        hours.setTypeface(null, Typeface.BOLD);
        hours.setPadding(0, dp(4), 0, 0);
        content.addView(hours);
        double delta = stats.monthHours - stats.lastMonthHours;
        TextView deltaView = new TextView(context);
        deltaView.setText(getString(delta >= 0
                ? R.string.profile_month_delta_up : R.string.profile_month_delta_down, Math.abs(delta)));
        deltaView.setTextSize(13);
        deltaView.setTextColor(delta >= 0
                ? Color.parseColor("#FF4CAF50") : Color.parseColor("#FFF44336"));
        deltaView.setPadding(0, dp(2), 0, 0);
        content.addView(deltaView);
        LinearLayout legend = new LinearLayout(context);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, dp(8), 0, 0);
        legend.addView(dotView(ProfileMonthChartView.SERIES_COLOR));
        legend.addView(legendLabel(R.string.profile_legend_series));
        legend.addView(dotView(ProfileMonthChartView.MOVIES_COLOR));
        legend.addView(legendLabel(R.string.profile_legend_movies));
        content.addView(legend);
        ProfileMonthChartView chart = new ProfileMonthChartView(context);
        chart.setData(stats.monthDailySeries, stats.monthDailyMovies);
        chart.setPadding(0, dp(4), 0, 0);
        content.addView(chart);
        if (cards.contains(ProfileCard.TREND1) || cards.contains(ProfileCard.TREND2)) {
            TextView topTitle = new TextView(context);
            topTitle.setText(R.string.profile_month_top3);
            topTitle.setTextSize(14);
            topTitle.setTypeface(null, Typeface.BOLD);
            topTitle.setPadding(0, dp(12), 0, dp(4));
            content.addView(topTitle);
            if (stats.monthTop3.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText(R.string.profile_month_no_record);
                empty.setTextSize(14);
                empty.setTextColor(secondaryColor());
                content.addView(empty);
            } else {
                int rank = 0;
                for (ProfileStats.NameCount entry : stats.monthTop3) {
                    addLine(content, getString(R.string.profile_trend_row, ++rank, entry.name, entry.count));
                }
            }
        }
    }

    private View dotView(int color) {
        View dot = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dot.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        dot.setBackground(bg);
        return dot;
    }

    private TextView legendLabel(int res) {
        TextView label = new TextView(requireContext());
        label.setText(res);
        label.setTextSize(13);
        label.setTextColor(secondaryColor());
        label.setPadding(dp(6), 0, dp(16), 0);
        return label;
    }

    // ---------- 类型分布 ----------

    private void fillGenres(LinearLayout content, ProfileStats stats) {
        addLine(content, getString(R.string.profile_genres_summary, stats.movieCount, stats.seriesCount));
        if (stats.topGenres.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.profile_genres_no_data);
            empty.setTextSize(14);
            empty.setTextColor(secondaryColor());
            empty.setPadding(0, dp(8), 0, 0);
            content.addView(empty);
            return;
        }
        int max = 0;
        for (ProfileStats.GenreCount genre : stats.topGenres) max = Math.max(max, genre.count);
        for (ProfileStats.GenreCount genre : stats.topGenres) {
            content.addView(buildGenreRow(genre, max, stats.genreTotal));
        }
    }

    private View buildGenreRow(ProfileStats.GenreCount genre, int max, int total) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView name = new TextView(context);
        name.setText(genre.name);
        name.setTextSize(14);
        name.setLayoutParams(new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(name);
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(8), 1f);
        barLp.setMarginStart(dp(8));
        bar.setLayoutParams(barLp);
        View fill = new View(context);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, genre.count));
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(primaryColor());
        fillBg.setCornerRadius(dp(4));
        fill.setBackground(fillBg);
        bar.addView(fill);
        if (genre.count < max) {
            View rest = new View(context);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, max - genre.count));
            bar.addView(rest);
        }
        row.addView(bar);
        int percent = total > 0 ? (int) Math.round(genre.count * 100.0 / total) : 0;
        TextView value = new TextView(context);
        value.setText(getString(R.string.profile_genres_bar, genre.count, percent));
        value.setTextSize(13);
        value.setTextColor(secondaryColor());
        value.setPadding(dp(8), 0, 0, 0);
        row.addView(value);
        return row;
    }

    // ---------- 小工具 ----------

    private void addLine(LinearLayout parent, String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(14);
        parent.addView(view);
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

    private int containerHighestColor() {
        return resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.parseColor("#FFE1E2E9"));
    }

    private int outlineColor() {
        return resolveAttr(com.google.android.material.R.attr.colorOutline, Color.parseColor("#FFBDBDBD"));
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
}
