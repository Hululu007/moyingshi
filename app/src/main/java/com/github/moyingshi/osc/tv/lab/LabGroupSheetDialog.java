package com.github.moyingshi.osc.tv.lab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.DialogLabGroupBinding;
import com.github.moyingshi.osc.tv.ui.dialog.BaseBottomSheetDialog;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayoutManager;

import java.util.List;

/**
 * 实验室悬浮 tune 按钮的底部面板：全部分组 chips，点哪个切到哪个分组（和点顶部标签栏同一个 onGroup）。
 * chips 复用标签栏同一套样式（adapter_lab_group_tab），当前分组高亮。
 */
public class LabGroupSheetDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onGroup(String id);
    }

    private DialogLabGroupBinding binding;
    private List<LabGroupAdapter.Row> rows;
    private String selectedId;
    private Listener listener;

    public static LabGroupSheetDialog create(List<LabGroupAdapter.Row> rows, String selectedId, Listener listener) {
        LabGroupSheetDialog dialog = new LabGroupSheetDialog();
        dialog.rows = rows;
        dialog.selectedId = selectedId;
        dialog.listener = listener;
        return dialog;
    }

    public void show(FragmentManager manager) {
        if (manager.findFragmentByTag("LabGroupSheet") != null) return;
        show(manager, "LabGroupSheet");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        binding = DialogLabGroupBinding.inflate(inflater, container, false);
        return binding;
    }

    @Override
    protected void initView() {
        FlexboxLayoutManager manager = new FlexboxLayoutManager(requireContext(), FlexDirection.ROW, FlexWrap.WRAP);
        binding.recycler.setLayoutManager(manager);
        binding.recycler.setAdapter(new RecyclerView.Adapter<Holder>() {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_lab_group_tab, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                LabGroupAdapter.Row row = rows.get(position);
                holder.text.setText(row.name);
                holder.text.setActivated(row.id.equals(selectedId));
                holder.itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onGroup(row.id);
                    dismiss();
                });
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView text;

        Holder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text);
        }
    }
}
