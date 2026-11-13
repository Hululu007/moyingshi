package com.github.moyingshi.osc.tv.ui.adapter;

import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.databinding.AdapterContinueBinding;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页“继续观看”适配器（B2）：横向滑动行，数据来自观看历史。
 * 只显示未看完的（duration > 0 且 position < duration），点进续播。
 */
public class ContinueAdapter extends RecyclerView.Adapter<ContinueAdapter.Holder> {

    private final OnClickListener listener;
    private final List<History> items = new ArrayList<>();

    public ContinueAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(History item);
    }

    public void submit(List<History> list) {
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
        return new Holder(AdapterContinueBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position));
    }

    class Holder extends RecyclerView.ViewHolder {

        private final AdapterContinueBinding binding;

        Holder(@NonNull AdapterContinueBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(History item) {
            binding.name.setText(displayName(item.getVodName()));
            if (item.getVodRemarks() == null || item.getVodRemarks().isEmpty()) {
                binding.remark.setVisibility(View.GONE);
            } else {
                binding.remark.setVisibility(View.VISIBLE);
                binding.remark.setText(item.getVodRemarks());
            }
            long duration = item.getDuration();
            int progress = duration > 0 ? (int) (item.getPosition() * 100 / duration) : 0;
            binding.progress.setProgress(Math.max(0, Math.min(100, progress)));
            String pic = item.getVodPic();
            if (pic == null || pic.isEmpty() || TextUtils.equals(pic, ResUtil.getString(R.string.push_image))) {
                // 无海报，或仍是旧 push 占位图（纸飞机白底）：显示柔和渐变占位，不走 ImgUtil；
                // 先清掉 Glide 在途请求，避免回收复用时旧海报晚到盖掉占位。
                Glide.with(binding.image).clear(binding.image);
                binding.image.setImageResource(R.drawable.continue_placeholder);
            } else {
                ImgUtil.load(item.getVodName(), pic, binding.image);
            }
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
        }

        /**
         * file:// 之类技术字串不直接当标题：取最后一段；播放列表文件（.m3u8）取父文件夹名；
         * 去扩展名并解码 %XX。普通剧名原样返回。
         */
        private static String displayName(String name) {
            if (name == null) return "";
            int scheme = name.indexOf("://");
            if (scheme < 0) return name;
            String clean = name.substring(scheme + 3);
            while (clean.startsWith("/")) clean = clean.substring(1);
            int lastSlash = clean.lastIndexOf('/');
            String last = lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;
            if (last.endsWith(".m3u8") || last.endsWith(".M3U8")) {
                clean = lastSlash > 0 ? clean.substring(0, lastSlash) : clean;
                lastSlash = clean.lastIndexOf('/');
                last = lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;
            }
            int dot = last.lastIndexOf('.');
            if (dot > 0) last = last.substring(0, dot);
            try {
                return Uri.decode(last);
            } catch (IllegalArgumentException e) {
                return last;
            }
        }
    }
}
