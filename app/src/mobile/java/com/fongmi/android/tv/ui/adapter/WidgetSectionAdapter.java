package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterWidgetItemBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.widget.WidgetItem;
import com.fongmi.android.tv.widget.WidgetManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页组件区一行（B5）：横向滑动的海报卡片行，数据来自组件条目。
 * 点条目：在当前资源里搜片名（与 B1 横幅的索引站行为一致）。
 */
public class WidgetSectionAdapter extends RecyclerView.Adapter<WidgetSectionAdapter.Holder> {

    private final OnClickListener listener;
    private final List<WidgetItem> items = new ArrayList<>();

    public WidgetSectionAdapter(OnClickListener listener) {
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

    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(AdapterWidgetItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position));
    }

    class Holder extends RecyclerView.ViewHolder {

        private final AdapterWidgetItemBinding binding;

        Holder(@NonNull AdapterWidgetItemBinding binding) {
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
