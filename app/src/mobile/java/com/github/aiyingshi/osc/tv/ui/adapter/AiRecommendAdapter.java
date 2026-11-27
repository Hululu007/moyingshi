package com.github.aiyingshi.osc.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.aiyingshi.osc.tv.R;
import com.github.aiyingshi.osc.tv.bean.TmdbItem;
import com.github.aiyingshi.osc.tv.utils.ImgUtil;
import com.github.aiyingshi.osc.tv.utils.TmdbImageSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页“为你推荐”适配器（P0 功能 1）：横向滑动行，数据来自
 * {@code PersonalRecommendationService.loadAiPage}（AiRecommendationService，
 * 每项带 {@code TmdbItem.getRecommendationReason()} 的一句理由）。
 * 点片进详情由调用方用 {@code TmdbNavigation.open} 实现。
 */
public class AiRecommendAdapter extends RecyclerView.Adapter<AiRecommendAdapter.Holder> {

    public interface OnClickListener {
        void onItemClick(TmdbItem item);
    }

    private final List<TmdbItem> items = new ArrayList<>();
    private final OnClickListener listener;

    public AiRecommendAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<TmdbItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_ai_recommend, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position));
    }

    class Holder extends RecyclerView.ViewHolder {

        private final ImageView poster;
        private final TextView title;
        private final TextView reason;

        Holder(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.poster);
            title = itemView.findViewById(R.id.title);
            reason = itemView.findViewById(R.id.reason);
        }

        void bind(TmdbItem item) {
            title.setText(item.getTitle());
            String reasonText = item.getRecommendationReason();
            if (reasonText == null || reasonText.isEmpty()) {
                reason.setVisibility(View.GONE);
            } else {
                reason.setVisibility(View.VISIBLE);
                reason.setText(reasonText);
            }
            String image = TmdbImageSelector.cardImage(item, false);
            String fallbackImage = TmdbImageSelector.cardImage(item, true);
            ImgUtil.load(item.getTitle(), image, fallbackImage, poster, true, 300, 450);
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
        }
    }
}
