package com.github.moyingshi.osc.tv.ui.hk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.ActivityHkDetailBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;

/**
 * 海阔小程序详情页（mobile 专属）。
 * <p>
 * 壳子只做三件事：Toolbar 显示影片名 + 返回键；把 {@link HkDetailFragment}
 * 挂到 {@code hk_detail_container}；壁纸由 {@link BaseActivity} 在
 * {@code setContentView} 时自动叠加。取数、切分、渲染都在 Fragment 里。
 * <p>
 * 启动传规则标题 + 详情页地址 + 列表卡片的标题/海报/简介（信息卡缺失时的
 * 回落），不传整条规则 JSON——与列表页同款理由。点选集进播放是第 5 步，
 * 本步只弹“播放还在做”。
 */
public class HkDetailActivity extends BaseActivity {

    /** Intent extra：规则标题（hk:// 后面的部分）。 */
    public static final String EXTRA_RULE_TITLE = "ruleTitle";
    /** Intent extra：详情页地址（列表 Vod 的 id）。 */
    public static final String EXTRA_URL = "url";
    /** Intent extra：列表卡片的标题（信息卡缺失时的回落 + Toolbar 标题）。 */
    public static final String EXTRA_NAME = "name";
    /** Intent extra：列表卡片的海报（回落）。 */
    public static final String EXTRA_PIC = "pic";
    /** Intent extra：列表卡片的简介（回落）。 */
    public static final String EXTRA_DESC = "desc";

    private ActivityHkDetailBinding binding;

    /** mobile 内启动详情页。 */
    public static void open(Context context, String ruleTitle, String url, String name, String pic, String desc) {
        Intent intent = new Intent(context, HkDetailActivity.class);
        intent.putExtra(EXTRA_RULE_TITLE, ruleTitle);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_PIC, pic);
        intent.putExtra(EXTRA_DESC, desc);
        context.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        if (binding == null) {
            binding = ActivityHkDetailBinding.inflate(getLayoutInflater());
        }
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null) name = "";
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(name);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.hk_detail_container, HkDetailFragment.newInstance(
                            getIntent().getStringExtra(EXTRA_RULE_TITLE),
                            getIntent().getStringExtra(EXTRA_URL),
                            name,
                            getIntent().getStringExtra(EXTRA_PIC),
                            getIntent().getStringExtra(EXTRA_DESC)))
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
