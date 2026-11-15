package com.github.moyingshi.osc.tv.ui.hk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.databinding.ActivityHkSearchBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.utils.Notify;

/**
 * 海阔小程序搜索页（mobile 专属），对应魔影的 HkSearchActivity
 * （见逆向报告 §7.4：报告里是多引擎聚合 + 分组/模式 + 联想，本步只做
 * 单规则搜索，见 docs/miniapp-search.md）。
 * <p>
 * 壳子只做三件事：Toolbar 显示搜索框（返回 + EditText + 搜索按钮，照本 App
 * 自己的搜索页样式）+ 把 {@link HkSearchFragment} 挂到
 * {@code hk_search_container}；壁纸由 {@link BaseActivity} 在
 * {@code setContentView} 时自动叠加。取数、翻页、换词重搜都在 Fragment 里。
 * <p>
 * 启动只传规则标题（extra {@code "ruleTitle"}）+ 可选初始关键词（extra
 * {@code "wd"}），不传整条规则 JSON——与列表页/详情页同款理由。
 * 无搜索规则（search_url/searchFind 任一为空）时进页显示空态、不崩，
 * 见 Fragment。
 * <p>
 * 点卡片进详情是第 4 步已有的 {@link HkDetailActivity}，直接复用。
 */
public class HkSearchActivity extends BaseActivity {

    /** Intent extra：规则标题（hk:// 后面的部分）。 */
    public static final String EXTRA_RULE_TITLE = "ruleTitle";
    /** Intent extra：初始关键词（可空；报告 Z0 的 wd 对应物）。 */
    public static final String EXTRA_KEYWORD = "wd";

    private ActivityHkSearchBinding binding;

    /** mobile 内启动搜索页：传规则标题即可（不带初始关键词）。 */
    public static void open(Context context, String ruleTitle) {
        open(context, ruleTitle, null);
    }

    /** mobile 内启动搜索页：带初始关键词时进页即搜一次。 */
    public static void open(Context context, String ruleTitle, String keyword) {
        Intent intent = new Intent(context, HkSearchActivity.class);
        intent.putExtra(EXTRA_RULE_TITLE, ruleTitle);
        if (keyword != null) intent.putExtra(EXTRA_KEYWORD, keyword);
        context.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        if (binding == null) {
            binding = ActivityHkSearchBinding.inflate(getLayoutInflater());
        }
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String title = getIntent().getStringExtra(EXTRA_RULE_TITLE);
        if (title == null) title = "";
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }
        String keyword = getIntent().getStringExtra(EXTRA_KEYWORD);
        if (keyword != null) binding.keyword.setText(keyword);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.hk_search_container, HkSearchFragment.newInstance(title, keyword))
                    .commit();
        }
        binding.searchBtn.setOnClickListener(v -> submitSearch());
        binding.keyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch();
                return true;
            }
            return false;
        });
    }

    /** 提交搜索：空关键词只弹提示、不发请求（报告 b1() 同款文案）；否则收键盘、交给 Fragment 换词重搜。 */
    private void submitSearch() {
        String keyword = binding.keyword.getText() == null ? "" : binding.keyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            Notify.show(R.string.hk_search_empty_keyword);
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(binding.keyword.getWindowToken(), 0);
        HkSearchFragment fragment = (HkSearchFragment) getSupportFragmentManager().findFragmentById(R.id.hk_search_container);
        if (fragment != null) fragment.search(keyword);
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
