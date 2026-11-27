package com.github.aiyingshi.osc.tv.ui.fragment;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.widget.ViewPager2;

import com.github.catvod.utils.Prefers;
import com.github.aiyingshi.osc.tv.App;
import com.github.aiyingshi.osc.tv.R;
import com.github.aiyingshi.osc.tv.bean.AiConfig;
import com.github.aiyingshi.osc.tv.bean.History;
import com.github.aiyingshi.osc.tv.databinding.FragmentProfileBinding;
import com.github.aiyingshi.osc.tv.databinding.ItemProfileCardBinding;
import com.github.aiyingshi.osc.tv.following.Following;
import com.github.aiyingshi.osc.tv.following.FollowingAgenda;
import com.github.aiyingshi.osc.tv.profile.ProfileCard;
import com.github.aiyingshi.osc.tv.profile.ProfileLayout;
import com.github.aiyingshi.osc.tv.profile.ProfileStats;
import com.github.aiyingshi.osc.tv.setting.Setting;
import com.github.aiyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.aiyingshi.osc.tv.ui.activity.FollowingActivity;
import com.github.aiyingshi.osc.tv.ui.activity.HistoryActivity;
import com.github.aiyingshi.osc.tv.ui.activity.HistoryResumeCoordinator;
import com.github.aiyingshi.osc.tv.ui.activity.KeepActivity;
import com.github.aiyingshi.osc.tv.ui.activity.ViewingReportActivity;
import com.github.aiyingshi.osc.tv.utils.Notify;
import com.github.aiyingshi.osc.tv.viewing.ViewingReportRange;
import com.github.aiyingshi.osc.tv.ui.base.BaseFragment;
import com.github.aiyingshi.osc.tv.ui.custom.ProfileBarsChartView;
import com.github.aiyingshi.osc.tv.ui.custom.ProfileContributionView;
import com.github.aiyingshi.osc.tv.ui.custom.ProfileDotsChartView;
import com.github.aiyingshi.osc.tv.ui.custom.ProfileLineChartView;
import com.github.aiyingshi.osc.tv.ui.custom.ProfileMonthChartView;
import com.github.aiyingshi.osc.tv.ui.dialog.ProfileActivitySheetDialog;
import com.github.aiyingshi.osc.tv.ui.dialog.ProfileCalendarSheetDialog;
import com.github.aiyingshi.osc.tv.ui.dialog.ProfileEditorDialog;
import com.github.aiyingshi.osc.tv.utils.ImgUtil;
import com.github.aiyingshi.osc.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 我的页：按 Arcane 截图与两段录屏 1:1 复刻的观影数据页。
 * 版式：页头“我的”+日历图标 → 7 天日期条 → 历史观看格 → 追剧行 →
 * 今天三页横滑（今天/本周观看/最近7天，圆点在卡外）→ 收藏/在看/分类走势 tile（按卡片顺序双列排，
 * 整卡插中间会断行）→ 本月观看（堆叠柱状图+刷剧 TOP3）→ 类型分布 → 管理模块。
 * 模块顺序与显隐仍走 ProfileLayout；在看空时不显示，在看 tile 可切海报/方块两种形态。
 * 数据一次后台取完（ProfileStats.load）。
 */
public class ProfileFragment extends BaseFragment {

    /** 类型分布堆叠条的配色（第 1 个题材紫，与 Arcane 一致；其余为推测）。 */
    private static final int[] GENRE_COLORS = {
            Color.parseColor("#FF6366F1"), Color.parseColor("#FFF59E0B"), Color.parseColor("#FF10B981")};

    private enum TileMode {
        SQUARE, POSTER
    }

    private FragmentProfileBinding mBinding;
    private ProfileStats currentStats;

    /** 日期条选中日（默认今天）与条内控件，点选后只刷新条本身，不重载数据。 */
    private LocalDate selectedDate;
    private LocalDate stripWeekStart;
    private List<LocalDate> stripDates;
    private List<View> stripCells;
    private TextView stripStatus;
    private View calendarSectionView;
    private int calendarSectionIndex = -1;

