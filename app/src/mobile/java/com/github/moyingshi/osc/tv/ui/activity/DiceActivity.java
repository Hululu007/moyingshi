package com.github.moyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.ActivityDiceBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.ui.fragment.DiceFragment;

/** 喜好选片页宿主：仿 FolderActivity 承载 DiceFragment。 */
public class DiceActivity extends BaseActivity {

    private ActivityDiceBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DiceActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDiceBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        getSupportFragmentManager().beginTransaction().replace(R.id.container, DiceFragment.newInstance(), "0").commit();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }
}
