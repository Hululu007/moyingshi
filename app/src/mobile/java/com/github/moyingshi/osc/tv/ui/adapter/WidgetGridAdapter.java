package com.github.moyingshi.osc.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.databinding.AdapterWidgetGridItemBinding;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.widget.WidgetItem;
import com.github.moyingshi.osc.tv.widget.WidgetManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件浏览页的网格适配器（B5）：与首页行共用 WidgetItem 数据，
 * 点条目同样在当前资源里搜片名。分页追加由宿主 Activity 驱动。
 */
public class WidgetGridAdapter extends RecyclerView.Adapter<WidgetGridAdapter.Holder> {

    private final OnClickListener listener;
    private final List<WidgetItem> items = new ArrayList<>();

    public WidgetGridAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(WidgetItem item);
    }

    public void submit(List<WidgetItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void append(List<WidgetItem> list) {
        if (list == null || list.isEmpty()) return;
        int start = items.size();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(AdapterWidgetGridItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position));
    }

    class Holder extends RecyclerView.ViewHolder {

        private final AdapterWidgetGridItemBinding binding;

        Holder(@NonNull AdapterWidgetGridItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(WidgetItem item) {
            binding.name.setText(item.getTitle());
            String remark = item.getDisplayRemark();
            if (remark == null || remark.isEmpty()) {
                binding.remark.setVisibility(View.GONE);
            } else {
                binding.remark.setVisibility(View.VISIBLE);
                binding.remark.setText(remark);
            }
            ImgUtil.load(item.getTitle(), WidgetManager.posterUrlWithHeaders(item), binding.image);
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
        }
    }
}
