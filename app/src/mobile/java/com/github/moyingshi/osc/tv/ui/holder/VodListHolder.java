package com.github.moyingshi.osc.tv.ui.holder;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.AdapterVodListBinding;
import com.github.moyingshi.osc.tv.ui.adapter.VodAdapter;
import com.github.moyingshi.osc.tv.ui.base.BaseVodHolder;
import com.github.moyingshi.osc.tv.utils.ImgUtil;

public class VodListHolder extends BaseVodHolder {

    private final VodAdapter.OnClickListener listener;
    private final AdapterVodListBinding binding;

    public VodListHolder(@NonNull AdapterVodListBinding binding, VodAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        binding.name.setSelected(true);
        binding.remark.setSelected(true);
    }

    @Override
    public void initView(Vod item) {
        binding.name.setText(item.getName());
        binding.remark.setText(item.getRemarks());
        binding.name.setVisibility(item.getNameVisible());
        binding.remark.setVisibility(item.getRemarkVisible());
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        binding.getRoot().setOnLongClickListener(v -> listener.onLongClick(item));
        ImgUtil.load(item.getName(), item.getPic(), binding.image);
    }

    @Override
    public void unbind() {
        Glide.with(binding.image).clear(binding.image);
    }
}
