package com.github.moyingshi.osc.tv.ui.fragment;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.bean.Keep;
import com.github.moyingshi.osc.tv.databinding.FragmentProfileBinding;
import com.github.moyingshi.osc.tv.databinding.ItemProfileCardBinding;
import com.github.moyingshi.osc.tv.databinding.ItemProfilePosterBinding;
import com.github.moyingshi.osc.tv.following.Following;
import com.github.moyingshi.osc.tv.following.FollowingAgenda;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.profile.ProfileCard;
import com.github.moyingshi.osc.tv.profile.ProfileLayout;
import com.github.moyingshi.osc.tv.profile.ProfileStats;
import com.github.moyingshi.osc.tv.ui.activity.FollowingActivity;
import com.github.moyingshi.osc.tv.ui.activity.HistoryActivity;
import com.github.moyingshi.osc.tv.ui.activity.HistoryResumeCoordinator;
import com.github.moyingshi.osc.tv.ui.activity.KeepActivity;
import com.github.moyingshi.osc.tv.ui.activity.SearchActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.ui.dialog.ProfileEditorDialog;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.Task;

import java.time.LocalDate;
import java.util.List;

/**
 * 我的页：10 张观影数据卡片，纵向一列；顺序与显隐走 ProfileLayout，编辑走
 * ProfileEditorDialog。数据一次后台取完（ProfileStats.load），每张卡就算
 * 没数据也显示一句说明，绝不出现空卡。
 */
public class ProfileFragment extends BaseFragment {

    private FragmentProfileBinding mBinding;

    public static ProfileFragment newInstance() {
        return new ProfileFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentProfileBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.yearTitle.setText(String.valueOf(LocalDate.now().getYear()));
        mBinding.editBtn.setOnClickListener(v -> new ProfileEditorDialog(requireContext(), this::load).show());
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
        for (ProfileCard card : cards) {
            ItemProfileCardBinding cardBinding = ItemProfileCardBinding.inflate(inflater, mBinding.cardContainer, false);
            cardBinding.cardTitle.setText(card.titleRes);
            fillCard(card, cardBinding, stats);
            mBinding.cardContainer.addView(cardBinding.getRoot());
        }
    }

    private void fillCard(ProfileCard card, ItemProfileCardBinding binding, ProfileStats stats) {
        LinearLayout content = binding.cardContent;
        switch (card) {
            case CALENDAR:
                navigate(binding, () -> FollowingActivity.start(requireActivity()));
                if (stats.upcomingThisMonth.isEmpty()) {
                    addLine(content, getString(R.string.profile_calendar_empty));
                } else {
                    addLine(content, getString(R.string.profile_calendar_summary, stats.upcomingThisMonth.size()));
                    for (Following item : stats.upcomingThisMonth.subList(0, Math.min(3, stats.upcomingThisMonth.size()))) {
                        String airLine = FollowingAgenda.airLineText(requireContext(), item);
                        if (airLine != null) {
                            addLine(content, getString(R.string.profile_calendar_row, item.vodName, airLine));
                        }
                    }
                }
                break;
            case OVERVIEW:
                binding.cardMore.setVisibility(View.GONE);
                addOverview(content, stats);
                break;
            case HISTORY:
                navigate(binding, () -> HistoryActivity.start(requireActivity()));
                addPosterRow(content, stats.recentHistory, R.string.profile_history_empty,
                        item -> HistoryResumeCoordinator.open(requireActivity(), item),
                        item -> formatDate(item.getCreateTime()));
                break;
            case TRACKING:
                navigate(binding, () -> FollowingActivity.start(requireActivity()));
                addLine(content, getString(R.string.profile_tracking_summary,
                        stats.followingCount, stats.unwatchedTotal, stats.endedCount));
                if (stats.nextAiring != null) {
                    String airLine = FollowingAgenda.airLineText(requireContext(), stats.nextAiring);
                    if (airLine != null) addLine(content, getString(R.string.profile_tracking_next,
                            stats.nextAiring.vodName, airLine));
                } else {
                    addLine(content, getString(R.string.profile_tracking_no_next));
                }
                break;
            case FAVORITES:
                navigate(binding, () -> KeepActivity.start(requireActivity()));
                addPosterRow(content, stats.recentKeeps, R.string.profile_favorites_empty,
                        this::openKeep, item -> "");
                break;
            case WATCHING:
                navigate(binding, () -> HistoryActivity.start(requireActivity()));
                addPosterRow(content, stats.watching, R.string.profile_watching_empty,
                        item -> HistoryResumeCoordinator.open(requireActivity(), item),
                        item -> getString(R.string.profile_watching_progress,
                                (int) (item.getPosition() * 100L / Math.max(1L, item.getDuration()))));
                break;
            case MONTH:
                binding.cardMore.setVisibility(View.GONE);
                addLine(content, getString(R.string.profile_month_summary, stats.monthTitleCount, stats.monthPlayCount));
                if (stats.monthTopTitle != null) {
                    addLine(content, getString(R.string.profile_month_top, stats.monthTopTitle, stats.monthTopCount));
                } else {
                    addLine(content, getString(R.string.profile_month_empty));
                }
                break;
            case GENRES:
                binding.cardMore.setVisibility(View.GONE);
                addLine(content, getString(R.string.profile_genres_summary, stats.movieCount, stats.seriesCount));
                if (stats.topGenres.isEmpty()) {
                    addLine(content, getString(R.string.profile_genres_empty));
                } else {
                    for (ProfileStats.GenreCount genre : stats.topGenres) {
                        addLine(content, getString(R.string.profile_genres_row, genre.name, genre.count));
                    }
                }
                break;
            case TREND1:
                binding.cardMore.setVisibility(View.GONE);
                if (stats.trend1 == null) {
                    addLine(content, getString(R.string.profile_trend_empty));
                } else {
                    addTrendRow(content, stats.trend1);
                }
                break;
            case TREND2:
                binding.cardMore.setVisibility(View.GONE);
                if (stats.trend2.isEmpty()) {
                    addLine(content, getString(R.string.profile_trend_empty));
                } else {
                    int rank = 0;
                    for (ProfileStats.NameCount entry : stats.trend2) {
                        addLine(content, getString(R.string.profile_trend_row, ++rank, entry.name, entry.count));
                    }
                }
                break;
        }
    }

