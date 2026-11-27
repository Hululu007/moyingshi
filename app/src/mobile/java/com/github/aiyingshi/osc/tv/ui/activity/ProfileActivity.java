package com.github.aiyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.github.aiyingshi.osc.tv.R;
import com.github.aiyingshi.osc.tv.databinding.ActivityProfileBinding;
import com.github.aiyingshi.osc.tv.ui.base.BaseActivity;
import com.github.aiyingshi.osc.tv.ui.fragment.ProfileFragment;

/** 我的页宿主：仿 DiceActivity 承载 ProfileFragment，手机端。 */
public class ProfileActivity extends BaseActivity {

    private ActivityProfileBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ProfileActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityProfileBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        getSupportFragmentManager().beginTransaction().replace(R.id.container, ProfileFragment.newInstance(), "0").commit();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }
}
