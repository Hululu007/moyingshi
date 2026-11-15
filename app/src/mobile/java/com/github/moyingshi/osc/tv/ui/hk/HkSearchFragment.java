package com.github.moyingshi.osc.tv.ui.hk;

import android.os.Bundle;
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
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkExecutor;
import com.github.moyingshi.osc.tv.api.hk.HkListHelper;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.FragmentHkSearchBinding;
import com.github.moyingshi.osc.tv.ui.adapter.VodAdapter;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.ui.custom.CustomScroller;
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;

/**
 * 海阔小程序搜索 Fragment，对应魔影 HkSearchActivity 里的结果列表部分
 * （见逆向报告 §7.4：报告里是 ViewPager2 + 双 RecyclerView 的多引擎搜索，
 * 本步只做单规则搜索，见 docs/miniapp-search.md）。
 * <p>
 * 结构照 {@link HkListFragment}：下拉刷新 + 滑到底翻页
 * （{@link CustomScroller}，搜索翻页走 search_url 的 fypage）+ 加载/空/错
 * 三态（ProgressLayout）。取数走第 6 步的
 * {@link HkExecutor#search(HkRule, String, int)}（后台线程，同步网络 +
 * Rhino），回来先 {@link HkListHelper#pickCards} 再转 Vod，直接复用首页
 * 同款 {@link VodAdapter} 渲染。点卡片进详情页（第 4 步，
 * {@link HkDetailActivity}）；换关键词重搜时回到第 1 页，旧结果靠序号丢弃。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkSearchFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private FragmentHkSearchBinding mBinding;
    private CustomScroller mScroller;
    private VodAdapter mAdapter;
    private HkRule mRule;
    private String mKeyword = "";
    private int mLoadSeq;

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

    /** 按规则 col_type 定样式与列数（单源：HkListHelper 全表；列数与卡片尺寸成对取，显式列数时用字面列数算尺寸）。 */
    private void setStyle(String colType) {
        Style style = HkListHelper.styleFor(colType);
        int columns = HkListHelper.columnsFor(colType);
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

    /** 取一页：第 1 页先清列表。后台线程跑，回来带序号防串位（换词/下拉时旧结果丢掉）。 */
    private void loadPage(int page, boolean append) {
        if (!append) {
            mScroller.reset();
            mBinding.diagText.setVisibility(View.GONE);
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
        }
        final int seq = ++mLoadSeq;
        final HkRule rule = mRule;
        final String keyword = mKeyword;
        new Thread(() -> {
            List<Vod> vods = null;
            String error = null;
            try {
                List<HkCard> cards = HkExecutor.search(rule, keyword, page);
                vods = HkListHelper.toVods(HkListHelper.pickCards(cards, rule), rule.getType());
            } catch (Exception e) {
                // HkException（请求/解析失败）与解析器里的未预料异常都走同一条错误态。
                error = e.getMessage();
            }
            final List<Vod> result = vods;
            final String err = error;
            App.post(() -> {
                if (!isAdded() || seq != mLoadSeq) return;
                onPageLoaded(page, append, result, err);
            });
        }).start();
    }

    private void onPageLoaded(int page, boolean append, List<Vod> vods, String error) {
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
                mAdapter.clear(() -> mAdapter.addAll(vods, this::checkMore));
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

    /** 点卡片：进详情页（第 4 步），不进空白蜘蛛页（与列表页同一行调用）。 */
    @Override
    public void onItemClick(Vod item) {
        HkDetailActivity.open(requireContext(), mRule.getTitle(), item.getId(), item.getName(), item.getPic(), item.getRemarks());
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }
}
