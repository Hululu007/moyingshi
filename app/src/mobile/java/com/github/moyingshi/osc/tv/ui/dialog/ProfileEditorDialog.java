package com.github.moyingshi.osc.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.databinding.DialogProfileManageBinding;
import com.github.moyingshi.osc.tv.profile.ProfileCard;
import com.github.moyingshi.osc.tv.profile.ProfileLayout;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 我的页卡片管理：底部面板，按 Arcane 的管理卡片样式——每行卡片名 + 显示开关 + 上移/下移，
 * 点完成或下滑/按返回关闭时保存并刷新我的页。
 *
 * 注意：行里的控件必须用面板自己的 context 建（binding 根 view 的 context，走弹窗主题），
 * 不能用 Activity 的 context——旧 AlertDialog 版就是这么把卡片名弄丢的：Activity 是深色主题，
 * plain TextView 默认取了浅色字，落在浅色弹窗底上看不见。
 */
public class ProfileEditorDialog extends BaseBottomSheetDialog {

    private Runnable onChanged;
    private List<ProfileCard> order;
    private Set<ProfileCard> hidden;
    private DialogProfileManageBinding binding;

    public static ProfileEditorDialog create(Runnable onChanged) {
        ProfileEditorDialog dialog = new ProfileEditorDialog();
        dialog.onChanged = onChanged;
        return dialog;
    }

    public void show(FragmentManager manager) {
        if (manager.findFragmentByTag("ProfileManage") != null) return;
        show(manager, "ProfileManage");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        binding = DialogProfileManageBinding.inflate(inflater, container, false);
        return binding;
    }

    @Override
    protected void initView() {
        order = ProfileLayout.orderedCards();
        hidden = ProfileLayout.hiddenCards();
        binding.doneBtn.setOnClickListener(v -> dismiss());
        rebuildRows();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (order != null) ProfileLayout.save(order, hidden);
        if (onChanged != null) onChanged.run();
    }

    private void rebuildRows() {
        binding.manageContainer.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            binding.manageContainer.addView(buildRow(order.get(i), i));
        }
    }

    private View buildRow(ProfileCard card, int index) {
        // 用面板的 context 建控件，走弹窗主题的字色，卡片名才看得见。
        android.content.Context ctx = binding.manageContainer.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int rowPadding = (int) (6 * density);
        int arrowWidth = (int) (48 * density);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, rowPadding, 0, rowPadding);
        TextView title = new AppCompatTextView(ctx);
        title.setText(card.titleRes);
        title.setTextSize(15);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (hidden.contains(card)) title.setAlpha(0.45f);
        SwitchMaterial visible = new SwitchMaterial(ctx);
        visible.setChecked(!hidden.contains(card));
        visible.setOnCheckedChangeListener((button, checked) -> {
            if (checked) hidden.remove(card);
            else hidden.add(card);
            rebuildRows();
        });
        Button up = new Button(ctx);
        up.setText("↑");
        up.setEnabled(index > 0);
        up.setLayoutParams(new LinearLayout.LayoutParams(arrowWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        up.setOnClickListener(v -> {
            Collections.swap(order, index, index - 1);
            rebuildRows();
        });
        Button down = new Button(ctx);
        down.setText("↓");
        down.setEnabled(index < order.size() - 1);
        down.setLayoutParams(new LinearLayout.LayoutParams(arrowWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        down.setOnClickListener(v -> {
            Collections.swap(order, index, index + 1);
            rebuildRows();
        });
        row.addView(title);
        row.addView(visible);
        row.addView(up);
        row.addView(down);
        return row;
    }
}
