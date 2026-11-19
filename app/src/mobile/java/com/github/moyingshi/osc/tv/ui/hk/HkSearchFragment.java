package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.Product;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkClickDispatcher;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.ui.hk.HkEngineManager;
import com.github.moyingshi.osc.tv.api.hk.HkErrorUtil;
import com.github.moyingshi.osc.tv.api.hk.HkRouter;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.api.hk.SearchResult;
import com.github.moyingshi.osc.tv.api.hk.event.home.OnRefreshPageEvent;
import com.github.moyingshi.osc.tv.api.hk.event.home.ToastEvent;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.FragmentHkSearchBinding;
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

/**
 * 海阔小程序搜索 Fragment，对应魔影 HkSearchActivity 里的结果列表部分
 * （见逆向报告 §7.4：报告里是 ViewPager2 + 双 RecyclerView 的多引擎搜索，
 * 本步只做单规则搜索，见 docs/miniapp-search.md）。
 * <p>
 * 结构照 {@link HkListFragment}：下拉刷新 + 滑到底翻页
 * （{@link CustomScroller}，搜索翻页走 search_url 的 fypage）+ 加载/空/错
 * 三态（ProgressLayout）。取数走 5c 的
 * {@link HkRouter#search(String, int)}（新引擎门面，经 HkEngineManager
 * 常驻运行时；后台线程），回来先 {@link HkCard#pickCards} 再转 Vod，
 * 直接复用首页同款 {@link VodAdapter} 渲染。点卡片进详情页（第 4 步，
 * {@link HkDetailActivity}）；换关键词重搜时回到第 1 页，旧结果靠序号丢弃。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkSearchFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener, HkClickDispatcher.Host {

    private FragmentHkSearchBinding mBinding;
    private static final String TAG = "HkSearchFragment";
    private CustomScroller mScroller;
    private VodAdapter mAdapter;
    private HkRule mRule;
    private String mKeyword = "";
    private int mLoadSeq;
    /** 新引擎路由（5c）：懒建，首次取数时在后台线程建（构造器跑常驻运行时接线）。 */
    private HkRouter mRouter;
    /** refreshPage 事件驱动重载进行中（对齐第 3 项）：loadPage 开始置 true，onPageLoaded 清掉。 */
    private boolean mLoading;
    /** 加载进行中收到的 refreshPage：等本次加载完成后再重载一次（不重入、不丢）。 */
    private boolean mPendingRefresh;
    /** 攒下的刷新是否回顶（多事件按 OR 合并，有一个要回顶就回顶）。 */
    private boolean mPendingRefreshToTop;

    public static HkSearchFragment newInstance(String title, String keyword) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("keyword", keyword == null ? "" : keyword);
        HkSearchFragment fragment = new HkSearchFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getTitle() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString("title", "");
    }

    private String getKeywordArg() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString("keyword", "");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHkSearchBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        mRule = HkStore.read(getTitle());
        if (mRule == null) {
            // 规则文件被删但站源条目还在：报一句就走，不进空白页（与列表页同款）。
            Notify.show(R.string.hk_list_rule_missing);
            requireActivity().finish();
            return;
        }
        // 5c：记一面（HkEngineManager 常驻运行时按规则记面；归零时清该规则的 JS 定时器/任务）。
        HkEngineManager.surfaceOpened(mRule);
        setStyle(mRule.getColType());
        if (!mRule.isSearchable()) {
            // 没有搜索规则（search_url/searchFind 任一为空）：不发请求，显示空态 + 原因。
            mBinding.progressLayout.showContent(true, 0);
            mBinding.diagText.setText(R.string.hk_search_no_rule);
            mBinding.diagText.setVisibility(View.VISIBLE);
            return;
        }
        mKeyword = getKeywordArg();
        if (mKeyword.isEmpty()) {
            // 还没输关键词：空态等输入，不发请求。
            mBinding.progressLayout.showContent(true, 0);
            return;
        }
        loadPage(1, false);
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
    public void onHkRefreshPage(OnRefreshPageEvent event) {
        if (event == null || getActivity() == null || getActivity().isFinishing()) return;
        if (mRule == null) return;
        if (!isResumed() || !getUserVisibleHint()) {
            Log.i(TAG, "onHkRefreshPage guard: resumed=" + isResumed() + " visible=" + getUserVisibleHint());
            return;
        }
        if (mLoading) {
            mPendingRefresh = true;
            mPendingRefreshToTop = mPendingRefreshToTop || event.isScrollTop();
            Log.i(TAG, "onHkRefreshPage deferred: loading in flight");
            return;
        }
        if (event.isScrollTop() && mBinding != null && mBinding.recycler != null) {
            mBinding.recycler.scrollToPosition(0);
        }
        reloadPage();
    }

    /**
     * ToastEvent（7b：星辰 HkArticleFragment.onToastEvent 同款，见 7b 文档 §5.2）：
     * setError 无回调支投出的规则执行错误，宿主错误式呈现。门：空消息/无 Activity/finishing/
     * 未 resumed/不可见一律丢弃（isOnPause 同款门；盖住期间到的事件不记不补）。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onToastEvent(ToastEvent event) {
        if (event == null || event.message == null || event.message.isEmpty()) return;
        if (getActivity() == null || getActivity().isFinishing()) return;
        if (!isResumed() || !getUserVisibleHint()) return;
        HkErrorUtil.showErrorMsg(getActivity(), event.message, "500", event.message, new Exception(event.message));
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

    /** 按规则 col_type 定样式与列数（单源：HkColType 官表；列数与卡片尺寸成对取，显式列数时用字面列数算尺寸）。 */
    private void setStyle(String colType) {
        Style style = HkColType.styleFor(colType);
        int columns = HkColType.columnsFor(colType);
        int span = columns > 0 ? columns : Product.getColumn(requireActivity(), style);
        int[] spec = columns > 0 ? Product.getSpec(requireActivity(), style, columns) : Product.getSpec(requireActivity(), style);
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, spec));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), span));
    }

    /** 搜索页换关键词再搜：回到第 1 页。空关键词不发请求（Activity 提交前已判空，这里再守一道）。 */
    public void search(String keyword) {
        if (mRule == null || !mRule.isSearchable()) return;
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return;
        mKeyword = kw;
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

    /** 懒建路由（5c）：只在后台线程调；构造器抛异常时调用方按取数失败收口。 */
    private synchronized HkRouter routerFor(HkRule rule) throws Exception {
        // 规则对象换了即重建路由，免得拿旧规则对象跑新文件。
        if (mRouter != null && mRouter.getRule() != rule) mRouter = null;
        if (mRouter == null) mRouter = new HkRouter(rule);
        return mRouter;
    }

    /** 取一页：第 1 页先清列表。后台线程跑，回来带序号防串位（换词/下拉时旧结果丢掉）。 */
    private void loadPage(int page, boolean append) {
        if (!append) {
            mScroller.reset();
            mBinding.diagText.setVisibility(View.GONE);
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
        }
        final int seq = ++mLoadSeq;
        mLoading = true;
        final HkRule rule = mRule;
        final String keyword = mKeyword;
        new Thread(() -> {
            List<Vod> vods = null;
            String error = null;
            try {
                // 5c：取数源改走新引擎 HkRouter.search（门面 search，失败回空表不抛错；
                // 空表时读引擎错误通道区分真空与失败，见 docs/miniapp-xingchen-5c-list-search-ui.md §3.3）。
                HkRouter router = routerFor(rule);
                // 7c：JS confirm() 接管——router 建好后装 handler（共享桥上的 handler 永远是当前页的）。
                HkClickDispatcher.installConfirmHandler(this);
                // hkfix-1d：与列表页同款，首发先跑 preRule（星辰 HkSearchModel
                // 同款：page==1 时先 parsePreRule 再搜；见 HkListFragment 注释）。
                if (page == 1 && !append && !rule.getPreRule().isEmpty()) {
                    router.getEngine().getJsRuntime().parsePreRule(rule);
                }
                List<SearchResult> results = router.search(keyword, page);
                List<HkCard> cards = HkCard.fromSearchResults(results);
                vods = HkCard.toVods(HkCard.pickCards(cards, rule), rule.getType());
                if (cards.isEmpty()) {
                    String engineError = router.getEngine() == null ? "" : router.getEngine().getError();
                    if (engineError != null && !engineError.isEmpty()) {
                        // 失败：走错误态分支（vods 置空 + error 文案），不显示“搜到 0 条”。
                        vods = null;
                        error = engineError;
                    }
                }
            } catch (Exception e) {
                // 路由构造失败等未预料异常走同一条错误态。
                error = e.getMessage();
            }
            final List<Vod> result = vods;
            final String err = error;
            App.post(() -> {
                if (!isAdded()) { mLoading = false; return; }
                if (seq != mLoadSeq) return;
                onPageLoaded(page, append, result, err);
                consumePendingRefresh();
            });
        }).start();
    }

    private void onPageLoaded(int page, boolean append, List<Vod> vods, String error) {
        mLoading = false;
        mBinding.swipeLayout.setRefreshing(false);
        if (vods == null) {
            if (!append) {
                // 首页失败：错误态 + 点重试跑当前词第 1 页。
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
        // 首屏 0 条：显示原因（搜到 0 条）；有内容或翻页时隐藏。
        if (!append && vods.isEmpty()) {
            mBinding.diagText.setText(R.string.hk_search_empty);
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
        if (mKeyword.isEmpty()) {
            mBinding.swipeLayout.setRefreshing(false);
            return;
        }
        loadPage(1, false);
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

    /** 点卡片：动作 URL 先走 HkClickDispatcher 执行（5e），普通卡片进详情页（第 4 步），不进空白蜘蛛页（与列表页同一行调用）。 */
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

    /* ---------------- HkClickDispatcher.Host（5e；搜索页不保留 vars） ---------------- */

    @Override
    public Activity activity() {
        return getActivity();
    }

    @Override
    public boolean isActive() {
        return isResumed() && getUserVisibleHint();
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
}
