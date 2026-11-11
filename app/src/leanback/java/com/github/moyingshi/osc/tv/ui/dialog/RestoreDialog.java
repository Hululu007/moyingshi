package com.github.moyingshi.osc.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.DialogRestoreBinding;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.ui.adapter.RestoreAdapter;
import com.github.moyingshi.osc.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class RestoreDialog extends BaseAlertDialog implements RestoreAdapter.OnClickListener {

    private DialogRestoreBinding binding;
    private RestoreAdapter adapter;
    private Callback callback;

    public static RestoreDialog create() {
        return new RestoreDialog();
    }

    public RestoreDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = LightDialog.create(requireContext(), getString(R.string.restore_select), getBinding().getRoot());
        initView();
        initEvent();
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogRestoreBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new RestoreAdapter(this);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    @Override
    public void onItemClick(File item) {
        BackupProgressDialog progress = BackupProgressDialog.open(getParentFragmentManager(), "恢复应用数据");
        AppDatabase.restore(item, new Callback() {
            @Override
            public void success() {
                progress.finish();
                if (callback != null) callback.success();
            }

            @Override
            public void error() {
                progress.finish();
                if (callback != null) callback.error();
            }
        }, progress::update);
        dismiss();
    }

    @Override
    public void onDeleteClick(File item) {
        int count = adapter.remove(item);
        if (count == 0) {
            dismiss();
            return;
        }
        if (count > 0) binding.recycler.post(() -> binding.recycler.scrollToPosition(0));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}
