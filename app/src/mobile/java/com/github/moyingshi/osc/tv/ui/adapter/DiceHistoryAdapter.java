package com.github.moyingshi.osc.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.AdapterDiceHistoryCardBinding;
import com.github.moyingshi.osc.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 骰子历史记录横向卡片（第 6 轮，贴参考图）：海报 + 片名 + 再摇一次 / 加入收藏 / 永久避雷。
 * 点卡片进详情；长按海报加入想看（想看入口挪到这里，原结果行按钮随纵向结果列表一起去掉）。
 */
public class DiceHistoryAdapter extends RecyclerView.Adapter<DiceHistoryAdapter.Holder> {

    public interface OnHistoryActionListener {
        void onItemClick(Vod item);

        void onKeep(Vod item);

        void onBlock(Vod item);

        void onReshake(Vod item);

        void onWant(Vod item);
    }

    private final OnHistoryActionListener mListener;
    private final List<Vod> mItems = new ArrayList<>();

    public DiceHistoryAdapter(OnHistoryActionListener listener) {
        mListener = listener;
    }

    public void setItems(List<Vod> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void remove(Vod item) {
        int index = mItems.indexOf(item);
        if (index < 0) return;
        mItems.remove(index);
        notifyItemRemoved(index);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(AdapterDiceHistoryCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    class Holder extends RecyclerView.ViewHolder {

        private final AdapterDiceHistoryCardBinding mBinding;

        Holder(AdapterDiceHistoryCardBinding binding) {
            super(binding.getRoot());
            mBinding = binding;
        }

        void bind(Vod item) {
            ImgUtil.load(item.getName(), item.getPic(), mBinding.pic);
            mBinding.name.setText(item.getName());
            final Vod vod = item;
            mBinding.getRoot().setOnClickListener(v -> mListener.onItemClick(vod));
            mBinding.pic.setOnLongClickListener(v -> {
                mListener.onWant(vod);
                return true;
            });
            mBinding.reshakeButton.setOnClickListener(v -> mListener.onReshake(vod));
            mBinding.keepButton.setOnClickListener(v -> mListener.onKeep(vod));
            mBinding.blockButton.setOnClickListener(v -> mListener.onBlock(vod));
        }
    }
}
