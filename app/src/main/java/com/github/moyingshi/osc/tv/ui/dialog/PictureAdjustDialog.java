package com.github.moyingshi.osc.tv.ui.dialog;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.fragment.app.FragmentActivity;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.player.PlayerManager;
import com.github.moyingshi.osc.tv.player.picture.PictureAdjust;
import com.github.moyingshi.osc.tv.player.picture.PictureAdjustSetting;
import com.github.moyingshi.osc.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textview.MaterialTextView;

/**
 * 画面调节面板（B6）。五条滑杆（亮度/对比度/饱和度/色相/伽马，-100..100）+ Anime4K 开关 + 重置。
 * 拖动即实时生效（调 PlayerManager.setPictureAdjust，值变更即持久化）；面板不做“取消恢复”。
 * 滑杆/Slider 样式抄 SliderNumberDialog（代码拼布局，无新 XML）。
 */
public final class PictureAdjustDialog {

    private static final int[] NAME_RES = new int[]{
            R.string.pic_brightness, R.string.pic_contrast, R.string.pic_saturation,
            R.string.pic_hue, R.string.pic_gamma};

    private PictureAdjustDialog() {
    }

    public static void show(FragmentActivity activity, PlayerManager player) {
        if (activity == null || player == null) return;
        PictureAdjust current = PictureAdjustSetting.getAdjust();
        String adjustReason = player.getPictureAdjustUnavailableReason();
        String anime4kReason = player.getAnime4kUnavailableReason();
        int[] values = new int[]{
                current.getBrightness(), current.getContrast(), current.getSaturation(),
                current.getHue(), current.getGamma()};

        LinearLayout container = new LinearLayout(activity);
        container.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = ResUtil.dp2px(16);
        container.setPadding(horizontal, 0, horizontal, 0);

        if (adjustReason != null) {
            container.addView(reasonView(activity, adjustReason));
        }

        MaterialTextView[] labels = new MaterialTextView[NAME_RES.length];
        Slider[] sliders = new Slider[NAME_RES.length];
        for (int i = 0; i < NAME_RES.length; i++) {
            MaterialTextView label = new MaterialTextView(activity);
            label.setText(formatLabel(activity, NAME_RES[i], values[i]));
            label.setTextColor(Color.parseColor("#202124"));
            label.setTextSize(15);
            container.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            labels[i] = label;

            Slider slider = new Slider(activity);
            slider.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            slider.setValueFrom(PictureAdjust.MIN);
            slider.setValueTo(PictureAdjust.MAX);
            slider.setStepSize(1);
            slider.setTickVisible(false);
            slider.setValue(values[i]);
            slider.setFocusable(true);
            slider.setFocusableInTouchMode(true);
            slider.setEnabled(adjustReason == null);
            ColorStateList active = ColorStateList.valueOf(Color.parseColor("#0B57D0"));
            ColorStateList inactive = ColorStateList.valueOf(Color.parseColor("#D0D5DD"));
            slider.setTrackActiveTintList(active);
            slider.setTrackInactiveTintList(inactive);
            slider.setThumbTintList(active);
            slider.setHaloTintList(active);
            container.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sliders[i] = slider;
        }

        SwitchMaterial anime4k = new SwitchMaterial(activity);
        anime4k.setText(R.string.pic_anime4k);
        anime4k.setChecked(PictureAdjustSetting.isAnime4kEnabled());
        anime4k.setEnabled(anime4kReason == null);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.topMargin = ResUtil.dp2px(8);
        container.addView(anime4k, switchParams);

        MaterialTextView hint = new MaterialTextView(activity);
        hint.setText(anime4kReason == null ? activity.getString(R.string.pic_anime4k_hint) : anime4kReason);
        hint.setTextColor(Color.parseColor("#5F6368"));
        hint.setTextSize(13);
        container.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton reset = new MaterialButton(activity);
        reset.setText(R.string.pic_reset);
        reset.setCornerRadius(ResUtil.dp2px(18));
        reset.setOnClickListener(v -> {
            for (Slider slider : sliders) slider.setValue(0);
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = ResUtil.dp2px(12);
        container.addView(reset, resetParams);

        Runnable apply = () -> player.setPictureAdjust(readAdjust(sliders), anime4k.isChecked());
        for (int i = 0; i < sliders.length; i++) {
            int index = i;
            sliders[i].addOnChangeListener((source, value, fromUser) -> {
                labels[index].setText(formatLabel(activity, NAME_RES[index], Math.round(value)));
                apply.run();
            });
        }
        anime4k.setOnCheckedChangeListener((button, checked) -> apply.run());

        Dialog[] holder = new Dialog[1];
        Dialog dialog = LightDialog.create(activity, activity.getString(R.string.play_picture_adjust), container,
                activity.getString(R.string.dialog_positive), v -> {
                    if (holder[0] != null) holder[0].dismiss();
                }, null, null);
        holder[0] = dialog;
        dialog.setOnShowListener(d -> {
            if (sliders.length > 0) sliders[0].requestFocus();
        });
        dialog.show();
    }

    private static PictureAdjust readAdjust(Slider[] sliders) {
        return new PictureAdjust(
                Math.round(sliders[0].getValue()),
                Math.round(sliders[1].getValue()),
                Math.round(sliders[2].getValue()),
                Math.round(sliders[3].getValue()),
                Math.round(sliders[4].getValue()));
    }

    private static String formatLabel(FragmentActivity activity, int nameRes, int value) {
        return activity.getString(nameRes) + "  " + (value > 0 ? "+" : "") + value;
    }

    private static MaterialTextView reasonView(FragmentActivity activity, String reason) {
        MaterialTextView view = new MaterialTextView(activity);
        view.setText(reason);
        view.setTextColor(Color.parseColor("#5F6368"));
        view.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ResUtil.dp2px(8);
        view.setLayoutParams(params);
        return view;
    }
}
