package com.github.moyingshi.osc.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.ItemFollowingBinding;
import com.github.moyingshi.osc.tv.databinding.ItemFollowingDateHeaderBinding;
import com.github.moyingshi.osc.tv.following.Following;
import com.github.moyingshi.osc.tv.following.FollowingAgenda;
import com.github.moyingshi.osc.tv.following.FollowingSource;
import com.github.moyingshi.osc.tv.following.FollowingUpdateCoordinator;
import com.github.moyingshi.osc.tv.following.FollowingUpdatePolicy;
import com.github.moyingshi.osc.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FollowingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onOpenDetail(Following item, FollowingSource source);

        void onContinue(Following item, FollowingSource source);

        void onFollowNextSeason(Following item);

        void onCheck(Following item);

        void onRead(Following item);

        void onToggleNotify(Following item);

        void onChangeSource(Following item);

        void onDelete(Following item);
    }

    public static final class Row {
        public final Following following;
        public final FollowingSource source;

        public Row(Following following, FollowingSource source) {
            this.following = following;
            this.source = source;
        }
    }

    /** 时间轴日期分组头：dayStart 为当天 0 点毫秒值，0 表示“待定”组。 */
    public static final class Header {
        public final long dayStart;
        public final String label;
        public final int count;

        public Header(long dayStart, String label, int count) {
            this.dayStart = dayStart;
            this.label = label;
            this.count = count;
        }
    }

    private static final int TYPE_ROW = 0;
    private static final int TYPE_HEADER = 1;

    private final Listener listener;
    /** Row 与 Header 混排：TV 布局没有日期 Tab，传进来的就是纯 Row 列表，老行为不变。 */
    private final List<Object> items = new ArrayList<>();

    public FollowingAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setItems(List<Object> rows) {
        items.clear();
        if (rows != null) items.addAll(rows);
        notifyDataSetChanged();
    }

    public int indexOf(String identityKey) {
        for (int i = 0; i < items.size(); i++) {
            Object entry = items.get(i);
            if (entry instanceof Row && ((Row) entry).following.identityKey.equals(identityKey)) return i;
        }
        return -1;
    }

    @Override
    public long getItemId(int position) {
        Object entry = items.get(position);
        if (entry instanceof Header) return -((Header) entry).dayStart - 1;
        return ((Row) entry).following.identityKey.hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Header ? TYPE_HEADER : TYPE_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(ItemFollowingDateHeaderBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }
        return new Holder(ItemFollowingBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object entry = items.get(position);
        if (entry instanceof Header) {
            Header header = (Header) entry;
            HeaderHolder headerHolder = (HeaderHolder) holder;
            headerHolder.binding.headerLabel.setText(header.label);
            headerHolder.binding.headerCount.setText(headerHolder.itemView.getContext()
                    .getString(R.string.following_agenda_header_count, header.count));
            return;
        }
        bindRow((Holder) holder, (Row) entry);
    }

    private void bindRow(@NonNull Holder holder, Row row) {
        Following item = row.following;
        FollowingSource source = row.source;
        holder.binding.title.setText(item.vodName);
        ImgUtil.load(item.vodName, item.vodPic, holder.binding.image);
        bindAirLine(holder, item);
        int released = FollowingUpdatePolicy.releasedEpisode(item);
        if (released > 0) holder.binding.official.setText(holder.itemView.getContext().getString(R.string.following_official, released));
        else holder.binding.official.setText(R.string.following_official_unknown);
        bindMetadata(holder, item);
        if (source != null && source.playableEpisode > 0) {
            holder.binding.source.setText(holder.itemView.getContext().getString(R.string.following_source, source.playableEpisode));
        } else {
            // 探针跑过但没算出可播集数必须与「尚未检查」分开，否则失败被静默伪装成未检查。
            // 判据必须与 FILTER_FAILED 同口径，且不能用 lastProbeAt：订阅导入会预写它，从未探过的源会误显示失败。
            holder.binding.source.setText(FollowingUpdateCoordinator.sourceCheckFailed(item.lastError)
                    ? R.string.following_source_failed : R.string.following_source_unknown);
        }
        if (item.watchedEpisode > 0) holder.binding.progress.setText(holder.itemView.getContext().getString(R.string.following_watched, item.watchedEpisode));
        else holder.binding.progress.setText(R.string.following_watched_unknown);
        if (item.unwatchedCount > 0) holder.binding.progress.append("\n" + holder.itemView.getContext().getString(R.string.following_unwatched, item.unwatchedCount));
        else if (released > 0) holder.binding.progress.append("\n" + holder.itemView.getContext().getString(R.string.following_unwatched_none));
        if (item.hasUpdate && item.unwatchedCount > 0) {
            holder.binding.badge.setVisibility(View.VISIBLE);
            holder.binding.badge.setText(holder.itemView.getContext().getString(R.string.following_new_count, item.unwatchedCount));
        } else {
            holder.binding.badge.setVisibility(View.GONE);
        }
        if (item.lastError == null || item.lastError.isBlank()) {
            holder.binding.error.setVisibility(View.GONE);
        } else {
            holder.binding.error.setVisibility(View.VISIBLE);
            holder.binding.error.setText(item.lastError);
        }
        holder.binding.notify.setText(item.notifyEnabled ? R.string.following_notify_on : R.string.following_notify_off);
        holder.itemView.setOnClickListener(view -> listener.onOpenDetail(item, source));
        if (item.latestReleasedSeason > item.trackedSeason) {
            holder.binding.nextSeason.setVisibility(View.VISIBLE);
            holder.binding.nextSeason.setText(holder.itemView.getContext().getString(R.string.following_next_season, item.latestReleasedSeason + 1));
        } else {
            holder.binding.nextSeason.setVisibility(View.GONE);
        }
        holder.binding.nextSeason.setOnClickListener(view -> listener.onFollowNextSeason(item));
        holder.binding.continuePlay.setOnClickListener(view -> listener.onContinue(item, source));
        holder.binding.check.setOnClickListener(view -> listener.onCheck(item));
        holder.binding.read.setVisibility(item.hasUpdate ? View.VISIBLE : View.GONE);
        holder.binding.read.setOnClickListener(view -> listener.onRead(item));
        holder.binding.notify.setOnClickListener(view -> listener.onToggleNotify(item));
        holder.binding.sourceChange.setOnClickListener(view -> listener.onChangeSource(item));
        holder.binding.delete.setOnClickListener(view -> listener.onDelete(item));
    }

    /**
     * 播出时间线 + 完结标识：完结的剧只亮“已完结”徽章；有下一集播出时间的显示
     * “今天 20:00 · 第 2 季第 5 集”；没播出信息的这一行直接隐藏，不刷“待定”噪音。
     */
    private void bindAirLine(Holder holder, Following item) {
        boolean ended = FollowingAgenda.isEnded(item);
        holder.binding.ended.setVisibility(ended ? View.VISIBLE : View.GONE);
        String text = FollowingAgenda.airLineText(holder.itemView.getContext(), item);
        if (text == null) {
            holder.binding.airLine.setVisibility(View.GONE);
            return;
        }
        holder.binding.airLine.setText(text);
        holder.binding.airLine.setVisibility(View.VISIBLE);
    }

    private void bindMetadata(Holder holder, Following item) {
        List<String> parts = new ArrayList<>();
        if (item.seasonTotalEpisodes > 0) {
            parts.add(holder.itemView.getContext().getString(R.string.following_season_total, item.seasonTotalEpisodes));
        }
        if (item.seriesTotalEpisodes > 0) {
            parts.add(holder.itemView.getContext().getString(R.string.following_series_total, item.seriesTotalEpisodes));
        }
        if (item.nextAirWeekday > 0) {
            parts.add(holder.itemView.getContext().getString(R.string.following_weekday,
                    holder.itemView.getResources().getStringArray(R.array.following_weekdays)[item.nextAirWeekday - 1]));
        }
        if (parts.isEmpty()) holder.binding.metadata.setVisibility(View.GONE);
        else {
            holder.binding.metadata.setVisibility(View.VISIBLE);
            holder.binding.metadata.setText(String.join(" · ", parts));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 不含分组头的剧集行数，汇总行（“追更 X 部”）用这个，别用 getItemCount()。 */
    public int getRowCount() {
        int count = 0;
        for (Object entry : items) if (entry instanceof Row) count++;
        return count;
    }

    public Row rowAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Object entry = items.get(position);
        return entry instanceof Row ? (Row) entry : null;
    }

    public List<Row> getItems() {
        List<Row> rows = new ArrayList<>();
        for (Object entry : items) if (entry instanceof Row) rows.add((Row) entry);
        return rows;
    }

    public void markReadLocally(Collection<String> identityKeys) {
        if (identityKeys == null || identityKeys.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            Object entry = items.get(i);
            if (!(entry instanceof Row)) continue;
            Following item = ((Row) entry).following;
            if (!identityKeys.contains(item.identityKey) || !item.hasUpdate) continue;
            FollowingUpdatePolicy.markRead(item, 0);
            notifyItemChanged(i);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemFollowingBinding binding;

        Holder(ItemFollowingBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final ItemFollowingDateHeaderBinding binding;

        HeaderHolder(ItemFollowingDateHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
