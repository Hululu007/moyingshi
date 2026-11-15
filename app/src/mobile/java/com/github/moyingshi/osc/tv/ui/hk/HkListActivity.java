package com.github.moyingshi.osc.tv.ui.hk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.ActivityHkListBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;

/**
 * 海阔小程序列表页（mobile 专属），对应魔影的 HkListActivity（见逆向报告 §7.3）。
 * <p>
 * 壳子只做三件事：Toolbar 显示规则标题 + 返回键；把 {@link HkListFragment}
 * 挂到 {@code hk_list_container}；壁纸由 {@link BaseActivity} 在
 * {@code setContentView} 时自动叠加。取数、分类、翻页都在 Fragment 里。
 * <p>
 * 启动只传规则标题（extra {@code "title"}），不传整条规则 JSON——规则 JSON
 * 可达几十 KB，页内用 {@code HkStore.read(title)} 再读。main 源集（实验室页）
 * 用隐式 intent（action {@code ${applicationId}.hk_list}）启动，mobile 内
 * 直接调 {@link #open(Context, String)}。
 * <p>
 * 点卡片进详情是第 4 步；本步点卡片只弹“详情页还在做”。
 */
public class HkListActivity extends BaseActivity {

    /** 隐式启动的 action 后缀（完整 action = applicationId + ACTION_SUFFIX）。 */
    public static final String ACTION_SUFFIX = ".hk_list";

    /** Intent extra：规则标题（hk:// 后面的部分）。 */
    public static final String EXTRA_TITLE = "title";

    private ActivityHkListBinding binding;

    /** mobile 内启动列表页：传规则标题即可。 */
    public static void open(Context context, String title) {
        Intent intent = new Intent(context, HkListActivity.class);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        if (binding == null) {
            binding = ActivityHkListBinding.inflate(getLayoutInflater());
        }
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "";
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.hk_list_container, HkListFragment.newInstance(title))
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
