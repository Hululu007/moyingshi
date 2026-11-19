package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;

/**
 * 海阔敏感 API 授权弹窗（简化版）。
 * 标题：规则请求授权；内容：API 名称 + 说明；按钮：允许 / 拒绝。
 *
 * 移植自星辰 app/src/main/java/com/fongmi/android/tv/api/hk/HkAuthDialog.java（53 行），
 * 除包名与一处适配外与原文一致；详见 docs/miniapp-xingchen-6c-auth.md。
 * 适配：星辰用 HkDialogs（5e 已删），我方改用原生 AlertDialog.Builder（同标题/同文案/
 * 同按钮、setCancelable(false)；无宿主/异常时回调 onDeny 不变）。其 import 的
 * AlertDialog 在星辰版里是闲置的，在我方版里正用上。
 */
public class HkAuthDialog {

    public interface Callback {
        void onAllow();
        void onDeny();
    }

    public static void show(Activity activity, String ruleTitle, String apiName, String description, Callback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (callback != null) callback.onDeny();
            return;
        }
        String title = "规则请求授权";
        StringBuilder msg = new StringBuilder();
        if (!TextUtils.isEmpty(ruleTitle)) {
            msg.append("规则：").append(ruleTitle).append("\n\n");
        }
        msg.append("请求使用：").append(apiName == null ? "" : apiName);
        if (!TextUtils.isEmpty(description)) {
            msg.append("\n\n").append(description);
        }
        // 高风险 API 额外警告
        if ("findJavaClass".equals(apiName) || "loadJavaClass".equals(apiName)) {
            msg.append("\n\n⚠️ 高风险操作：此操作可执行任意代码，仅允许信任的规则！");
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(msg.toString())
                    .setCancelable(false)
                    .setPositiveButton("允许", (d, w) -> {
                        if (callback != null) callback.onAllow();
                    })
                    .setNegativeButton("拒绝", (d, w) -> {
                        if (callback != null) callback.onDeny();
                    })
                    .show();
        } catch (Throwable e) {
            if (callback != null) callback.onDeny();
        }
    }
}
