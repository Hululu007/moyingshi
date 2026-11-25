package com.github.moyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.Product;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.bean.Keep;
import com.github.moyingshi.osc.tv.databinding.ActivityHistoryBinding;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.event.RefreshEvent;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.adapter.HistoryAdapter;
import com.github.moyingshi.osc.tv.ui.adapter.KeepAdapter;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.ui.dialog.SyncDialog;
import com.github.moyingshi.osc.tv.utils.MobileWindow;
import com.github.moyingshi.osc.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener, KeepAdapter.OnClickListener {

    private static final int TAB_HISTORY = 0;
    private static final int TAB_KEEP = 1;

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;
    private KeepAdapter mKeepAdapter;
    private int mTab = TAB_HISTORY;
    private TextView mHistoryTab;
    private TextView mKeepTab;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setEmptyContent();
        setRecyclerView();
        ((ViewGroup) mBinding.getRoot()).addView(buildTabs(), 1);
        switchTab(TAB_HISTORY);
        getKeep();
    }

    /**
     * 空态内容：设计文档 §9.9 要求「标题 + 解释 + 一个主动作」。
     *
     * <p>历史页与收藏页一样，新用户第一次进来时必为空。改动前只有一句
     * {@code error_empty}（「这里什麽都没有 👀」），既不解释为什么空，也不给出下一步。
     */
    private void setEmptyContent() {
        mBinding.progressLayout.setEmptyContent(
                R.string.history_empty_title,
                R.string.history_empty_description,
                R.string.history_empty_action,
                view -> goHome());
    }

    /** 空态主按钮：回点播首页去挑一部。与收藏页/播放页的回首页写法一致。 */
    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ---------- 历史观看 / 收藏双 Tab（我的页 Arcane 版式） ----------

    private LinearLayout buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(16), dp(4), dp(16), dp(4));
        mHistoryTab = buildTab(getString(R.string.profile_card_history), TAB_HISTORY);
        mKeepTab = buildTab("", TAB_KEEP);
        tabs.addView(mHistoryTab);
        tabs.addView(mKeepTab);
        return tabs;
    }

    private TextView buildTab(String text, int tab) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setOnClickListener(v -> switchTab(tab));
        return view;
    }

    private void switchTab(int tab) {
        mTab = tab;
        updateTabStyles();
        if (tab == TAB_KEEP) {
            mBinding.recycler.setAdapter(mKeepAdapter);
            getKeep();
        } else {
            mBinding.recycler.setAdapter(mAdapter);
            getHistory();
        }
    }

    private void updateTabStyles() {
        styleTab(mHistoryTab, mTab == TAB_HISTORY);
        styleTab(mKeepTab, mTab == TAB_KEEP);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tab.setTextColor(selected ? onSurfaceColor() : secondaryColor());
    }

    private void updateKeepTabLabel() {
        if (mKeepTab == null || mKeepAdapter == null) return;
        mKeepTab.setText(getString(R.string.profile_history_tab_favorites, mKeepAdapter.getItemCount()));
    }

    private void getKeep() {
        mKeepAdapter.setItems(Keep.getVod(), () -> {
            updateKeepTabLabel();
            mBinding.recycler.post(this::updateMarquee);
        });
    }

    private void setRecyclerView() {
        int column = MobileWindow.isWide(this) ? Product.getColumn(this) : 3;
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, column));
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mKeepAdapter = new KeepAdapter(this);
        // 列表数量变化后统一驱动状态机（BaseDiffAdapter 的钩子）：注册一次，之后无论从哪条
        // 路径改动列表（单条删除、删除全部、刷新）状态都会跟着列表走，宿主不可能再忘。
        // 只驱动当前 Tab 的空态，另一 Tab 的数量变化不抢状态。
        mAdapter.setOnCountChanged(count -> {
            if (mTab == TAB_HISTORY) mBinding.progressLayout.showContent(true, count);
        });
        mKeepAdapter.setOnCountChanged(count -> {
            if (mTab == TAB_KEEP) mBinding.progressLayout.showContent(true, count);
        });
        mBinding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                recyclerView.post(() -> {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) updateMarquee();
                    else setMarqueeRange(RecyclerView.NO_POSITION, RecyclerView.NO_POSITION);
                });
            }
        });
        int[] size = Product.getSpec(this, column);
        mAdapter.setSize(size);
        mKeepAdapter.setSize(size);
    }

    private void setMarqueeRange(int first, int last) {
        if (mTab == TAB_KEEP) mKeepAdapter.setMarqueeRange(first, last);
        else mAdapter.setMarqueeRange(first, last);
    }

    private void getHistory() {
        mAdapter.setItems(History.getForDisplay(), (hasChange) -> {
            if (hasChange) mBinding.recycler.scrollToPosition(0);
            mBinding.recycler.post(this::updateMarquee);
        });
    }

    private void updateMarquee() {
        if (mBinding.recycler.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            setMarqueeRange(RecyclerView.NO_POSITION, RecyclerView.NO_POSITION);
            return;
        }
        int[] range = findMarqueeRange(mBinding.recycler);
        setMarqueeRange(range[0], range[1]);
    }

    private int[] findMarqueeRange(RecyclerView recyclerView) {
        int first = RecyclerView.NO_POSITION;
        int last = RecyclerView.NO_POSITION;
        int top = recyclerView.getPaddingTop();
        int bottom = recyclerView.getHeight() - recyclerView.getPaddingBottom();
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            View info = child.findViewById(R.id.history_info);
            int infoTop = child.getTop() + info.getTop();
            int infoBottom = child.getTop() + info.getBottom();
            if (infoBottom <= top || infoTop >= bottom) continue;
            int position = recyclerView.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            first = first == RecyclerView.NO_POSITION ? position : Math.min(first, position);
            last = Math.max(last, position);
        }
        return new int[]{first, last};
    }

    private void onSync() {
        if (mTab == TAB_KEEP) SyncDialog.create().keep().show(this);
        else SyncDialog.create().history().show(this);
    }

    private void onDelete() {
        if (mTab == TAB_KEEP) {
            if (mKeepAdapter.isDelete()) {
                new WebHtvAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_keep).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mKeepAdapter.clear()).show();
            } else if (mKeepAdapter.getItemCount() > 0) {
                mKeepAdapter.setDelete(true);
            }
            return;
        }
        if (mAdapter.isDelete()) {
            new WebHtvAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(Setting.isGlobalHistoryEnabled() ? R.string.dialog_delete_global_history : R.string.dialog_delete_history).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.clear()).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.isVod()) {
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
            if (mKeepAdapter != null) mKeepAdapter.notifyDataSetChanged();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.HISTORY) && mTab == TAB_HISTORY) getHistory();
        else if (event.getType().equals(RefreshEvent.Type.KEEP) && mTab == TAB_KEEP) getKeep();
    }

    @Override
    public void onItemClick(History item) {
        HistoryResumeCoordinator.open(this, item);
    }

    @Override
    public void onItemDelete(History item) {
        mAdapter.remove(item.deleteDisplayItem(), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
            mBinding.recycler.post(this::updateMarquee);
        });
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) SearchActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        mKeepAdapter.remove(item.delete(), () -> {
            updateKeepTabLabel();
            if (mKeepAdapter.getItemCount() == 0) mKeepAdapter.setDelete(false);
            mBinding.recycler.post(this::updateMarquee);
        });
    }

    @Override
    public boolean onLongClick() {
        if (mTab == TAB_KEEP) mKeepAdapter.setDelete(!mKeepAdapter.isDelete());
        else mAdapter.setDelete(!mAdapter.isDelete());
        return true;
    }

    /** 收藏的源不是当前源时先加载该源配置（与收藏页同一套逻辑），再起播。 */
    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                mBinding.progressLayout.showError(
                        R.string.error_config_load,
                        msg,
                        R.string.error_retry,
                        view -> {
                            mBinding.progressLayout.showProgress();
                            loadConfig(config, item);
                        });
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_history, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.sync) onSync();
        else if (item.getItemId() == R.id.report) onReport();
        return super.onOptionsItemSelected(item);
    }

    private void onReport() {
        com.github.moyingshi.osc.tv.ui.dialog.ViewingReportRangeDialog.create(this)
                .callback(range -> com.github.moyingshi.osc.tv.ui.activity.ViewingReportActivity.start(this, range))
                .show();
    }

    @Override
    protected void onBackInvoked() {
        if (mTab == TAB_KEEP) {
            if (mKeepAdapter.isDelete()) mKeepAdapter.setDelete(false);
            else super.onBackInvoked();
        } else if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }

    private int onSurfaceColor() {
        return resolveAttr(com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#FF1A1C1E"));
    }

    private int secondaryColor() {
        return resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#FF8A8A8A"));
    }

    private int resolveAttr(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attr, value, true) && value.data != 0) {
            return value.data;
        }
        return fallback;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
