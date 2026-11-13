package com.github.moyingshi.osc.tv.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.AdapterBannerBinding;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页轮播横幅适配器（B1）：给 VodFragment 的 ViewPager2 用。
 * 数据来自当前站首页推荐（SiteApi.homeContent 的 homeVideoContent）。
 */
public class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.Holder> {

    private final OnClickListener listener;
    private final List<Vod> items = new ArrayList<>();

    public BannerAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(Vod item);
    }

    public void submit(List<Vod> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public int getItemCount() {
        return items.size();
    }

    public Vod getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(AdapterBannerBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position), position, items.size());
    }

    class Holder extends RecyclerView.ViewHolder {

        private final AdapterBannerBinding binding;

        Holder(@NonNull AdapterBannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Vod item, int position, int count) {
            binding.name.setText(item.getName());
            if (item.getRemarks() == null || item.getRemarks().isEmpty()) {
                binding.remark.setVisibility(View.GONE);
            } else {
                binding.remark.setVisibility(View.VISIBLE);
                binding.remark.setText(item.getRemarks());
            }
            bindDots(position, count);
            ImgUtil.load(item.getName(), item.getPic(), binding.image);
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
        }

        /** 轮播指示器：右上角小点，本页为 18dp 白色 pill，其余为 6dp 半透白圆；只有 1 页时隐藏。 */
        private void bindDots(int position, int count) {
            if (count <= 1) {
                binding.dots.setVisibility(View.GONE);
                return;
            }
            binding.dots.setVisibility(View.VISIBLE);
            binding.dots.removeAllViews();
            for (int i = 0; i < count; i++) {
                boolean active = i == position;
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.RECTANGLE);
                dot.setCornerRadius(ResUtil.dp2px(3));
                dot.setColor(active ? 0xFFFFFFFF : 0x66FFFFFF);
                View view = new View(binding.dots.getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ResUtil.dp2px(active ? 18 : 6), ResUtil.dp2px(6));
                if (i < count - 1) params.setMarginEnd(ResUtil.dp2px(6));
                view.setLayoutParams(params);
                view.setBackground(dot);
                binding.dots.addView(view);
            }
        }
    }
}
