package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityWidgetBrowseBinding;
import com.fongmi.android.tv.databinding.AdapterWidgetChipBinding;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.ui.adapter.WidgetGridAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.widget.WidgetCache;
import com.fongmi.android.tv.widget.WidgetHost;
import com.fongmi.android.tv.widget.WidgetItem;
import com.fongmi.android.tv.widget.WidgetManager;
import com.fongmi.android.tv.widget.WidgetMetadata;
import com.fongmi.android.tv.widget.WidgetModule;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 组件浏览页（B5）：一个组件的全部非分类模块 + 参数筛选 + 分页网格。
 *
 * <p>模块片横向排（只列非分类模块，分类模块是首页分类导航，v1 不进浏览页）；
 * 参数片横向排：enumeration 弹单选框、string 弹输入框，改值后回到第 1 页重查；
 * page/constant 参数不显示（分页由下滑到底自动加载下一页驱动，定值参数直接带上）。
 * 点条目与首页行一致：在当前资源里搜片名。
 */
public class WidgetBrowseActivity extends BaseActivity {

    public static void start(Activity activity, String widgetId) {
        Intent intent = new Intent(activity, WidgetBrowseActivity.class);
        intent.putExtra("widgetId", widgetId);
        activity.startActivity(intent);
    }

    private ActivityWidgetBrowseBinding mBinding;
    private WidgetGridAdapter mGridAdapter;
    private final List<WidgetItem> mItems = new ArrayList<>();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private WidgetHost mHost;
    private WidgetMetadata mMetadata;
    private String mWidgetId;

