package com.github.moyingshi.osc.tv.module;

import android.app.Activity;

import androidx.fragment.app.Fragment;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;

/**
 * 模块未安装时的统一提示（2026-11-25 19:31 用户补充）：
 * 文案用用户原话“请到模块里面下载”+ 模块名（{@code R.string.module_missing_prompt}）；
 * 带“去下载”按钮，点它直接打开设置里的“模块”页并尽量直达该模块那一行；
 * 取消/关闭后按原有回落走（内核回落 EXO、功能置灰），不反复弹。
 *
 * <p>只在“用户主动选了未安装的模块”时调用；模块已安装时调用方不要弹。
 * 提示本身不做下载，下载在模块页里做（在线下载 / 本地导入二选一）。
 */
public final class ModulePrompt {

    private ModulePrompt() {
    }

    public static void showMissingModule(Activity activity, ModuleDefinition definition) {
        showMissingModule(activity, definition, null);
    }

    public static void showMissingModule(Activity activity, ModuleDefinition definition,
                                         Runnable onCancel) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || definition == null) {
            if (onCancel != null) onCancel.run();
            return;
        }
        show(activity, definition, onCancel);
    }

    public static void showMissingModule(Fragment fragment, ModuleDefinition definition) {
        showMissingModule(fragment, definition, null);
    }

    public static void showMissingModule(Fragment fragment, ModuleDefinition definition,
                                         Runnable onCancel) {
        if (fragment == null || !fragment.isAdded() || definition == null) {
            if (onCancel != null) onCancel.run();
            return;
        }
        show(fragment.requireActivity(), definition, onCancel);
    }

    private static void show(Activity activity, ModuleDefinition definition, Runnable onCancel) {
        new WebHtvAlertDialogBuilder(activity)
                .setMessage(activity.getString(R.string.module_missing_prompt,
                        definition.displayName))
                .setPositiveButton(R.string.module_go_download,
                        (dialog, which) -> ModuleActivity.start(activity, definition.id))
                .setNegativeButton(android.R.string.cancel,
                        (dialog, which) -> {
                            if (onCancel != null) onCancel.run();
                        })
                .setOnCancelListener(dialog -> {
                    if (onCancel != null) onCancel.run();
                })
                .show();
    }
}
