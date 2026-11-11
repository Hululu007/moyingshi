package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ItemSettingSearchResultBinding;

import java.util.ArrayList;
import java.util.List;

/** Flat list of settings-search results. */
public class SettingSearchAdapter extends RecyclerView.Adapter<SettingSearchAdapter.Holder> {

    public interface Listener {
        void onEntry(SettingSearchIndex.Entry entry);
    }

    private final List<SettingSearchIndex.Entry> entries = new ArrayList<>();
    private final Listener listener;

    public SettingSearchAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setEntries(List<SettingSearchIndex.Entry> result) {
        entries.clear();
        entries.addAll(result);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSettingSearchResultBinding binding = ItemSettingSearchResultBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SettingSearchIndex.Entry entry = entries.get(position);
        holder.binding.title.setText(entry.titleRes);
        if (entry.subtitleRes != 0) {
            holder.binding.subtitle.setVisibility(View.VISIBLE);
            holder.binding.subtitle.setText(entry.subtitleRes);
        } else {
            holder.binding.subtitle.setVisibility(View.GONE);
        }
        holder.binding.dest.setText(entry.destRes);
        holder.binding.getRoot().setOnClickListener(v -> listener.onEntry(entry));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemSettingSearchResultBinding binding;

        Holder(ItemSettingSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