    private WidgetModule mModule;
    private final Map<String, String> mParams = new LinkedHashMap<>();
    private int mPage = 1;
    private boolean mLoading;
    private boolean mHasMore = true;
    private boolean mForceRefresh;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityWidgetBrowseBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mWidgetId = getIntent().getStringExtra("widgetId");
        setSupportActionBar(mBinding.toolbar);
        mBinding.moduleChips.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mBinding.paramChips.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        int column = MobileWindow.isWide(this) ? Product.getColumn(this) : 3;
        mBinding.grid.setHasFixedSize(true);
        mBinding.grid.setLayoutManager(new GridLayoutManager(this, column));
        mBinding.grid.setAdapter(mGridAdapter = new WidgetGridAdapter(this::onItemClick));
        mBinding.grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || mLoading || !mHasMore || mModule == null || !hasPageParam(mModule)) return;
                GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
                if (manager == null) return;
                int last = manager.findLastVisibleItemPosition();
                if (last >= mGridAdapter.getItemCount() - 6) loadPage(mPage + 1);
            }
        });
        loadWidget();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        if (mHost != null) mHost.destroy();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_widget_browse, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.refresh) {
            mForceRefresh = true;
            loadPage(1);
        }
        return super.onOptionsItemSelected(item);
    }

    /** 组件条目点进：在当前资源里搜片名（与首页行、B1 横幅一致）。 */
    private void onItemClick(WidgetItem item) {
        if (item == null || TextUtils.isEmpty(item.getTitle())) return;
        SearchActivity.start(this, item.getTitle());
    }

    /** 后台加载组件脚本并取元数据；失败则整页显示错误（首页行不受影响）。 */
    private void loadWidget() {
        showLoading(true);
        mExecutor.execute(() -> {
            try {
                WidgetManager.Builtin builtin = findBuiltin(mWidgetId);
                if (builtin == null) throw new IllegalStateException("未知组件: " + mWidgetId);
                String script = Asset.read(builtin.asset);
                if (TextUtils.isEmpty(script)) throw new IllegalStateException("组件脚本为空: " + builtin.asset);
                mHost = new WidgetHost(mWidgetId, script);
                mMetadata = mHost.load();
                runOnUiThread(() -> {
                    mBinding.toolbar.setTitle(mMetadata.getTitle());
                    selectModule(mMetadata.getHomeModule());
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    showEmpty(getString(R.string.widget_load_failed, e.getMessage()));
                });
            }
        });
    }

    private static WidgetManager.Builtin findBuiltin(String widgetId) {
        for (WidgetManager.Builtin builtin : WidgetManager.BUILTINS) {
            if (builtin.id.equals(widgetId)) return builtin;
        }
        return null;
    }

    /** 模块片：只列非分类模块；当前模块高亮。 */
    private void setModuleChips() {
        List<WidgetModule> modules = new ArrayList<>();
        for (WidgetModule module : mMetadata.getModules()) {
            if (!module.isCategory()) modules.add(module);
        }
        mBinding.moduleChips.setAdapter(new ChipAdapter(modules, module -> {
            if (mModule != module) selectModule(module);
        }, module -> module == mModule, module -> module.getTitle()));
    }

    private void selectModule(WidgetModule module) {
        if (module == null) {
            showEmpty(getString(R.string.widget_no_module));
            return;
        }
        mModule = module;
        mParams.clear();
        mParams.putAll(WidgetManager.defaultParams(module));
        setModuleChips();
        setParamChips();
        loadPage(1);
    }

    /** 参数片：只列当前值下可见的参数；片上显示“标题：当前值”。 */
    private void setParamChips() {
        List<JsonObject> params = WidgetManager.visibleParams(mModule, mParams);
        mBinding.paramChips.setAdapter(new ChipAdapter(params, this::onParamChipClick, param -> false, param -> {
            String title = Json.safeString(param, "title");
            if (TextUtils.isEmpty(title)) title = Json.safeString(param, "name");
            return title + "：" + paramDisplayValue(param);
        }));
        mBinding.paramChips.setVisibility(params.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 参数当前值的显示：enumeration 显示选项标题，string 显示原值（过长截断）。 */
    private String paramDisplayValue(JsonObject param) {
        String value = mParams.get(Json.safeString(param, "name"));
        if (value == null) value = "";
        if ("enumeration".equals(Json.safeString(param, "type")) && param.has("enumOptions")) {
            JsonArray options = param.getAsJsonArray("enumOptions");
            for (int i = 0; i < options.size(); i++) {
                JsonObject option = options.get(i).getAsJsonObject();
                if (value.equals(Json.safeString(option, "value"))) return Json.safeString(option, "title");
            }
        }
        if (value.length() > 8) value = value.substring(0, 8) + "…";
        return value.isEmpty() ? "全部" : value;
    }

    private void onParamChipClick(JsonObject param) {
        String name = Json.safeString(param, "name");
        String type = Json.safeString(param, "type");
        if ("enumeration".equals(type) && param.has("enumOptions")) {
            showEnumDialog(param, name);
        } else if ("string".equals(type)) {
            showStringDialog(param, name);
        }
    }

    private void showEnumDialog(JsonObject param, String name) {
        JsonArray options = param.getAsJsonArray("enumOptions");
        String[] titles = new String[options.size()];
        String[] values = new String[options.size()];
        String current = mParams.get(name);
        int checked = 0;
        for (int i = 0; i < options.size(); i++) {
            JsonObject option = options.get(i).getAsJsonObject();
            titles[i] = Json.safeString(option, "title");
            values[i] = Json.safeString(option, "value");
            if (values[i].equals(current)) checked = i;
        }
        new WebHtvAlertDialogBuilder(this)
                .setTitle(Json.safeString(param, "title"))
                .setSingleChoiceItems(titles, checked, (dialog, which) -> {
                    mParams.put(name, values[which]);
                    dialog.dismiss();
                    setParamChips();
                    loadPage(1);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void showStringDialog(JsonObject param, String name) {
        EditText input = new EditText(this);
        input.setText(mParams.get(name));
        input.setHint(Json.safeString(param, "description"));
        input.setSingleLine(true);
        new WebHtvAlertDialogBuilder(this)
                .setTitle(Json.safeString(param, "title"))
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    mParams.put(name, input.getText().toString().trim());
                    setParamChips();
                    loadPage(1);
                })
                .show();
    }

    /** 分页加载：第 1 页先贴缓存再刷新；后续页直接拉取、追加。失败只停分页，不清空已有内容。 */
    private void loadPage(int page) {
        if (mLoading || mModule == null || mHost == null) return;
        mLoading = true;
        if (page == 1) {
            mHasMore = true;
            showLoading(mItems.isEmpty());
        }
        Map<String, String> params = new LinkedHashMap<>(mParams);
        if (hasPageParam(mModule)) params.put("page", String.valueOf(page));
        boolean useCache = page == 1 && !mForceRefresh && !mModule.noCache();
        mExecutor.execute(() -> {
            try {
                if (useCache) {
                    String cached = WidgetCache.read(mWidgetId, mModule.getFunctionName(), WidgetManager.paramsKey(params), mModule.cacheTtlMs());
                    List<WidgetItem> cachedItems = parseItems(cached);
                    if (cachedItems != null && !cachedItems.isEmpty()) {
                        runOnUiThread(() -> applyItems(cachedItems, 1, true));
                    }
                }
                String json = mHost.invoke(mModule.getFunctionName(), params);
                List<WidgetItem> items = parseItems(json);
                if (items == null) throw new IllegalStateException("组件返回数据异常");
                runOnUiThread(() -> {
                    if (page == 1 && !mModule.noCache()) {
                        WidgetCache.write(mWidgetId, mModule.getFunctionName(), WidgetManager.paramsKey(params), json);
                    }
                    applyItems(items, page, false);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    mLoading = false;
                    showLoading(false);
                    if (page == 1 && mItems.isEmpty()) showEmpty(getString(R.string.widget_load_failed, e.getMessage()));
                });
            } finally {
                mForceRefresh = false;
            }
        });
    }

    private void applyItems(List<WidgetItem> items, int page, boolean fromCache) {
        mLoading = false;
        showLoading(false);
        if (page == 1) {
            mItems.clear();
            mItems.addAll(items);
            mGridAdapter.submit(mItems);
            mPage = 1;
            if (mItems.isEmpty() && !fromCache) showEmpty(getString(R.string.widget_empty));
            else mBinding.empty.setVisibility(View.GONE);
        } else {
            mItems.addAll(items);
            mGridAdapter.append(items);
            mPage = page;
        }
        mHasMore = !items.isEmpty() && hasPageParam(mModule);
    }

    private static List<WidgetItem> parseItems(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            return com.fongmi.android.tv.App.gson().fromJson(json, new TypeToken<List<WidgetItem>>() {
            }.getType());
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean hasPageParam(WidgetModule module) {
        for (JsonObject param : module.getParams()) {
            if ("page".equals(Json.safeString(param, "type"))) return true;
        }
        return false;
    }

    private void showLoading(boolean show) {
        mBinding.progress.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) mBinding.empty.setVisibility(View.GONE);
    }

    private void showEmpty(String text) {
        mBinding.empty.setText(text);
        mBinding.empty.setVisibility(View.VISIBLE);
    }

    /** 横向单行片适配器（模块片/参数片共用）：MaterialTextView + shape_item_round，选中态高亮。 */
    private static class ChipAdapter<T> extends RecyclerView.Adapter<ChipAdapter<T>.Holder> {

        interface OnClick<T> {
            void onClick(T item);
        }

        interface Selected<T> {
            boolean isSelected(T item);
        }

        interface Label<T> {
            String getLabel(T item);
        }

        private final List<T> items;
        private final OnClick<T> onClick;
        private final Selected<T> selected;
        private final Label<T> label;

        ChipAdapter(List<T> items, OnClick<T> onClick, Selected<T> selected, Label<T> label) {
            this.items = items;
            this.onClick = onClick;
            this.selected = selected;
            this.label = label;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterWidgetChipBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            T item = items.get(position);
            holder.binding.text.setText(label.getLabel(item));
            holder.binding.text.setSelected(selected.isSelected(item));
            holder.binding.getRoot().setOnClickListener(v -> onClick.onClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final AdapterWidgetChipBinding binding;

            Holder(@NonNull AdapterWidgetChipBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
