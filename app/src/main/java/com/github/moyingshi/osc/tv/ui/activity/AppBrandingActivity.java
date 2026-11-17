package com.github.moyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.ActivityAppBrandingBinding;
import com.github.moyingshi.osc.tv.flutter.C1ScreenActivity;
import com.github.moyingshi.osc.tv.setting.AppBranding;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.utils.Util;

public class AppBrandingActivity extends BaseActivity {

    private ActivityAppBrandingBinding mBinding;
    private int selectedIconMode;

    public static void start(Activity activity) {
        // B8 B15: B6→B7 同款预授权分流（见 docs/b8-b15-local.md §5 V4）。
        // 开关开时走 Flutter 应用图标页（mobile only — leanback 守卫让
        // TV 保持原生）。调用方是 Flutter 屏（B5 个人设置页）时与 B7 的
        // openAbout 同为 start-then-finish，G2 跨屏竞态风险同款——返回
        // 时落在原生个人设置页；调用方是原生 Activity 时只 start 不
        // finish（原生行为：图标页盖在上面，返回回到设置页）。
        if (Setting.isFlutterB15Enabled() && !Util.isLeanback()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_BRANDING);
            if (activity instanceof C1ScreenActivity) activity.finish();
            return;
        }
        activity.startActivity(new Intent(activity, AppBrandingActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAppBrandingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        selectedIconMode = AppBranding.getIconMode(this);
        updateSelection();
        mBinding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    protected void initEvent() {
        mBinding.iconCurrent.setOnClickListener(v -> selectIcon(AppBranding.ICON_CURRENT));
        mBinding.iconHistory.setOnClickListener(v -> selectIcon(AppBranding.ICON_HISTORY));
        mBinding.cancel.setOnClickListener(v -> finish());
        mBinding.save.setOnClickListener(v -> save());
    }

    private void selectIcon(int mode) {
        selectedIconMode = mode;
        updateSelection();
    }

    private void updateSelection() {
        setSelected(mBinding.iconCurrent, mBinding.iconCurrentText, selectedIconMode == AppBranding.ICON_CURRENT);
        setSelected(mBinding.iconHistory, mBinding.iconHistoryText, selectedIconMode == AppBranding.ICON_HISTORY);
    }

    private void setSelected(LinearLayoutCompat container, TextView label, boolean selected) {
        container.setSelected(selected);
        label.setTextColor(selected ? getColor(R.color.display_option_bg_selected) : getColor(R.color.white));
    }

    private void save() {
        AppBranding.putIconMode(selectedIconMode);
        AppBranding.applyLauncherIcon(this);
        setResult(RESULT_OK);
        Intent intent = AppBranding.launcherIntent(this)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
