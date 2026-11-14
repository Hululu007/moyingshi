package com.github.moyingshi.osc.tv.ui.activity;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.github.moyingshi.osc.tv.ui.dialog.LocalSourceDialog;

/**
 * 本地源弹窗宿主（mobile 专属透明 Activity）：只负责把 LocalSourceDialog 弹出来。
 *
 * 实验室页在 main 源集，不能直接引用 mobile 的 DialogFragment，所以工具栏 + 按钮走隐式 intent
 * （action 是 ${applicationId}.local_source_dialog，与海阔小程序管理页同款）到这里。
 * 弹窗关掉时宿主就 finish，背后仍是实验室页；旋转重建时弹窗由 FragmentManager 自己恢复，
 * 不再重复 show。
 */
public class LocalSourceDialogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                if (f instanceof LocalSourceDialog) finish();
            }
        }, false);
        if (savedInstanceState == null) {
            LocalSourceDialog.create().show(getSupportFragmentManager(), "LocalSourceDialog");
        }
    }
}
