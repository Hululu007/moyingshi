package com.github.moyingshi.osc.tv.ui.dialog;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.profile.ProfileCard;
import com.github.moyingshi.osc.tv.profile.ProfileLayout;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 我的页布局编辑器：10 张卡片按保存的顺序列出来，每行可上移/下移/隐藏；
 * 点完成或按返回都会保存并刷新我的页。
 */
public class ProfileEditorDialog {

    private final Context context;
    private final Runnable onChanged;
    private List<ProfileCard> order;
    private Set<ProfileCard> hidden;
    private LinearLayout container;

    public ProfileEditorDialog(@NonNull Context context, Runnable onChanged) {
        this.context = context;
        this.onChanged = onChanged;
    }

    public void show() {
        order = ProfileLayout.orderedCards();
        hidden = ProfileLayout.hiddenCards();
        ScrollView scroll = new ScrollView(context);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, padding / 2);
        scroll.addView(container);
        rebuildRows();
        WebHtvAlertDialogBuilder builder = new WebHtvAlertDialogBuilder(context, R.style.Theme_WebHTV_Dialog);
        builder.setTitle(R.string.profile_edit_layout);
        builder.setView(scroll);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            ProfileLayout.save(order, hidden);
            if (onChanged != null) onChanged.run();
        });
        dialog.show();
    }

    private void rebuildRows() {
        container.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            container.addView(buildRow(order.get(i), i));
        }
    }

    private View buildRow(ProfileCard card, int index) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(card.titleRes);
        title.setTextSize(15);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (hidden.contains(card)) title.setAlpha(0.45f);
        Button up = new Button(context);
        up.setText("↑");
        up.setEnabled(index > 0);
        up.setOnClickListener(v -> {
            Collections.swap(order, index, index - 1);
            rebuildRows();
        });
        Button down = new Button(context);
        down.setText("↓");
        down.setEnabled(index < order.size() - 1);
        down.setOnClickListener(v -> {
            Collections.swap(order, index, index + 1);
            rebuildRows();
        });
        Button hide = new Button(context);
        hide.setText(hidden.contains(card) ? R.string.profile_show : R.string.profile_hide);
        hide.setOnClickListener(v -> {
            if (hidden.contains(card)) hidden.remove(card);
            else hidden.add(card);
            rebuildRows();
        });
        row.addView(title);
        row.addView(up);
        row.addView(down);
        row.addView(hide);
        return row;
    }
}
