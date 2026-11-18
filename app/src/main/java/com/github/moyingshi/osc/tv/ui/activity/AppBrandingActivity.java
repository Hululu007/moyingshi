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
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.utils.Util;

public class AppBrandingActivity extends BaseActivity {

    private ActivityAppBrandingBinding mBinding;
    private int selectedIconMode;

    public static void start(Activity activity) {
        // B8 B15: mobile opens the Flutter branding page (leanback guard
        // keeps TV native). Neither caller is finished here: a C1 caller
        // (B5 personal settings page) must stay alive — C1 is singleTop,
        // start() reuses it via onNewIntent, and finishing it would drop
        // the user to the launcher (see docs/b8-crash-singletop-fix.md);
        // a native caller keeps native behavior (the branding page covers
        // the top, back returns to the settings page).
        if (!Util.isLeanback()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_BRANDING);
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
