package com.github.moyingshi.osc.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.databinding.DialogDownloadEpisodeBinding;
import com.github.moyingshi.osc.tv.databinding.ItemDownloadSelectEpisodeBinding;
import com.github.moyingshi.osc.tv.download.VideoDownloadManager;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 选集下载弹窗：列出全部集数多选打勾，底部全选 / 反向选择 / 开始下载。
 * 点开始下载后先要存储权限，再把选中的集逐个交给下载队列。
 */
public final class DownloadEpisodeDialog {

    private DownloadEpisodeDialog() {
    }

    public static void show(FragmentActivity activity, String dramaName, String siteKey, String flag, List<Episode> episodes) {
        if (activity == null || episodes == null || episodes.isEmpty()) return;
        DialogDownloadEpisodeBinding binding = DialogDownloadEpisodeBinding.inflate(LayoutInflater.from(activity));
        DownloadEpisodeAdapter adapter = new DownloadEpisodeAdapter(episodes);
        binding.list.setLayoutManager(new LinearLayoutManager(activity));
        binding.list.setAdapter(adapter);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(binding.getRoot());
        binding.selectAll.setOnClickListener(v -> adapter.selectAll());
        binding.invertSelection.setOnClickListener(v -> adapter.invertSelection());
        binding.startDownload.setOnClickListener(v -> {
            List<Episode> selected = adapter.getSelected();
            if (selected.isEmpty()) {
                Notify.show(R.string.download_no_episode_selected);
                return;
            }
            dialog.dismiss();
            PermissionUtil.requestFile(activity, granted -> {
                if (!granted) {
                    Notify.show(R.string.download_need_permission);
                    return;
                }
                int enqueued = 0;
                for (Episode episode : selected) {
                    if (VideoDownloadManager.get().enqueue(dramaName, siteKey, flag, episode.getName(), episode.getUrl())) enqueued++;
                }
                if (enqueued > 0) Notify.show(R.string.download_started);
            });
        });
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        configureWindow(activity, dialog);
    }

    private static void configureWindow(FragmentActivity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        params.dimAmount = 0.58f;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
    }

    /** 集数多选列表：点整行切换勾选。 */
    private static final class DownloadEpisodeAdapter extends RecyclerView.Adapter<DownloadEpisodeAdapter.Holder> {

        private final List<Episode> episodes;
        private final Set<Integer> selected = new HashSet<>();

        DownloadEpisodeAdapter(List<Episode> episodes) {
            this.episodes = episodes;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemDownloadSelectEpisodeBinding binding;

            Holder(ItemDownloadSelectEpisodeBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemDownloadSelectEpisodeBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Episode episode = episodes.get(position);
            holder.binding.name.setText(episode.getName());
            holder.binding.check.setChecked(selected.contains(position));
            holder.binding.getRoot().setOnClickListener(v -> toggle(position));
        }

        private void toggle(int position) {
            if (selected.contains(position)) selected.remove(position);
            else selected.add(position);
            notifyItemChanged(position);
        }

        @Override
        public int getItemCount() {
            return episodes.size();
        }

        void selectAll() {
            for (int i = 0; i < episodes.size(); i++) selected.add(i);
            notifyDataSetChanged();
        }

        void invertSelection() {
            Set<Integer> inverted = new HashSet<>();
            for (int i = 0; i < episodes.size(); i++) {
                if (!selected.contains(i)) inverted.add(i);
            }
            selected.clear();
            selected.addAll(inverted);
            notifyDataSetChanged();
        }

        List<Episode> getSelected() {
            List<Episode> result = new ArrayList<>();
            for (int i = 0; i < episodes.size(); i++) {
                if (selected.contains(i)) result.add(episodes.get(i));
            }
            return result;
        }
    }
}