    public static ProfileFragment newInstance() {
        return new ProfileFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentProfileBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.calendarBtn.setOnClickListener(v -> ProfileCalendarSheetDialog.create(
                selectedDate == null ? LocalDate.now() : selectedDate,
                day -> {
                    stripWeekStart = day.minusDays(day.getDayOfWeek().getValue() % 7);
                    selectedDate = day;
                    rebuildCalendarSection();
                }).show(getChildFragmentManager()));
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
        currentStats = stats;
        mBinding.cardContainer.removeAllViews();
        mBinding.emptyHint.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        calendarSectionIndex = -1;
        calendarSectionView = null;
        LinearLayout pendingRow = null;
        int pendingCount = 0;
        for (ProfileCard card : cards) {
            // 在看海报形态是整宽区块，不进双列 tile 行。
            if (card == ProfileCard.WATCHING && tileMode(card) == TileMode.POSTER && !stats.watching.isEmpty()) {
                if (pendingRow != null) {
                    mBinding.cardContainer.addView(pendingRow);
                    pendingRow = null;
                    pendingCount = 0;
                }
                mBinding.cardContainer.addView(buildWatchingPosterSection(stats));
                continue;
            }
            if (isTile(card)) {
                View tile = buildTile(card, stats);
                if (tile == null) continue;
                if (pendingRow == null) {
                    pendingRow = newTileRow();
                    pendingCount = 0;
                }
                pendingRow.addView(tile, tileLayoutParams(pendingCount > 0));
                pendingCount++;
                if (pendingCount == 2) {
                    mBinding.cardContainer.addView(pendingRow);
                    pendingRow = null;
                    pendingCount = 0;
                }
                continue;
            }
            if (pendingRow != null) {
                mBinding.cardContainer.addView(pendingRow);
                pendingRow = null;
                pendingCount = 0;
            }
            switch (card) {
                case CALENDAR:
                    calendarSectionIndex = mBinding.cardContainer.getChildCount();
                    calendarSectionView = buildCalendarSection(stats);
                    mBinding.cardContainer.addView(calendarSectionView);
                    break;
                case TRACKING:
                    mBinding.cardContainer.addView(buildTrackingSection(stats));
                    break;
                case OVERVIEW: {
                    ItemProfileCardBinding cardBinding = ItemProfileCardBinding.inflate(inflater, mBinding.cardContainer, false);
                    cardBinding.cardTitle.setText(card.titleRes);
                    cardBinding.cardHeader.setVisibility(View.GONE);
                    ViewPager2 pager = fillOverview(cardBinding.cardContent, stats);
                    addAiReportButton(cardBinding.cardContent, stats);
                    mBinding.cardContainer.addView(cardBinding.getRoot());
                    mBinding.cardContainer.addView(buildOverviewDots(pager));
                    break;
                }
                default: {
                    ItemProfileCardBinding cardBinding = ItemProfileCardBinding.inflate(inflater, mBinding.cardContainer, false);
                    cardBinding.cardTitle.setText(card.titleRes);
                    fillCard(card, cardBinding, stats, cards);
                    mBinding.cardContainer.addView(cardBinding.getRoot());
                    break;
                }
            }
        }
        if (pendingRow != null) mBinding.cardContainer.addView(pendingRow);
    }

    /** 只重建日期条那一段（日历面板点日跳周用），不重载数据。 */
    private void rebuildCalendarSection() {
        if (calendarSectionView == null || calendarSectionIndex < 0 || currentStats == null) return;
        View section = buildCalendarSection(currentStats);
        mBinding.cardContainer.removeViewAt(calendarSectionIndex);
        mBinding.cardContainer.addView(section, calendarSectionIndex);
        calendarSectionView = section;
    }

    private static boolean isTile(ProfileCard card) {
        return card == ProfileCard.FAVORITES || card == ProfileCard.WATCHING
                || card == ProfileCard.TREND1 || card == ProfileCard.TREND2;
    }

    private LinearLayout newTileRow() {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(rowLp);
        return row;
    }

    /** tile 行里两个 tile 等高：高用 MATCH_PARENT，横向 LinearLayout 会把矮的拉伸到行高（行高=最高的 tile），底边对齐。 */
    private LinearLayout.LayoutParams tileLayoutParams(boolean hasStartMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (hasStartMargin) lp.setMarginStart(dp(12));
        return lp;
    }

