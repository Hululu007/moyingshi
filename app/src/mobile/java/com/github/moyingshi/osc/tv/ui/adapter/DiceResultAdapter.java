package com.github.moyingshi.osc.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.AdapterDiceResultBinding;
import com.github.moyingshi.osc.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/** 喜好页结果 / 历史行：海报 + 片名 + 收藏 / 想看 / 避雷三个操作，点行进详情。 */
public class DiceResultAdapter extends RecyclerView.Adapter<DiceResultAdapter.Holder> {

    public interface OnActionListener {
        void onItemClick(Vod item);

        void onKeep(Vod item);

        void onWant(Vod item);

        void onBlock(Vod item);
    }

    private final OnActionListener mListener;
    private final List<Vod> mItems = new ArrayList<>();

    public DiceResultAdapter(OnActionListener listener) {
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
        return new Holder(AdapterDiceResultBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
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

        private final AdapterDiceResultBinding mBinding;

        Holder(AdapterDiceResultBinding binding) {
            super(binding.getRoot());
            mBinding = binding;
        }

        void bind(Vod item) {
            ImgUtil.load(item.getName(), item.getPic(), mBinding.pic);
            mBinding.name.setText(item.getName());
            String info = item.getTypeName();
            if (item.getSite() != null && !item.getSite().getDisplayName().isEmpty()) {
                info = item.getSite().getDisplayName() + (info.isEmpty() ? "" : " · " + info);
            }
            mBinding.info.setText(info);
            mBinding.info.setVisibility(info.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            final Vod vod = item;
            mBinding.getRoot().setOnClickListener(v -> mListener.onItemClick(vod));
            mBinding.keepButton.setOnClickListener(v -> mListener.onKeep(vod));
            mBinding.wantButton.setOnClickListener(v -> mListener.onWant(vod));
            mBinding.blockButton.setOnClickListener(v -> mListener.onBlock(vod));
        }
    }
}