    /** 卡片头可点进对应页；不可点的卡把 › 藏掉。 */
    private void navigate(ItemProfileCardBinding binding, Runnable target) {
        binding.cardHeader.setOnClickListener(v -> target.run());
    }

    /** 总览：追更 / 收藏 / 历史 / 在看四列数字，各自点进对应页。 */
    private void addOverview(LinearLayout content, ProfileStats stats) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        addStatCell(row, String.valueOf(stats.followingCount), getString(R.string.profile_overview_following),
                () -> FollowingActivity.start(requireActivity()));
        addStatCell(row, String.valueOf(stats.keepCount), getString(R.string.profile_overview_favorites),
                () -> KeepActivity.start(requireActivity()));
        addStatCell(row, String.valueOf(stats.historyCount), getString(R.string.profile_overview_history),
                () -> HistoryActivity.start(requireActivity()));
        addStatCell(row, String.valueOf(stats.watching.size()), getString(R.string.profile_overview_watching),
                () -> HistoryActivity.start(requireActivity()));
        content.addView(row);
    }

    private void addStatCell(LinearLayout row, String number, String label, Runnable target) {
        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(params);
        cell.setOnClickListener(v -> target.run());
        TextView numberView = new TextView(requireContext());
        numberView.setText(number);
        numberView.setTextSize(20);
        numberView.setTypeface(null, android.graphics.Typeface.BOLD);
        numberView.setGravity(Gravity.CENTER);
        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(12);
        labelView.setTextColor(getResources().getColor(R.color.following_text_secondary, null));
        labelView.setGravity(Gravity.CENTER);
        cell.addView(numberView);
        cell.addView(labelView);
        row.addView(cell);
    }

    /** 横向海报行：没数据时只显示一句说明，不留空行。 */
    private <T> void addPosterRow(LinearLayout content, List<T> items, int emptyRes,
                                  PosterClick<T> onPosterClick, PosterSub<T> sub) {
        if (items.isEmpty()) {
            addLine(content, getString(emptyRes));
            return;
        }
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (T item : items) {
            ItemProfilePosterBinding posterBinding = ItemProfilePosterBinding.inflate(inflater, row, false);
            String pic = item instanceof History ? ((History) item).getVodPic() : ((Keep) item).getVodPic();
            String name = item instanceof History ? ((History) item).getVodName() : ((Keep) item).getVodName();
            GradientDrawable placeholder = placeholder(requireContext());
            posterBinding.posterPic.setBackground(placeholder);
            Glide.with(requireContext()).load(pic).centerCrop().placeholder(placeholder).error(placeholder)
                    .into(posterBinding.posterPic);
            posterBinding.posterName.setText(name);
            String subText = sub.text(item);
            posterBinding.posterSub.setText(subText);
            posterBinding.posterSub.setVisibility(subText.isEmpty() ? View.GONE : View.VISIBLE);
            posterBinding.getRoot().setOnClickListener(v -> onPosterClick.onClick(item));
            row.addView(posterBinding.getRoot());
        }
        content.addView(row);
    }

    /** Trend1：本周看得最多的那部，一张海报 + 一行说明。 */
    private void addTrendRow(LinearLayout content, ProfileStats.NameCount entry) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        ItemProfilePosterBinding posterBinding = ItemProfilePosterBinding.inflate(inflater, row, false);
        GradientDrawable placeholder = placeholder(requireContext());
        posterBinding.posterPic.setBackground(placeholder);
        Glide.with(requireContext()).load(entry.pic).centerCrop().placeholder(placeholder).error(placeholder)
                .into(posterBinding.posterPic);
        posterBinding.posterName.setText(entry.name);
        posterBinding.posterSub.setText(getString(R.string.profile_trend1_count, entry.count));
        row.addView(posterBinding.getRoot());
        TextView text = new TextView(requireContext());
        text.setText(getString(R.string.profile_trend1_text, entry.name, entry.count));
        text.setTextSize(14);
        text.setPadding(dp(12), 0, 0, 0);
        row.addView(text);
        content.addView(row);
    }

    /** 收藏海报点开：与 KeepActivity.onItemClick 同一路（切站 → 直播详情）。 */
    private void openKeep(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) {
            SearchActivity.start(requireActivity(), item.getVodName());
            return;
        }
        if (item.getCid() != VodConfig.getCid()) {
            VodConfig.load(config, new Callback() {
                @Override
                public void success() {
                    VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
                }

                @Override
                public void error(String msg) {
                    Notify.show(msg);
                }
            });
            return;
        }
        VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    private void addLine(LinearLayout parent, String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(14);
        parent.addView(view);
    }

    private String formatDate(long millis) {
        return FollowingAgenda.shortDate(millis);
    }

    private static GradientDrawable placeholder(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#FF2A3648"));
        drawable.setStroke((int) (1 * density), Color.parseColor("#FF3D4E66"));
        drawable.setCornerRadius(12 * density);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }

    private interface PosterClick<T> {
        void onClick(T item);
    }

    private interface PosterSub<T> {
        String text(T item);
    }
}
