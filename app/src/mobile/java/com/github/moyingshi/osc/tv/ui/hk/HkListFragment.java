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
import com.github.moyingshi.osc.tv.api.hk.HkCache;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkClickDispatcher;
import com.github.moyingshi.osc.tv.api.hk.HkEngineManager;
import com.github.moyingshi.osc.tv.api.hk.HkItem;
import com.github.moyingshi.osc.tv.api.hk.HkRefreshPageEvent;
import com.github.moyingshi.osc.tv.api.hk.HkRouter;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.api.hk.HkSubpage;
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
import java.util.HashMap;
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
 * 取数走 5c 的 {@link HkRouter#home(int, String, String, String, String)}
 *（新引擎门面，经 HkEngineManager 常驻运行时；后台线程），回来先
 * {@link HkCard#pickCards} 再转 Vod，直接复用首页同款
 * {@link VodAdapter} 渲染。点卡片进详情页（第 4 步，
 * {@link HkDetailActivity}）；首屏 0 条时显示命中诊断（区分真没内容还是
 * find_rule 未命中）。
 * <p>
 * 5e 子页面模式：{@code hiker://page/…} 卡片经 {@link HkListActivity#openSubpage}
 * 开新列表页，单页由 {@link HkSubpage#expand} 渲染（不翻页、不缓存、不分类）。
 * <p>
 * 7a 规则 JSON 直供模式：{@code hiker://page/…} 卡片经
 * {@link com.github.moyingshi.osc.tv.ui.activity.HkPageActivity#openPage} 组子规则
 * （{@code HkPageParser.getNextPage}）后开新页，子规则 JSON 直供本 Fragment，
 * 不走 {@code HkStore.read}（子规则未登记进站源）；分类/翻页正常，缓存跳过
 * （子规则标题常与父规则同名，键会与父规则首页互串，见 5e 子页面模式同款理由）；
 * 页地址 query 参数在 {@code router.home(...)} 求值前后挂/清 {@code $.importParam}
 * （只包一次求值，见 {@link #loadPage} 内桥段）。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkListFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener, TypeAdapter.OnClickListener, HkClickDispatcher.Host {

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
    /** 新引擎路由（5c）：懒建，首次取数时在后台线程建（构造器跑常驻运行时接线）。 */
    private HkRouter mRouter;
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

    /** 子页面模式（5e）：开新列表页渲染 hiker://page/… 子页面（单页，见 HkSubpage.expand）。 */
    public static HkListFragment newInstanceSubpage(String title, String pageUrl) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("subpage_url", pageUrl);
        HkListFragment fragment = new HkListFragment();
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * 规则 JSON 直供模式（7a）：子规则由 HkPageActivity.openPage 经 getNextPage 组出，
     * 未登记进站源，JSON 直供；importParamsJson 是页地址 query 参数的 JSON（供 $.importParam）。
     */
    public static HkListFragment newInstanceWithRuleJson(String ruleJson, String title, String importParamsJson) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("rule_json", ruleJson);
        if (importParamsJson != null) args.putString("import_params", importParamsJson);
        HkListFragment fragment = new HkListFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getRuleJson() {
        Bundle args = getArguments();
        return args == null ? null : args.getString("rule_json", null);
    }

    private boolean isRuleJsonMode() {
        return getRuleJson() != null;
    }

    private String getImportParamsJson() {
        Bundle args = getArguments();
        return args == null ? null : args.getString("import_params", null);
    }

    /** import_params JSON → Map（坏串回空表，不抛错；值只取字符串形，非字符串取 JSON 文本）。 */
    private static Map<String, String> parseImportParams(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return out;
            com.google.gson.JsonObject obj = root.getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                com.google.gson.JsonElement v = e.getValue();
                if (v == null || v.isJsonNull()) out.put(e.getKey(), "");
                else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) out.put(e.getKey(), v.getAsString());
                else out.put(e.getKey(), v.toString());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 规则 JSON 直供模式的 $.importParam（页地址 query 参数；只在 home() 求值前后挂/清）。 */
    private Map<String, String> mImportParams;

    private String getSubpageUrl() {
        Bundle args = getArguments();
        return args == null ? null : args.getString("subpage_url", null);
    }

    private boolean isSubpage() {
        return getSubpageUrl() != null;
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
        // 7a：规则 JSON 直供模式直接解析子规则 JSON（子规则未登记进站源，HkStore.read 读不到）。
        mRule = isRuleJsonMode() ? HkRule.parse(getRuleJson()) : HkStore.read(getTitle());
        if (isRuleJsonMode()) mImportParams = parseImportParams(getImportParamsJson());
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
        // 5c：记一面（HkEngineManager 常驻运行时按规则记面；归零时清该规则的 JS 定时器/任务）。
        HkEngineManager.surfaceOpened(mRule);
        if (isSubpage()) {
            // 5e 子页面模式：无分类栏（显式隐藏），样式取子页面的 col_type（空则回落规则 col_type）。
            mClassId = null;
            mBinding.classRecycler.setVisibility(android.view.View.GONE);
            setStyle(HkSubpage.pageColType(mRule, getSubpageUrl()));
        } else {
            setClasses();
            setStyle(mRule.getColType());
        }
        loadPage(1, false);
    }

    @Override
    public void onDestroy() {
        // 5c：路由 destroy 对常驻引擎是空操作（生命周期归 HkEngineManager 管）；销一面。
        if (mRouter != null) {
            try {
                mRouter.destroy();
            } catch (Throwable ignored) {
            }
            mRouter = null;
        }
        if (mRule != null) HkEngineManager.surfaceClosed(mRule);
        super.onDestroy();
    }

    /** 内嵌模式重试：重读规则文件，读到了继续建分类取数，仍读不到继续显示错误态。 */
    private void retryBindRule() {
        mBinding.progressLayout.showProgress();
        mRule = isRuleJsonMode() ? HkRule.parse(getRuleJson()) : HkStore.read(getTitle());
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

    /** 按规则 col_type 定样式与列数（单源：HkColType 官表；列数与卡片尺寸成对取，显式列数时用字面列数算尺寸）。 */
    private void setStyle(String colType) {
        Style style = HkColType.styleFor(colType);
        int columns = HkColType.columnsFor(colType);
        int span = columns > 0 ? columns : Product.getColumn(requireActivity(), style);
        int[] spec = columns > 0 ? Product.getSpec(requireActivity(), style, columns) : Product.getSpec(requireActivity(), style);
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, spec));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), span));
    }

    /** 懒建路由（5c）：只在后台线程调；构造器抛异常时调用方按取数失败收口。 */
    private synchronized HkRouter routerFor(HkRule rule) throws Exception {
        // 规则对象换了（retryBindRule 重读）即重建路由，免得拿旧规则对象跑新文件。
        if (mRouter != null && mRouter.getRule() != rule) mRouter = null;
        if (mRouter == null) mRouter = new HkRouter(rule);
        return mRouter;
    }

    /** 取一页：第 1 页先清列表。后台线程跑，回来带序号防串位（切分类/下拉时旧结果丢掉）。 */
    private void loadPage(int page, boolean append) {
        loadPage(page, append, false);
    }

    /**
     * 取一页（第 7 步之十，缓存版）：force=true（下拉刷新）跳过缓存查、成功后覆盖写回；
     * 否则先查 {@link HkCache}，命中直接渲染、不发请求。缓存键是规则标题 + 分类 + 页码，
     * 与 HkWarmup 预热写入的键同一口径；规则文件 mtime 变了时 get() 内部即判失效。
     * 5e：子页面模式跳过缓存读写（键是规则标题 + 分类 + 页码，子页面内容与首页同键会互串）。
     */
    private void loadPage(int page, boolean append, boolean force) {
        final int seq = ++mLoadSeq;
        mLoading = true;
        if (!force && !isSubpage() && !isRuleJsonMode()) {
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
                // 5c：取数源改走新引擎 HkRouter.home（门面 home，失败回空表不抛错；
                // 空表时读引擎错误通道区分真空与失败，见 docs/miniapp-xingchen-5c-list-search-ui.md §3.3）。
                // 5e：子页面模式单页由 HkSubpage.expand 渲染（与旧 HkExecutor.detail 的子页面支
                // 行为一致），不走 Router.home；失败由 expand 抛错走外层 catch。
                HkRouter router = routerFor(rule);
                List<HkCard> cards;
                if (isSubpage()) {
                    cards = HkSubpage.expand(rule, getSubpageUrl());
                } else {
                    // 7a $.importParam 桥：子规则的 find_rule（如阅动漫 ClassTab 页规则
                    // `var {...} = $.importParam`）在求值时要读页地址 query 参数；
                    // $.importParam 只由 HkSubpage.expand 经 setImportParams 注入，
                    // Router.home 路从不调它，故规则 JSON 模式在此处挂/清一次（只包
                    // 本次 home() 求值；importParams 是共享桥上的 volatile 字段，
                    // 窗口内并发求值的他规则理论上可读到它，与共享桥 varMap 同类局限，
                    // 记名接受；MY_PARAMS 不走此桥，规则 params 字段既有路承接）。
                    List<HkItem> items;
                    if (isRuleJsonMode() && mImportParams != null && !mImportParams.isEmpty()) {
                        boolean applied = false;
                        try {
                            router.getEngine().setImportParams(mImportParams);
                            applied = true;
                            items = router.home(page, classId, "", "", "");
                        } finally {
                            if (applied) router.getEngine().setImportParams(null);
                        }
                    } else {
                        items = router.home(page, classId, "", "", "");
                    }
                    cards = HkCard.fromItems(items);
                }
                vods = HkCard.toVods(HkCard.pickCards(cards, rule), rule.getType());
                diag = "得到 " + cards.size() + " 张卡片";
                if (!isSubpage() && cards.isEmpty()) {
                    String engineError = router.getEngine() == null ? "" : router.getEngine().getError();
                    if (engineError != null && !engineError.isEmpty()) {
                        // 失败：走错误态分支（vods 置空 + error 文案），不显示“0 条内容”。
                        vods = null;
                        error = engineError;
                        diag = "";
                    }
                }
                if (!isSubpage() && !isRuleJsonMode() && vods != null && !vods.isEmpty()) {
                    // 成功且非空才进缓存（0 条不存，见 HkCache.put）；规则文件 mtime
                    // 随条目一起记，文件变了下次 get() 即失效。
                    HkCache.put(rule.getTitle(), classId, page, vods, HkStore.lastModified(rule.getTitle()));
                }
            } catch (Exception e) {
                // 路由构造失败等未预料异常走同一条错误态。
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
        vods = HkCard.dedupeByUrl(append ? mAdapter.getItems() : null, vods);
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
        // 5e 子页面模式：单页，无第 2 页（expand 重跑只会回同一页，去重后也是空追加）。
        if (isSubpage()) return false;
        int next;
        try {
            next = Integer.parseInt(page);
        } catch (NumberFormatException e) {
            next = 2;
        }
        loadPage(next, true);
        return true;
    }

    /** 点卡片：动作 URL 先走 HkClickDispatcher 执行（5e），普通卡片进详情页（第 4 步），不进空白蜘蛛页。 */
    @Override
    public void onItemClick(Vod item) {
        if (mRule != null) {
            HkCard card = new HkCard(item.getName(), item.getId(), item.getPic(), item.getRemarks());
            if (HkClickDispatcher.handle(this, card)) return;
        }
        // x5_webview_single 卡是网页卡（官方 dealWithUrl：x5/web 分流不进详情页；
        // 星辰无 X5 引擎，走系统 WebView 顶替）：点开进站内 WebView，不进详情页。
        if (HkCard.isX5Url(item.getId())) {
            startActivity(CatWebActivity.intent(requireContext(), item.getId()));
            return;
        }
        HkDetailActivity.open(requireContext(), mRule.getTitle(), item.getId(), item.getName(), item.getPic(), item.getRemarks());
    }

    /* ---------------- HkClickDispatcher.Host（5e；列表页不保留 vars） ---------------- */

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
    public HkRouter router() {
        return mRouter;
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
    public void openSubpage(String url, String title) {
        if (mRule == null) return;
        HkListActivity.openSubpage(requireContext(), mRule.getTitle(), url);
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