    /** tile 卡片：在看空时不显示，分类走势没数据时不显示。 */
    private View buildTile(ProfileCard card, ProfileStats stats) {
        switch (card) {
            case FAVORITES:
                return buildFavoritesTile(stats);
            case WATCHING:
                if (stats.watching.isEmpty()) return null;
                return buildWatchingTile(stats);
            case TREND1:
                if (stats.genreTrends.isEmpty()) return null;
                return buildTrendTile(stats.genreTrends.get(0));
            case TREND2:
                if (stats.genreTrends.size() < 2) return null;
                return buildTrendTile(stats.genreTrends.get(1));
            default:
                return null;
        }
    }

    private void fillCard(ProfileCard card, ItemProfileCardBinding binding, ProfileStats stats, List<ProfileCard> cards) {
        LinearLayout content = binding.cardContent;
        switch (card) {
            case HISTORY:
                navigate(binding, () -> HistoryActivity.start(requireActivity()));
                ProfileContributionView grid = new ProfileContributionView(requireContext());
                grid.setData(stats.dailyHours);
                grid.setOnClickListener(v -> openActivitySheet(
                        getString(R.string.profile_activity_title), daysAgoStart(69), tomorrowStart()));
                content.addView(grid);
                TextView openHistory = new TextView(requireContext());
                openHistory.setText(getString(R.string.profile_open_history) + " ›");
                openHistory.setTextSize(14);
                openHistory.setTextColor(secondaryColor());
                openHistory.setPadding(0, dp(10), 0, 0);
                openHistory.setOnClickListener(v -> HistoryActivity.start(requireActivity()));
                content.addView(openHistory);
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

    private void openActivitySheet(String title, long startMillis, long endMillis) {
        if (currentStats == null || currentStats.allHistory == null) return;
        ProfileActivitySheetDialog.create(title, startMillis, endMillis, currentStats.allHistory, this::load)
                .show(getChildFragmentManager());
    }

    private static long daysAgoStart(int daysAgo) {
        return LocalDate.now().minusDays(daysAgo).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long tomorrowStart() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // ---------- 日历：7 天日期条 ----------

    private View buildCalendarSection(ProfileStats stats) {
        Context context = requireContext();
        LocalDate today = LocalDate.now();
        if (stripWeekStart == null) {
            stripWeekStart = today.minusDays(today.getDayOfWeek().getValue() % 7);
            selectedDate = today;
        }
        stripDates = new ArrayList<>();
        for (int i = 0; i < 7; i++) stripDates.add(stripWeekStart.plusDays(i));

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
            TextView weekday = (TextView) cell.getChildAt(1);
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
            dayNum.setTextColor(isToday ? primaryColor() : onSurfaceColor());
            weekday.setTextColor(isToday ? primaryColor() : secondaryColor());
        }
    }

    private void refreshStripStatus() {
        if (stripStatus == null || selectedDate == null || currentStats == null) return;
        List<String> names = new ArrayList<>();
        for (Following item : currentStats.upcomingThisMonth) {
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

    // ---------- 总览：今天 / 本周观看 / 最近7天三页横滑 ----------

    private ViewPager2 fillOverview(LinearLayout content, ProfileStats stats) {
        LocalDate today = LocalDate.now();
        LocalDate sunday = today.minusDays(today.getDayOfWeek().getValue() % 7);
        LocalDate saturday = sunday.plusDays(6);
        List<View> pages = new ArrayList<>();
        pages.add(buildTodayPage(stats, today));
        pages.add(buildWeekPage(stats, sunday, saturday));
        pages.add(buildLast7Page(stats));

        ViewPager2 pager = new ViewPager2(requireContext());
        pager.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        pager.setAdapter(new PagerAdapter(pages));
        content.addView(pager);
        return pager;
    }

    /**
     * “观看概览”卡片内的“生成 AI 报告”按钮（P0 功能 3）。
     * 功能开关关着时不显示；点按后先弹确认框，列出将发送的数据摘要（只发聚合统计，
     * 不含具体哪天几点看了哪集），确认后进已有的 ViewingReportActivity 生成报告。
     */
    private void addAiReportButton(LinearLayout content, ProfileStats stats) {
        if (!Setting.isAiViewingReport()) return;
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(R.string.ai_report_generate);
        button.setOnClickListener(v -> onGenerateAiReport(stats));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        content.addView(button, params);
    }

    private void onGenerateAiReport(ProfileStats stats) {
        if (!Setting.isAiViewingReport()) return;
        if (!AiConfig.objectFrom(Setting.getAiConfig()).isReady()) {
            Notify.show(R.string.ai_report_not_ready);
            return;
        }
        new WebHtvAlertDialogBuilder(requireContext())
                .setTitle(R.string.ai_report_confirm_title)
                .setMessage(getString(R.string.ai_report_confirm_message, buildAiReportSummary(stats)))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) ->
                        ViewingReportActivity.start(requireActivity(), ViewingReportRange.ALL))
                .show();
    }

    /** 确认框里的数据摘要：只含合计与分布，不含任何单条观看记录的时间与集数。 */
    private String buildAiReportSummary(ProfileStats stats) {
        if (stats == null) return "";
        StringBuilder summary = new StringBuilder();
        summary.append(String.format(Locale.getDefault(), "本月 %.1f 小时 · 电影 %d 部 · 剧集 %d 部 · 历史记录 %d 条",
                stats.monthHours, stats.monthMovies, stats.monthSeries, stats.historyCount));
        if (stats.genreHours != null && !stats.genreHours.isEmpty()) {
            List<String> top = new ArrayList<>();
            for (int i = 0; i < Math.min(3, stats.genreHours.size()); i++) {
                if (stats.genreHours.get(i) != null && !TextUtils.isEmpty(stats.genreHours.get(i).name)) {
                    top.add(stats.genreHours.get(i).name);
                }
            }
            if (!top.isEmpty()) summary.append("\n题材前三：").append(TextUtils.join("、", top));
        }
        return summary.toString();
    }

    /** 圆点指示器在卡片外面、卡片下方；选中是深色，未选中是描边灰。 */
    private View buildOverviewDots(ViewPager2 pager) {
        Context context = requireContext();
        LinearLayout dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(12));
        dots.setLayoutParams(lp);
        List<View> dotViews = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            if (i > 0) dotLp.setMarginStart(dp(6));
            dot.setLayoutParams(dotLp);
            dots.addView(dot);
            dotViews.add(dot);
        }
        updateDots(dotViews, 0);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(dotViews, position);
            }
        });
        return dots;
    }

    private void updateDots(List<View> dots, int selected) {
        for (int i = 0; i < dots.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(i == selected ? onSurfaceColor() : outlineColor());
            dots.get(i).setBackground(bg);
        }
    }

    private View buildTodayPage(ProfileStats stats, LocalDate today) {
        LinearLayout page = newStatPage();
        page.addView(statTitleRow(getString(R.string.profile_today),
                FollowingAgenda.shortDate(toMillis(today))));
        page.addView(bigNumberLine(String.format(Locale.US, "%.2f", stats.todayHours), R.string.profile_hours_unit));
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        row.addView(countsColumn(stats.todayMovies, stats.todaySeries));
        ProfileDotsChartView chart = new ProfileDotsChartView(requireContext());
        chart.setData(stats.todayHourly, hourLabels());
        chart.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chart);
        page.addView(row);
        long start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String sheetTitle = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        page.setOnClickListener(v -> openActivitySheet(sheetTitle, start, end));
        return page;
    }

    private View buildWeekPage(ProfileStats stats, LocalDate sunday, LocalDate saturday) {
        LinearLayout page = newStatPage();
        String range = weekRangeLabel(sunday, saturday);
        page.addView(statTitleRow(getString(R.string.profile_week_watch), range));
        page.addView(bigNumberLine(String.format(Locale.US, "%.2f", stats.weekHoursSun), R.string.profile_hours_unit));
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        row.addView(countsColumn(stats.weekMoviesSun, stats.weekSeriesSun));
        ProfileBarsChartView chart = new ProfileBarsChartView(requireContext());
        chart.setData(stats.weekDailySun, sunWeekdayLabels());
        chart.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chart);
        page.addView(row);
        long start = sunday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = saturday.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        page.setOnClickListener(v -> openActivitySheet(range, start, end));
        return page;
    }

    private View buildLast7Page(ProfileStats stats) {
        LinearLayout page = newStatPage();
        page.addView(statTitleRow(getString(R.string.profile_last_7_days), null));
        page.addView(bigNumberLine(String.format(Locale.US, "%.2f", stats.last7Hours), R.string.profile_hours_unit));
        double delta = stats.last7Hours - stats.prev7Hours;
        TextView deltaView = new TextView(requireContext());
        deltaView.setText(getString(delta >= 0
                ? R.string.profile_month_delta_up : R.string.profile_month_delta_down, Math.abs(delta)));
        deltaView.setTextSize(13);
        deltaView.setTextColor(delta >= 0
                ? Color.parseColor("#FF4CAF50") : Color.parseColor("#FFF44336"));
        deltaView.setPadding(0, dp(2), 0, dp(4));
        page.addView(deltaView);
        ProfileLineChartView chart = new ProfileLineChartView(requireContext());
        chart.setData(stats.last7Daily);
        page.addView(chart);
        long start = daysAgoStart(6);
        page.setOnClickListener(v -> openActivitySheet(
                getString(R.string.profile_last_7_days), start, tomorrowStart()));
        return page;
    }

    private LinearLayout newStatPage() {
        Context context = requireContext();
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        // ViewPager2 要求每页填满整个 pager（宽高都得是 match_parent），否则进页就抛
        // IllegalStateException: Pages must fill the whole ViewPager2
        page.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return page;
    }

    private View statTitleRow(String title, @Nullable String dateLabel) {
        Context context = requireContext();
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        titleRow.addView(titleView);
        if (dateLabel != null) {
            View spacer = new View(context);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
            titleRow.addView(spacer);
            TextView dateView = new TextView(context);
            dateView.setText(dateLabel);
            dateView.setTextSize(13);
            dateView.setTextColor(secondaryColor());
            titleRow.addView(dateView);
        }
        return titleRow;
    }

    /** 左侧两行计数：N 部电影 / N 集剧，数字大、单位小。 */
    private View countsColumn(int movies, int series) {
        Context context = requireContext();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginEnd(dp(12));
        column.setLayoutParams(lp);
        column.addView(countLine(movies, R.string.profile_movies_unit));
        column.addView(countLine(series, R.string.profile_series_unit));
        return column;
    }

    private TextView countLine(int number, int unitRes) {
        TextView view = new TextView(requireContext());
        SpannableStringBuilder sb = new SpannableStringBuilder(String.valueOf(number));
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(1.55f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = sb.length();
        sb.append(" ").append(getString(unitRes));
        sb.setSpan(new ForegroundColorSpan(secondaryColor()), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(sb);
        view.setTextSize(13);
        view.setTextColor(onSurfaceColor());
        return view;
    }

    /** 大数字 + 小单位（“0.00 小时”）：数字 22sp 粗体，单位 13sp 灰字。 */
    private TextView bigNumberLine(String number, int unitRes) {
        TextView view = new TextView(requireContext());
        SpannableStringBuilder sb = new SpannableStringBuilder(number);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(1.6f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = sb.length();
        sb.append(" ").append(getString(unitRes));
        sb.setSpan(new ForegroundColorSpan(secondaryColor()), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.9f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(sb);
        view.setTextSize(14);
        view.setTextColor(onSurfaceColor());
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

    private static String weekRangeLabel(LocalDate sunday, LocalDate saturday) {
        String start = sunday.getMonthValue() + "/" + sunday.getDayOfMonth();
        String end = saturday.getMonthValue() == sunday.getMonthValue()
                ? String.valueOf(saturday.getDayOfMonth())
                : saturday.getMonthValue() + "/" + saturday.getDayOfMonth();
        return start + " – " + end;
    }

    private static String[] hourLabels() {
        String[] labels = new String[24];
        for (int i = 0; i < labels.length; i++) labels[i] = "";
        labels[0] = "00:00";
        labels[12] = "12:00";
        labels[23] = "24:00";
        return labels;
    }

    private static String[] sunWeekdayLabels() {
        String[] labels = new String[7];
        java.time.DayOfWeek[] days = {
                java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY,
                java.time.DayOfWeek.SATURDAY};
        for (int i = 0; i < days.length; i++) labels[i] = "";
        labels[0] = days[0].getDisplayName(TextStyle.SHORT, Locale.getDefault());
        labels[3] = days[3].getDisplayName(TextStyle.SHORT, Locale.getDefault());
        labels[6] = days[6].getDisplayName(TextStyle.SHORT, Locale.getDefault());
        return labels;
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

    // ---------- 收藏 / 在看 / 分类走势 tile ----------

    private MaterialCardView buildFavoritesTile(ProfileStats stats) {
        MaterialCardView tile = newTileCard(dp(120));
        LinearLayout inner = tileInner();
        inner.addView(tileMore(null));
        inner.addView(tileNumber(String.valueOf(stats.keepCount)));
        inner.addView(tileLabel(getString(R.string.profile_card_favorites)));
        tile.addView(inner);
        tile.setOnClickListener(v -> KeepActivity.start(requireActivity()));
        return tile;
    }

    private MaterialCardView buildWatchingTile(ProfileStats stats) {
        MaterialCardView tile = newTileCard(dp(150));
        LinearLayout inner = tileInner();
        TextView more = tileMore(null);
        inner.addView(more);
        inner.addView(tileNumber(String.valueOf(stats.watching.size())));
        inner.addView(tileLabel(getString(R.string.profile_card_watching)));
        inner.addView(watchingFan(stats));
        tile.addView(inner);
        more.setOnClickListener(v -> showTileModeMenu(v, ProfileCard.WATCHING));
        tile.setOnClickListener(v -> HistoryActivity.start(requireActivity()));
        return tile;
    }

    /** 在看 tile 底部：最多 3 张海报交叠的小扇形。 */
    private View watchingFan(ProfileStats stats) {
        Context context = requireContext();
        LinearLayout fan = new LinearLayout(context);
        fan.setOrientation(LinearLayout.HORIZONTAL);
        fan.setPadding(0, dp(8), 0, 0);
        int count = Math.min(3, stats.watching.size());
        for (int i = 0; i < count; i++) {
            History item = stats.watching.get(i);
            ImageView poster = new ImageView(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(45));
            if (i > 0) lp.setMarginStart(-dp(10));
            poster.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            bg.setColor(containerHighColor());
            poster.setBackground(bg);
            poster.setClipToOutline(true);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImgUtil.load(item.getVodName(), item.getVodPic(), poster);
            fan.addView(poster);
        }
        return fan;
    }

    private MaterialCardView buildTrendTile(ProfileStats.GenreTrend trend) {
        MaterialCardView tile = newTileCard(dp(158));
        LinearLayout inner = tileInner();
        TextView title = new TextView(requireContext());
        title.setText(trend.name);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        inner.addView(title);
        inner.addView(bigNumberLine(String.format(Locale.US, "%.2f", trend.last7Hours), R.string.profile_hours_unit));
        double delta = trend.last7Hours - trend.prev7Hours;
        TextView deltaView = new TextView(requireContext());
        deltaView.setText(getString(delta >= 0
                ? R.string.profile_month_delta_up : R.string.profile_month_delta_down, Math.abs(delta)));
        deltaView.setTextSize(13);
        deltaView.setTextColor(delta >= 0
                ? Color.parseColor("#FF4CAF50") : Color.parseColor("#FFF44336"));
        inner.addView(deltaView);
        ProfileLineChartView chart = new ProfileLineChartView(requireContext());
        chart.setData(trend.last7Daily);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        inner.addView(chart);
        tile.addView(inner);
        return tile;
    }

    /** 在看海报形态：整宽区块，头“在看 N ›”+ …，下面 3 列海报格。 */
    private View buildWatchingPosterSection(ProfileStats stats) {
        Context context = requireContext();
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(16), dp(4), dp(16), dp(4));
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(0, 0, 0, dp(12));
        section.setLayoutParams(sectionLp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(getString(R.string.profile_watching_poster_title, stats.watching.size()));
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
        header.addView(title);
        header.addView(chevron);
        header.addView(spacer);
        header.addView(more);
        header.setOnClickListener(v -> HistoryActivity.start(requireActivity()));
        more.setOnClickListener(v -> showTileModeMenu(v, ProfileCard.WATCHING));
        section.addView(header);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        float density = context.getResources().getDisplayMetrics().density;
        int columns = 3;
        int gap = (int) (12 * density);
        int cellWidth = (int) ((screenWidth - 32 * density - (columns - 1) * gap) / columns);
        int cellHeight = (int) (cellWidth * 1.5f);
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < stats.watching.size(); i++) {
            final History item = stats.watching.get(i);
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) cellLp.setMarginStart(gap);
            cell.setLayoutParams(cellLp);
            FrameLayout posterBox = new FrameLayout(context);
            posterBox.setLayoutParams(new LinearLayout.LayoutParams(cellWidth, cellHeight));
            ImageView poster = new ImageView(context);
            poster.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(containerHighColor());
            poster.setBackground(bg);
            poster.setClipToOutline(true);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImgUtil.load(item.getVodName(), item.getVodPic(), poster);
            posterBox.addView(poster);
            String remarks = item.getVodRemarks();
            if (!TextUtils.isEmpty(remarks)) {
                posterBox.addView(posterBadge(remarks, Gravity.BOTTOM | Gravity.START));
            }
            if (item.getDuration() > 0 && item.getPosition() > 0) {
                int percent = (int) (item.getPosition() * 100 / item.getDuration());
                posterBox.addView(posterBadge(percent + "%", Gravity.BOTTOM | Gravity.END));
            }
            posterBox.setOnClickListener(v -> HistoryResumeCoordinator.open(requireActivity(), item));
            cell.addView(posterBox);
            TextView name = new TextView(context);
            name.setText(item.getVodName());
            name.setTextSize(14);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(4), 0, 0);
            cell.addView(name);
            String year = item.getYear();
            if (!TextUtils.isEmpty(year)) {
                TextView yearView = new TextView(context);
                yearView.setText(year);
                yearView.setTextSize(13);
                yearView.setTextColor(secondaryColor());
                cell.addView(yearView);
            }
            grid.addView(cell);
        }
        section.addView(grid);
        return section;
    }

    /** 海报角标：半透明黑底白字。 */
    private TextView posterBadge(String text, int gravity) {
        Context context = requireContext();
        TextView badge = new TextView(context);
        badge.setText(text);
        badge.setTextSize(11);
        badge.setTextColor(Color.WHITE);
        badge.setMaxLines(1);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(Color.parseColor("#80000000"));
        badge.setBackground(bg);
        badge.setPadding(dp(4), dp(1), dp(4), dp(1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        badge.setLayoutParams(lp);
        return badge;
    }

    private MaterialCardView newTileCard(int heightDp) {
        Context context = requireContext();
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(containerHighestColor());
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setStrokeColor(getResources().getColor(R.color.setting_card_divider, null));
        card.setStrokeWidth(dp(1));
        // 行里用的 LayoutParams 会盖掉这里的高，用最小高度保底。
        card.setMinimumHeight(heightDp);
        return card;
    }

    private LinearLayout tileInner() {
        Context context = requireContext();
        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(12), dp(16), dp(16));
        return inner;
    }

    private TextView tileMore(@Nullable View.OnClickListener onClick) {
        TextView more = new TextView(requireContext());
        more.setText("…");
        more.setTextSize(20);
        more.setTextColor(secondaryColor());
        more.setGravity(Gravity.END);
        if (onClick != null) more.setOnClickListener(onClick);
        return more;
    }

    private TextView tileNumber(String number) {
        TextView numberView = new TextView(requireContext());
        numberView.setText(number);
        numberView.setTextSize(24);
        numberView.setTypeface(null, Typeface.BOLD);
        return numberView;
    }

    private TextView tileLabel(String label) {
        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(secondaryColor());
        labelView.setPadding(0, dp(2), 0, 0);
        return labelView;
    }

    private void showTileModeMenu(View anchor, ProfileCard card) {
        Context context = requireContext();
        PopupMenu popup = new PopupMenu(context, anchor);
        boolean poster = tileMode(card) == TileMode.POSTER;
        popup.getMenu().add(Menu.NONE, 1, Menu.NONE, getString(R.string.profile_tile_poster))
                .setCheckable(true).setChecked(poster);
        popup.getMenu().add(Menu.NONE, 2, Menu.NONE, getString(R.string.profile_tile_square))
                .setCheckable(true).setChecked(!poster);
        popup.setOnMenuItemClickListener(item -> {
            setTileMode(card, item.getItemId() == 1 ? TileMode.POSTER : TileMode.SQUARE);
            load();
            return true;
        });
        popup.show();
    }

    private TileMode tileMode(ProfileCard card) {
        String saved = Prefers.getString("profile_tile_mode_" + card.name(), "square");
        return "poster".equals(saved) ? TileMode.POSTER : TileMode.SQUARE;
    }

    private void setTileMode(ProfileCard card, TileMode mode) {
        Prefers.put("profile_tile_mode_" + card.name(), mode == TileMode.POSTER ? "poster" : "square");
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
        content.addView(bigNumberLine(String.format(Locale.US, "%.2f", stats.monthHours), R.string.profile_hours_unit));
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
                double max = 0;
                for (ProfileStats.NameCount entry : stats.monthTop3) max = Math.max(max, entry.hours);
                if (max <= 0) max = 1;
                for (ProfileStats.NameCount entry : stats.monthTop3) {
                    content.addView(buildTop3Row(entry, max));
                }
            }
        }
    }

    /** 刷剧 TOP3 行：片名 + 类型色条形 + 小时数。 */
    private View buildTop3Row(ProfileStats.NameCount entry, double maxHours) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView name = new TextView(context);
        name.setText(entry.name);
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(8), 2f);
        barLp.setMarginStart(dp(8));
        bar.setLayoutParams(barLp);
        View fill = new View(context);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(entry.hours, maxHours * 0.05)));
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(entry.movie ? ProfileMonthChartView.MOVIES_COLOR : ProfileMonthChartView.SERIES_COLOR);
        fillBg.setCornerRadius(dp(4));
        fill.setBackground(fillBg);
        bar.addView(fill);
        if (entry.hours < maxHours) {
            View rest = new View(context);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) (maxHours - Math.max(entry.hours, maxHours * 0.05))));
            bar.addView(rest);
        }
        row.addView(bar);
        TextView hours = new TextView(context);
        hours.setText(getString(R.string.profile_hours, entry.hours));
        hours.setTextSize(13);
        hours.setTextColor(secondaryColor());
        hours.setPadding(dp(8), 0, 0, 0);
        row.addView(hours);
        return row;
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
        Context context = requireContext();
        if (stats.genreHours.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.profile_genres_no_data);
            empty.setTextSize(14);
            empty.setTextColor(secondaryColor());
            empty.setPadding(0, dp(8), 0, 0);
            content.addView(empty);
            return;
        }
        // 全宽堆叠条：按题材小时分段。
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp(5));
        barBg.setColor(containerHighColor());
        bar.setBackground(barBg);
        bar.setClipToOutline(true);
        for (int i = 0; i < stats.genreHours.size(); i++) {
            ProfileStats.GenreHours genre = stats.genreHours.get(i);
            View segment = new View(context);
            segment.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) genre.hours));
            segment.setBackgroundColor(GENRE_COLORS[i % GENRE_COLORS.length]);
            bar.addView(segment);
        }
        content.addView(bar);
        content.addView(bigNumberLine(String.format(Locale.US, "%.2f", stats.genreHoursTotal), R.string.profile_hours_unit));
        addLine(content, getString(R.string.profile_genres_summary, stats.movieCount, stats.seriesCount));
        for (int i = 0; i < stats.genreHours.size(); i++) {
            ProfileStats.GenreHours genre = stats.genreHours.get(i);
            int percent = stats.genreHoursTotal > 0
                    ? (int) Math.round(genre.hours * 100.0 / stats.genreHoursTotal) : 0;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            row.addView(dotView(GENRE_COLORS[i % GENRE_COLORS.length]));
            TextView label = new TextView(context);
            label.setText(getString(R.string.profile_genres_legend, genre.name, percent));
            label.setTextSize(14);
            label.setPadding(dp(6), 0, 0, 0);
            row.addView(label);
            content.addView(row);
        }
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
