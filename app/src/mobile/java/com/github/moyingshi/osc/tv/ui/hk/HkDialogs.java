package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 海阔小程序的弹框（第 7 步之七：动作 URL 全套）。
 * <p>
 * 只分两层：异步回调核心（供卡片点击流程，主线程调）+ 同步 latch 包装
 * （供 Rhino 桥 {@code confirm/select/input}，后台线程调）。
 * 全部用项目现有的 {@link WebHtvAlertDialogBuilder}，输入框复用
 * {@code dialog_action_input.xml}（与 {@code ActionCardHelper} 同款布局与
 * “空输入不关框”规则），不另起一套 UI；dialog 根布局与窗口背景走 Builder
 * 自带的不透明主题，不新写布局。
 * <p>
 * 取消语义（三种取消路径都一样）：确认框的取消按钮、返回键、点框外 →
 * 回调收到取消默认值（input/select 回 null，confirm 回 false），调用方
 * 中断当前链路、不崩。同步版在主线程调直接回取消默认值（不能在主线程
 * latch 等自己）；Activity 已 finish/destroyed 时同样直接回取消默认值。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkDialogs {

    private HkDialogs() {
    }

    /** Activity 还能不能弹框。 */
    private static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** 回调只投递一次（确认投递后 dismiss 触发的 onDismiss 不再投递取消值）。 */
    private static final class Once<T> {
        private final AtomicBoolean delivered = new AtomicBoolean(false);
        private final Consumer<T> target;

        Once(Consumer<T> target) {
            this.target = target;
        }

        void deliver(T value) {
            if (delivered.compareAndSet(false, true) && target != null) target.accept(value);
        }
    }

    /** 确保在主线程执行（已在主线程直接跑，否则 post 到主线程）。 */
    private static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else App.post(r);
    }

    /**
     * 输入框（异步）：标题 + 提示 + 预填值；确认回输入文本，取消回 null。
     * 空输入弹“内容不能为空”且不关框（与 ActionCardHelper 同款）。
     */
    public static void inputAsync(Activity activity, String title, String hint, String preset,
                                  Consumer<String> onResult) {
        Once<String> once = new Once<>(onResult);
        if (!usable(activity)) {
            once.deliver(null);
            return;
        }
        onMain(() -> {
            if (!usable(activity)) {
                once.deliver(null);
                return;
            }
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_action_input, null);
            EditText edit = view.findViewById(R.id.action_input);
            if (!TextUtils.isEmpty(preset)) edit.setText(preset);
            if (!TextUtils.isEmpty(hint)) edit.setHint(hint);
            AlertDialog dialog = new WebHtvAlertDialogBuilder(activity)
                    .setTitle(TextUtils.isEmpty(title) ? activity.getString(R.string.hk_action_input_title) : title)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnDismissListener((DialogInterface d) -> once.deliver(null));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = edit.getText().toString().trim();
                if (TextUtils.isEmpty(text)) {
                    Notify.show(R.string.hk_action_input_empty);
                    return;
                }
                once.deliver(text);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
        });
    }

    /**
     * 确认框（异步）：确认回 true，取消/返回键/点框外回 false。
     */
    public static void confirmAsync(Activity activity, String title, String message,
                                    Consumer<Boolean> onResult) {
        Once<Boolean> once = new Once<>(onResult);
        if (!usable(activity)) {
            once.deliver(false);
            return;
        }
        onMain(() -> {
            if (!usable(activity)) {
                once.deliver(false);
                return;
            }
            AlertDialog dialog = new WebHtvAlertDialogBuilder(activity)
                    .setTitle(TextUtils.isEmpty(title) ? activity.getString(R.string.hk_action_confirm_title) : title)
                    .setMessage(message == null ? "" : message)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnDismissListener((DialogInterface d) -> once.deliver(false));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                once.deliver(true);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
        });
    }

    /**
     * 选项列表（异步）：点某一项回该项文本，取消/返回键/点框外回 null。
     * options 为空时不弹框，直接回 null。
     */
    public static void selectAsync(Activity activity, String title, List<String> options,
                                   Consumer<String> onResult) {
        Once<String> once = new Once<>(onResult);
        if (options == null || options.isEmpty() || !usable(activity)) {
            once.deliver(null);
            return;
        }
        onMain(() -> {
            if (!usable(activity)) {
                once.deliver(null);
                return;
            }
            CharSequence[] items = options.toArray(new CharSequence[0]);
            AlertDialog dialog = new WebHtvAlertDialogBuilder(activity)
                    .setTitle(TextUtils.isEmpty(title) ? activity.getString(R.string.hk_action_select_title) : title)
                    .setItems(items, (d, which) -> {
                        if (which >= 0 && which < options.size()) once.deliver(options.get(which));
                        else once.deliver(null);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnDismissListener((DialogInterface d) -> once.deliver(null));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
        });
    }

    /** 同步等一个异步回调的结果（后台线程用；主线程调直接回取消默认值，不 latch）。 */
    private static <T> T awaitResult(Consumer<Consumer<T>> starter, T cancelled) {
        if (Looper.myLooper() == Looper.getMainLooper()) return cancelled;
        AtomicReference<T> result = new AtomicReference<>(cancelled);
        CountDownLatch latch = new CountDownLatch(1);
        starter.accept(value -> {
            result.set(value);
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelled;
        }
        return result.get();
    }

    /** 输入框（同步，后台线程调）：返回输入文本，取消回 null。 */
    public static String input(Activity activity, String title, String hint, String preset) {
        return awaitResult(cb -> inputAsync(activity, title, hint, preset, cb), null);
    }

    /** 确认框（同步，后台线程调）：返回是否确认。 */
    public static boolean confirm(Activity activity, String title, String message) {
        Boolean result = awaitResult(cb -> confirmAsync(activity, title, message, cb), Boolean.FALSE);
        return result != null && result;
    }

    /** 选项列表（同步，后台线程调）：返回选中项文本，取消回 null。 */
    public static String select(Activity activity, String title, List<String> options) {
        return awaitResult(cb -> selectAsync(activity, title, options, cb), null);
    }
}
