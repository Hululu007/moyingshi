package com.github.moyingshi.osc.tv.ui.hk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkDetailHelper;
import com.github.moyingshi.osc.tv.databinding.AdapterEpisodeGridBinding;
import com.github.moyingshi.osc.tv.databinding.ItemHkDetailInfoBinding;
import com.github.moyingshi.osc.tv.databinding.ItemHkDetailLineBinding;
import com.github.moyingshi.osc.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页多类型 Adapter：信息头（海报 + 标题 + 简介）+ 线路头 + 选集 chip。
 * 选集 chip 复用现有 adapter_episode_grid.xml（@+id/text，40dp 圆角按钮样式，
 * 与现有详情页的选集 chip 同款）。布局由 Fragment 的 GridLayoutManager +
 * SpanSizeLookup 控制：信息与线路头占满整行，选集占 1 列。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkDetailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_INFO = 0;
    public static final int TYPE_LINE = 1;
    public static final int TYPE_EPISODE = 2;

    public interface Listener {
        void onEpisodeClick(HkCard episode);
    }

    private final Listener listener;
    private final List<Object> items = new ArrayList<>();

    public HkDetailAdapter(Listener listener) {
        this.listener = listener;
    }

    /** 整页数据：信息头 + 每条线路的头与选集（按 HkDetailHelper.Detail 的顺序铺平）。 */
    public void setDetail(HkDetailHelper.Detail detail) {
        items.clear();
        if (detail == null) {
            notifyDataSetChanged();
            return;
        }
        items.add(detail.info);
        for (HkDetailHelper.Line line : detail.lines) {
            items.add(line);
            items.addAll(line.episodes);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof HkDetailHelper.Info) return TYPE_INFO;
        if (item instanceof HkDetailHelper.Line) return TYPE_LINE;
        return TYPE_EPISODE;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_INFO) {
            return new InfoHolder(ItemHkDetailInfoBinding.inflate(inflater, parent, false));
        }
        if (viewType == TYPE_LINE) {
            return new LineHolder(ItemHkDetailLineBinding.inflate(inflater, parent, false));
        }
        return new EpisodeHolder(AdapterEpisodeGridBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof InfoHolder) {
            HkDetailHelper.Info info = (HkDetailHelper.Info) item;
            InfoHolder h = (InfoHolder) holder;
            h.binding.title.setText(info.title);
            h.binding.desc.setText(info.desc);
            h.binding.desc.setVisibility(info.desc.isEmpty() ? View.GONE : View.VISIBLE);
            ImgUtil.load(info.title, info.pic, h.binding.poster);
        } else if (holder instanceof LineHolder) {
            ((LineHolder) holder).binding.text.setText(((HkDetailHelper.Line) item).title);
        } else if (holder instanceof EpisodeHolder) {
            HkCard episode = (HkCard) item;
            EpisodeHolder h = (EpisodeHolder) holder;
            h.binding.text.setText(episode.getTitle());
            h.binding.text.setOnClickListener(v -> listener.onEpisodeClick(episode));
        }
    }

    static class InfoHolder extends RecyclerView.ViewHolder {
        final ItemHkDetailInfoBinding binding;

        InfoHolder(ItemHkDetailInfoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class LineHolder extends RecyclerView.ViewHolder {
        final ItemHkDetailLineBinding binding;

        LineHolder(ItemHkDetailLineBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class EpisodeHolder extends RecyclerView.ViewHolder {
        final AdapterEpisodeGridBinding binding;

        EpisodeHolder(AdapterEpisodeGridBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
