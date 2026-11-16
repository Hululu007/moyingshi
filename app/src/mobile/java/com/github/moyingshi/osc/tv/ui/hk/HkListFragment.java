package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.Product;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkActionHandler;
import com.github.moyingshi.osc.tv.api.hk.HkCache;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkExecutor;
import com.github.moyingshi.osc.tv.api.hk.HkListHelper;
import com.github.moyingshi.osc.tv.api.hk.HkRefreshPageEvent;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.bean.Class;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.FragmentHkListBinding;
import com.github.moyingshi.osc.tv.ui.adapter.TypeAdapter;
import com.github.moyingshi.osc.tv.ui.adapter.VodAdapter;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.ui.custom.CustomScroller;
import com.github.moyingshi.osc.tv.ui.web.CatWebActivity;
import com.github.moyingshi.osc.tv.utils.Notify;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 海阔小程序列表 Fragment，对应魔影的 kv1（见逆向报告 §4：无源码，按
 * HkListActivity 对它的调用点 x1(g22) / v1(标题, url) + 布局文件重写）。
 * <p>
 * 结构照首页的 {@code TypeFragment}：分类 chips（横向 RecyclerView，复用
 * {@link TypeAdapter} + {@link Class}）+ 下拉刷新 + 滑到底翻页
 *（{@link CustomScroller}）+ 加载/空/错三态（ProgressLayout）。
 * <p>
 * 取数走第 1 步的 {@link HkExecutor#home(HkRule, int, String)}（后台线程，
 * 同步网络 + Rhino），回来先 {@link HkListHelper#pickCards} 再转 Vod，
 * 直接复用首页同款 {@link VodAdapter} 渲染。点卡片进详情页（第 4 步，
 * {@link HkDetailActivity}）；首屏 0 条时显示命中诊断（区分真没内容还是
 * find_rule 未命中）。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkListFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener, TypeAdapter.OnClickListener, HkActionHandler.Host {

    /**
     * 内嵌宿主（首页 VodFragment）：内嵌模式下规则缺失/首屏取数失败时不由 Fragment
     * 自己收尾（不能 finish() 掉 HomeActivity），而是问宿主是否接管（回落开列表页）。
     * 返回 true = 宿主已接管，Fragment 不再显示错误态；false = Fragment 自己显示错误态。
     */
    public interface EmbedHost {
        boolean onHkEmbedUnavailable(String title);

        boolean onHkEmbedFailed(String title, String error);
    }

    private FragmentHkListBinding mBinding;
    private CustomScroller mScroller;
    private VodAdapter mAdapter;
    private TypeAdapter mClassAdapter;
    private HkRule mRule;
    private String mClassId;
    private int mLoadSeq;
    /** refreshPage 事件驱动重载进行中（对齐第 3 项）：loadPage 开始置 true，onPageLoaded 清掉。 */
    private boolean mLoading;
    /** 加载进行中收到的 refreshPage：等本次加载完成后再重载一次（不重入、不丢）。 */
    private boolean mPendingRefresh;
    /** 攒下的刷新是否回顶（多事件按 OR 合并，有一个要回顶就回顶）。 */
    private boolean mPendingRefreshToTop;

    private static final String TAG = "HkListFragment";

    public static HkListFragment newInstance(String title) {
        return newInstance(title, false);
    }

    /** 内嵌模式（第 7 步之五）：挂在首页 VodFragment 的 hkContainer 里，失败时通知宿主回落。 */
    public static HkListFragment newInstance(String title, boolean embedded) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putBoolean("embedded", embedded);
        HkListFragment fragment = new HkListFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getTitle() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString("title", "");
    }

    private boolean isEmbedded() {
        Bundle args = getArguments();
        return args != null && args.getBoolean("embedded", false);
    }

    private boolean notifyEmbedUnavailable() {
        Fragment parent = getParentFragment();
        if (parent instanceof EmbedHost) return ((EmbedHost) parent).onHkEmbedUnavailable(getTitle());
        return false;
    }

    private boolean notifyEmbedFailed(String error) {
        Fragment parent = getParentFragment();
        if (parent instanceof EmbedHost) return ((EmbedHost) parent).onHkEmbedFailed(getTitle(), error);
        return false;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHkListBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.progressLayout.showProgress();
        mScroller = new CustomScroller(this);
        mRule = HkStore.read(getTitle());
        if (mRule == null) {
            // 规则文件被删但站源条目还在：列表页报一句就走，不进空白页。
            // 内嵌模式不能 finish() 掉 HomeActivity：先问宿主是否回落开列表页，
            // 宿主未接管则在内嵌页自己显示错误态，重试重读规则。
            if (isEmbedded() && notifyEmbedUnavailable()) return;
            if (isEmbedded()) {
                mBinding.progressLayout.showError(R.string.hk_list_error, getString(R.string.hk_list_rule_missing), R.string.hk_list_retry, v -> retryBindRule());
                return;
            }
            Notify.show(R.string.hk_list_rule_missing);
            requireActivity().finish();
            return;
        }
        bindRuleLoaded();
    }

    /** 规则已读到：建分类、定样式、取第 1 页。 */
    private void bindRuleLoaded() {
        setClasses();
        setStyle(mRule.getColType());
        loadPage(1, false);
    }

    /** 内嵌模式重试：重读规则文件，读到了继续建分类取数，仍读不到继续显示错误态。 */
    private void retryBindRule() {
        mBinding.progressLayout.showProgress();
        mRule = HkStore.read(getTitle());
        if (mRule == null) {
            mBinding.progressLayout.showError(R.string.hk_list_error, getString(R.string.hk_list_rule_missing), R.string.hk_list_retry, v -> retryBindRule());
            return;
        }
        bindRuleLoaded();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    /**
     * refreshPage 事件（对齐第 3 项，照官方 vr1.onRefreshPage）：只对已 resumed 且可见的
     * 当前页生效，回顶在重跑之前；加载进行中时攒下来等完成后再重载一次（不重入、不丢）。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onHkRefreshPage(HkRefreshPageEvent event) {
        if (event == null || getActivity() == null || getActivity().isFinishing()) return;
        if (mRule == null) return;
        if (!isResumed() || !getUserVisibleHint()) {
            Log.i(TAG, "onHkRefreshPage guard: resumed=" + isResumed() + " visible=" + getUserVisibleHint());
            return;
        }
        if (mLoading) {
            mPendingRefresh = true;
            mPendingRefreshToTop = mPendingRefreshToTop || event.isToTop();
            Log.i(TAG, "onHkRefreshPage deferred: loading in flight");
            return;
        }
        if (event.isToTop() && mBinding != null && mBinding.recycler != null) {
            mBinding.recycler.scrollToPosition(0);
        }
        reloadPage();
    }

    /** 本次加载完成时消费进行中攒下的 refreshPage：只重载一次（回顶按攒下的值）。 */
    private void consumePendingRefresh() {
        if (!mPendingRefresh) return;
        mPendingRefresh = false;
        boolean toTop = mPendingRefreshToTop;
        mPendingRefreshToTop = false;
        if (toTop && mBinding != null && mBinding.recycler != null) {
            mBinding.recycler.scrollToPosition(0);
        }
        reloadPage();
    }

    /** 分类 chips：规则的 class_name（多于 1 个才显示；默认选中第一个）。 */
    private void setClasses() {
        String[] names = mRule.classNames();
        if (names.length <= 1) {
            mBinding.classRecycler.setVisibility(android.view.View.GONE);
            mClassId = names.length == 1 ? names[0] : null;
            return;
        }
        mClassId = names[0];
        List<Class> classes = new ArrayList<>();
        for (String name : names) {
            Class item = new Class();
            item.setTypeId(name);
            item.setTypeName(name);
            classes.add(item);
        }
        mClassAdapter = new TypeAdapter(this);
        mBinding.classRecycler.setAdapter(mClassAdapter);
        Result result = new Result();
        result.setTypes(classes);
        result.setList(new ArrayList<>());
        mClassAdapter.addAll(result);
    }

    /** 按规则 col_type 定样式与列数（单源：HkListHelper 全表；列数与卡片尺寸成对取，显式列数时用字面列数算尺寸）。 */
    private void setStyle(String colType) {
        Style style = HkListHelper.styleFor(colType);
        int columns = HkListHelper.columnsFor(colType);
        int span = columns > 0 ? columns : Product.getColumn(requireActivity(), style);
        int[] spec = columns > 0 ? Product.getSpec(requireActivity(), style, columns) : Product.getSpec(requireActivity(), style);
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, spec));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), span));
    }

    /** 取一页：第 1 页先清列表。后台线程跑，回来带序号防串位（切分类/下拉时旧结果丢掉）。 */
    private void loadPage(int page, boolean append) {
        loadPage(page, append, false);
    }

    /**
     * 取一页（第 7 步之十，缓存版）：force=true（下拉刷新）跳过缓存查、成功后覆盖写回；
     * 否则先查 {@link HkCache}，命中直接渲染、不发请求。缓存键是规则标题 + 分类 + 页码，
     * 与 HkWarmup 预热写入的键同一口径；规则文件 mtime 变了时 get() 内部即判失效。
     */
    private void loadPage(int page, boolean append, boolean force) {
        final int seq = ++mLoadSeq;
        mLoading = true;
        if (!force) {
            List<Vod> cached = HkCache.get(getTitle(), mClassId, page);
            if (cached != null) {
                // 命中缓存：不显示 loading、不发请求，直接走现成的 onPageLoaded 渲染；
                // !append 时仍先 reset + 隐藏诊断行，与网络路径的 loadPage 前半段一致。
                if (!append) {
                    mScroller.reset();
                    mBinding.diagText.setVisibility(View.GONE);
                }
                Log.i(TAG, "cache hit, render without network title=" + getTitle() + " page=" + page);
                App.post(() -> {
                    if (!isAdded()) { mLoading = false; return; }
                    if (seq != mLoadSeq) return;
                    onPageLoaded(page, append, cached, "", null);
                    consumePendingRefresh();
                });
                return;
            }
        }
        if (!append) {
            mScroller.reset();
            mBinding.diagText.setVisibility(View.GONE);
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
        }
        final HkRule rule = mRule;
        final String classId = mClassId;
        new Thread(() -> {
            List<Vod> vods = null;
            String diag = "";
            String error = null;
            try {
                HkExecutor.HomeOutcome outcome = HkExecutor.homeDetailed(rule, page, classId);
                vods = HkListHelper.toVods(HkListHelper.pickCards(outcome.cards, rule), rule.getType());
                diag = outcome.diag;
                if (vods != null && !vods.isEmpty()) {
                    // 成功且非空才进缓存（0 条不存，见 HkCache.put）；规则文件 mtime
                    // 随条目一起记，文件变了下次 get() 即失效。
                    HkCache.put(rule.getTitle(), classId, page, vods, HkStore.lastModified(rule.getTitle()));
                }
            } catch (Exception e) {
                // HkException（请求/解析失败）与解析器里的未预料异常都走同一条错误态。
                error = e.getMessage();
            }
            final List<Vod> result = vods;
            final String diagText = diag;
            final String err = error;
            App.post(() -> {
                if (!isAdded()) { mLoading = false; return; }
                if (seq != mLoadSeq) return;
                onPageLoaded(page, append, result, diagText, err);
                consumePendingRefresh();
            });
        }).start();
    }

    private void onPageLoaded(int page, boolean append, List<Vod> vods, String diag, String error) {
        mLoading = false;
        mBinding.swipeLayout.setRefreshing(false);
        if (vods == null) {
            if (!append) {
                // 首页失败：内嵌模式先问宿主是否回落开列表页（首页不停在报错页）；
                // 宿主未接管（已回落过一次）才显示错误态 + 点重试跑当前页。
                if (isEmbedded() && notifyEmbedFailed(error)) return;
                mBinding.progressLayout.showError(R.string.hk_list_error, error == null ? "" : error, R.string.hk_list_retry, v -> loadPage(1, false));
            } else {
                // 翻页失败：只报一句，页码回退，下次滑到底再试。
                Notify.show(getString(R.string.hk_list_load_failed, error == null ? "" : error));
                Result empty = new Result();
                empty.setList(new ArrayList<>());
                mScroller.endLoading(empty);
            }
            return;
        }
        // 同一列表内相同 URL 的卡只留一张（翻页每页都回同一张卡时去重；不同 URL 照常追加）。
        vods = HkListHelper.dedupeByUrl(append ? mAdapter.getItems() : null, vods);
        Result result = new Result();
        result.setList(vods);
        boolean first = mScroller.first();
        mBinding.progressLayout.showContent(first, vods.size());
        mScroller.endLoading(result);
        // 首屏 0 条：显示命中诊断（区分真没内容还是 find_rule 未命中）；有内容或翻页时隐藏。
        if (!append && vods.isEmpty()) {
            mBinding.diagText.setText(diag);
            mBinding.diagText.setVisibility(View.VISIBLE);
        } else {
            mBinding.diagText.setVisibility(View.GONE);
        }
        if (!vods.isEmpty()) {
            if (append) {
                mAdapter.addAll(vods, this::checkMore);
            } else {
                // 去重后 vods 被重赋值，不再是 effectively final，lambda 里用定值拷贝。
                final List<Vod> finalVods = vods;
                mAdapter.clear(() -> mAdapter.addAll(finalVods, this::checkMore));
            }
        }
    }

    private void checkMore() {
        mBinding.recycler.post(() -> mScroller.checkMore(mBinding.recycler));
    }

    @Override
    public void onRefresh() {
        // 下拉刷新 = 强制重取：绕过缓存，成功后覆盖写回同一键。
        loadPage(1, false, true);
    }

    @Override
    public boolean onLoadMore(String page) {
        int next;
        try {
            next = Integer.parseInt(page);
        } catch (NumberFormatException e) {
            next = 2;
        }
        loadPage(next, true);
        return true;
    }

    /** 点卡片：动作 URL 先走 HkActionHandler 执行（第 7 步之七），普通卡片进详情页（第 4 步），不进空白蜘蛛页。 */
    @Override
    public void onItemClick(Vod item) {
        if (mRule != null) {
            HkCard card = new HkCard(item.getName(), item.getId(), item.getPic(), item.getRemarks());
            if (HkActionHandler.handle(this, card)) return;
        }
        // x5_webview_single 卡是网页卡（官方 dealWithUrl：x5/web 分流不进详情页；
        // 星辰无 X5 引擎，走系统 WebView 顶替）：点开进站内 WebView，不进详情页。
        if (HkListHelper.isX5Url(item.getId())) {
            startActivity(CatWebActivity.intent(requireContext(), item.getId()));
            return;
        }
        HkDetailActivity.open(requireContext(), mRule.getTitle(), item.getId(), item.getName(), item.getPic(), item.getRemarks());
    }

    /* ---------------- HkActionHandler.Host（第 7 步之七；列表页不保留 vars） ---------------- */

    @Override
    public Activity activity() {
        return getActivity();
    }

    @Override
    public HkRule rule() {
        return mRule;
    }

    @Override
    public String pageUrl() {
        return mRule == null ? "" : mRule.getUrl();
    }

    @Override
    public Map<String, Object> vars() {
        return HkActionHandler.emptyVars();
    }

    @Override
    public void updateVars(Map<String, Object> vars) {
        // 列表页没有 tab 状态要留，putVar 结果不保留。
    }

    @Override
    public void reloadPage() {
        loadPage(1, false);
    }

    @Override
    public void openDetail(String url, String title, String pic, String desc) {
        if (mRule == null) return;
        HkDetailActivity.open(requireContext(), mRule.getTitle(), url, title, pic, desc);
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    /** 点分类 chip：切分类，回到第 1 页。 */
    @Override
    public void onItemClick(int position, Class item) {
        if (mClassAdapter != null) mClassAdapter.setSelected(position);
        mClassId = item.getTypeId();
        loadPage(1, false);
    }
}
