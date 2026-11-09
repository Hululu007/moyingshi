package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ItemLocalFileBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地源文件浏览列表：文件夹在前、支持的源文件在后，点文件夹进目录，点文件就用那一个。
 */
public class LocalFileAdapter extends RecyclerView.Adapter<LocalFileAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<File> mItems = new ArrayList<>();

    public LocalFileAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onFileClick(File file);
    }

    public LocalFileAdapter setItems(List<File> items) {
        mItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
        notifyDataSetChanged();
        return this;
    }

    public List<File> getItems() {
        return mItems;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemLocalFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = mItems.get(position);
        holder.binding.name.setText((file.isDirectory() ? "\uD83D\uDCC1 " : "\uD83D\uDCC4 ") + file.getName());
        holder.binding.getRoot().setOnClickListener(v -> listener.onFileClick(file));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemLocalFileBinding binding;

        ViewHolder(@NonNull ItemLocalFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
