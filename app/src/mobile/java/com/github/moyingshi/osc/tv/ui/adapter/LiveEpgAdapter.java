package com.github.moyingshi.osc.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.AdapterLiveEpgBinding;
import com.github.moyingshi.osc.tv.setting.LiveEpgSetting;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class LiveEpgAdapter extends RecyclerView.Adapter<LiveEpgAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> items;

    public LiveEpgAdapter(OnClickListener listener) {
        this.listener = listener;
        this.items = new ArrayList<>();
        reload();
    }

    public void reload() {
        items.clear();
        items.add("");
        for (String item : LiveEpgSetting.getHistory()) add(item);
        notifyDataSetChanged();
    }

    private void add(String item) {
        if (!items.contains(item)) items.add(item);
    }

    public interface OnClickListener {

        void onEpgClick(String url);

        void onEpgEdit(String url);

        void onEpgDelete(String url);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLiveEpgBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String item = items.get(position);
        boolean editable = !item.isEmpty();
        holder.binding.text.setText(item.isEmpty() ? ResUtil.getString(R.string.live_epg_default) : item);
        holder.binding.getRoot().setSelected(item.equals(LiveEpgSetting.getUrl()));
        holder.binding.edit.setVisibility(editable ? View.VISIBLE : View.GONE);
        holder.binding.delete.setVisibility(editable ? View.VISIBLE : View.GONE);
        holder.binding.getRoot().setOnClickListener(v -> listener.onEpgClick(item));
        holder.binding.edit.setOnClickListener(v -> listener.onEpgEdit(item));
        holder.binding.delete.setOnClickListener(v -> listener.onEpgDelete(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLiveEpgBinding binding;

        ViewHolder(@NonNull AdapterLiveEpgBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
