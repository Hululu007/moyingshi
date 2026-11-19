package com.github.moyingshi.osc.tv.api.hk;
/** 移植自星辰 app/src/main/java/com/fongmi/android/tv/api/hk/HkErrorUtil.java（284 行）：除包名与 §3.3 三处适配（ToastUtil→Toast、两处 R.layout 改程序化建视图、HkDialogs→AlertDialog.Builder）外与原文一致；详见 docs/miniapp-xingchen-5a-router-detail-play.md。 */

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;


import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 规则错误弹窗。一比一对照 8.83 反编译原文：
 * smali_classes2/com/example/hikerview/utils/DebugUtil.smali
 * （showErrorMsg / lambda$showErrorMsg$3 / smartCheck / delRuleByCode）。
 *
 * 两层：
 * 1. 底部弹窗（对等 CustomBottomPopup + view_msg_bottom_pop.xml），5 秒自动消失；
 * 2. 点"查看详情"后弹出 AlertDialog，标题"错误详细信息"，按钮"报告作者/忽略/删除规则"。
 */
public class HkErrorUtil {

    private HkErrorUtil() {
    }

    /** 墨影视适配：我方无星辰 ToastUtil 工具类，等价为 Toast.makeText().show()。 */
    private static void toast(Context context, String text, int duration) {
        try {
            Toast.makeText(context, text == null ? "" : text, duration).show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 对等 DebugUtil.showErrorMsg(Activity, Context, String, String, String, Throwable)。
     *
     * @param activity  当前 Activity
     * @param errorTitle 错误标题（一般为"规则执行过程中出现错误"）
     * @param ruleTitle  规则标题（用于判断是否显示"删除规则"）
     * @param errorMsg   错误描述
     * @param throwable  异常堆栈，可空
     */
    public static void showErrorMsg(Activity activity, String errorTitle, String ruleTitle, String errorMsg, Throwable throwable) {
        try {
            // 对等 smali 69-73 行：规则有误的特殊文案替换
            if ("规则执行过程中出现错误".equals(errorTitle) && !TextUtils.isEmpty(errorMsg)
                    && errorMsg.contains("\" is not defined.")) {
                String[] parts = errorMsg.split("\" is not defined");
                if (parts.length > 1) {
                    errorTitle = "规则有误或者软件版本过低";
                }
            }
            final String title = errorTitle;
            if (!(activity instanceof FragmentActivity)) return;
            FragmentActivity fa = (FragmentActivity) activity;
            if (fa.isFinishing() || fa.isDestroyed()) return;
            fa.runOnUiThread(() -> {
                try {
                    BottomErrorDialog dialog = BottomErrorDialog.newInstance(title, ruleTitle, errorMsg, throwable);
                    dialog.show(fa.getSupportFragmentManager(), "hk_error_bottom");
                } catch (Throwable showFailed) {
                    // 本函数就是错误提示的出口：弹窗出不来时错误不能跟着一起消失，
                    // 退到本域既有的 Toast 提示形，把标题与描述直接给用户看。
                    toast(activity, title + (TextUtils.isEmpty(errorMsg) ? "" : "：" + errorMsg), Toast.LENGTH_LONG);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 对等 DebugUtil.showErrorMsg(Activity, String, Throwable) */
    public static void showErrorMsg(Activity activity, String errorMsg, Throwable throwable) {
        showErrorMsg(activity, "规则执行过程中出现错误", "", errorMsg, throwable);
    }

    /** 对等 DebugUtil.showErrorMsg(Activity, Throwable) */
    public static void showErrorMsg(Activity activity, Throwable throwable) {
        String msg = throwable == null ? "" : String.valueOf(throwable.getMessage());
        showErrorMsg(activity, "规则执行过程中出现错误", "", msg, throwable);
    }

    /**
     * 对等 DebugUtil.smartCheck(StringBuilder, String)。
     * 按错误描述追加提示语。
     */
    private static void smartCheck(StringBuilder sb, String desc) {
        if (TextUtils.isEmpty(desc)) return;
        if (desc.contains("syntax error") || desc.contains("is not defined") || desc.contains("JSEngine")) {
            sb.append("\n提示：JS语法错误，请联系规则作者");
            return;
        }
        if (desc.contains("http response code is") || desc.contains("HttpRequestError")) {
            sb.append("\n提示：疑似网站已关闭或者规则已失效，建议删除规则或者联系规则作者");
            return;
        }
        if (desc.contains("org.jsoup.")) {
            sb.append("\n提示：规则已失效，请联系规则作者");
        }
    }

    /**
     * 对等 lambda$showErrorMsg$3：拼装详情文本。
     * "错误：" + errorTitle + smartCheck + ("\n描述：" + desc) + "\n堆栈：" + stackTrace
     */
    private static String buildDetailText(String errorTitle, String desc, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("错误：").append(errorTitle == null ? "" : errorTitle);
        smartCheck(sb, desc);
        if (TextUtils.equals(desc, errorTitle)) {
            sb.append("\n堆栈：");
        } else {
            sb.append("\n描述：").append(desc == null ? "" : desc).append("\n堆栈：");
        }
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            Throwable cause = throwable.getCause();
            while (cause != null) {
                cause.printStackTrace(pw);
                cause = cause.getCause();
            }
            pw.close();
            String stack = sw.toString();
            // 对等 smali 98-99 行：JS 错误时从堆栈里去掉错误正文
            if (errorTitle != null && errorTitle.contains("来源：") && errorTitle.contains("行数：")) {
                stack = stack.replace(errorTitle, "");
            }
            sb.append(stack);
        }
        // 排障：Empty JSON 大概率是 fetch 拿到空数据，附上最近一次网络诊断（host/状态码/异常）
        try {
            if (desc != null && desc.contains("Empty JSON")) {
                String diag = HkJsRuntime.getLastFetchDiag();
                if (diag != null && !diag.isEmpty()) {
                    sb.append("\n网络诊断：").append(diag);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 对等 lambda$showErrorMsg$0：报告作者 = 复制到剪贴板 */
    private static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast(context, "复制失败", Toast.LENGTH_SHORT);
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("error", text == null ? "" : text));
        } catch (Throwable t) {
            toast(context, "复制失败", Toast.LENGTH_SHORT);
        }
    }

    /**
     * 对等 DebugUtil.delRuleByCode：按标题删除规则。
     * 8.83 从标题去掉 "home@" 前缀后按 title 查库删除；星辰用 HkRuleManager.delete(title)。
     */
    private static void delRuleByTitle(Context context, String ruleTitle) {
        try {
            String title = ruleTitle == null ? "" : ruleTitle.replace("home@", "");
            boolean ok = HkRuleManager.get().delete(title);
            toast(context, ok ? "已删除规则" : "找不到规则：" + title, Toast.LENGTH_SHORT);
        } catch (Throwable t) {
            toast(context, "删除失败", Toast.LENGTH_SHORT);
        }
    }

    /** 第一层：底部弹窗。对等 CustomBottomPopup + view_msg_bottom_pop.xml。 */
    public static class BottomErrorDialog extends DialogFragment {

        private static final long AUTO_DISMISS_MS = 5000; // 对等 delayDismiss(0x1388)

        private String title;
        private String ruleTitle;
        private String errorMsg;
        private Throwable throwable;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable autoDismiss = this::dismissAllowingStateLoss;

        static BottomErrorDialog newInstance(String title, String ruleTitle, String errorMsg, Throwable throwable) {
            BottomErrorDialog d = new BottomErrorDialog();
            d.title = title;
            d.ruleTitle = ruleTitle;
            d.errorMsg = errorMsg;
            d.throwable = throwable;
            return d;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            Dialog dialog = new Dialog(requireContext());
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            // 墨影视适配：R.layout.dialog_hk_error_bottom 在我方 res 内不存在（本阶段
            // scope 不含 res/），程序化建等价视图：标题 +「查看详情」「忽略」两按钮，
            // 文案与 5 秒自动消失行为一字不动。
            android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad, pad, pad);
            android.widget.TextView msgText = new android.widget.TextView(requireContext());
            msgText.setText(title == null ? "规则执行过程中出现错误" : title);
            msgText.setTextSize(15);
            root.addView(msgText);
            android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.Button showBtn = new android.widget.Button(requireContext());
            showBtn.setText("查看详情");
            android.widget.Button dismissBtn = new android.widget.Button(requireContext());
            dismissBtn.setText("忽略");
            row.addView(showBtn);
            row.addView(dismissBtn);
            root.addView(row);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setGravity(Gravity.BOTTOM);
                WindowManager.LayoutParams params = window.getAttributes();
                params.width = WindowManager.LayoutParams.MATCH_PARENT;
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(params);
            }
            // 对等 lambda$onCreate$0：点"查看详情" -> 关弹窗 -> 弹详情对话框
            showBtn.setOnClickListener(v -> {
                handler.removeCallbacks(autoDismiss);
                dismissAllowingStateLoss();
                showDetail();
            });
            // 对等 lambda$onCreate$1：点"忽略" -> 关弹窗
            dismissBtn.setOnClickListener(v -> {
                handler.removeCallbacks(autoDismiss);
                dismissAllowingStateLoss();
            });
            return dialog;
        }

        @Override
        public void onStart() {
            super.onStart();
            // 对等 delayDismiss(5000)：5 秒后自动消失
            handler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
        }

        @Override
        public void onDestroyView() {
            handler.removeCallbacks(autoDismiss);
            super.onDestroyView();
        }

        private void showDetail() {
            Context context = requireContext();
            String detailText = buildDetailText(title, errorMsg, throwable);
            // 墨影视适配：R.layout.dialog_hk_error_detail 不存在，程序化建等价视图；
            // 我方 ui/hk/HkDialogs 是自研 final 工具类而非 AlertDialog.Builder，
            // 改用 androidx AlertDialog.Builder 直建，按钮与文案一字不动。
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            android.widget.TextView tv = new android.widget.TextView(context);
            tv.setText(detailText);
            // 1.3.47 只动颜色：自定义布局用 Activity 上下文 inflate，不继承弹窗深色主题字色，
            // 深色毛玻璃底上字发暗。提亮到深色弹窗标准字色 #F2F4F8（对等
            // ThemeOverlay.XingChen.GlassDialog.Dark 的 android:textColor）。
            // 内容、堆栈文本、按钮、布局结构一字不动。
            tv.setTextColor(0xFFF2F4F8);
            scroll.addView(tv);
            androidx.appcompat.app.AlertDialog.Builder hkBuilder =
                    new androidx.appcompat.app.AlertDialog.Builder(context);
            hkBuilder.setTitle("错误详细信息")
                    .setView(scroll)
                    .setCancelable(true)
                    // 对等 setPositiveButton("报告作者")：复制详情文本
                    .setPositiveButton("报告作者", (d, w) -> {
                        copyToClipboard(context, detailText);
                        d.dismiss();
                    })
                    // 对等 setNegativeButton("忽略")
                    .setNegativeButton("忽略", (d, w) -> d.dismiss());
            // 对等 smali 117-118 行：仅当规则标题以 "home@" 开头时显示"删除规则"
            if (!TextUtils.isEmpty(ruleTitle) && ruleTitle.startsWith("home@")) {
                final String rt = ruleTitle;
                hkBuilder.setNeutralButton("删除规则", (d, w) -> delRuleByTitle(context, rt));
            }
            try {
                hkBuilder.create().show();
            } catch (Throwable ignored) {
            }
        }
    }
}
