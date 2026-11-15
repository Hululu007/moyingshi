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
import com.github.moyingshi.osc.tv.api.hk.HkExecutor;
import com.github.moyingshi.osc.tv.api.hk.HkListHelper;
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
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;

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
public class HkListFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener, TypeAdapter.OnClickListener {

    private FragmentHkListBinding mBinding;
    private CustomScroller mScroller;
    private VodAdapter mAdapter;
    private TypeAdapter mClassAdapter;
    private HkRule mRule;
    private String mClassId;
    private int mLoadSeq;

    public static HkListFragment newInstance(String title) {
        Bundle args = new Bundle();
        args.putString("title", title);
        HkListFragment fragment = new HkListFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getTitle() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString("title", "");
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
            // 规则文件被删但站源条目还在：报一句就走，不进空白页。
            Notify.show(R.string.hk_list_rule_missing);
            requireActivity().finish();
            return;
        }
        setClasses();
        setStyle(HkListHelper.styleFor(mRule.getColType()));
        loadPage(1, false);
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
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

    private void setStyle(Style style) {
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, Product.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), Product.getColumn(requireActivity(), style)));
    }

    /** 取一页：第 1 页先清列表。后台线程跑，回来带序号防串位（切分类/下拉时旧结果丢掉）。 */
    private void loadPage(int page, boolean append) {
        if (!append) {
            mScroller.reset();
            mBinding.diagText.setVisibility(View.GONE);
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
        }
        final int seq = ++mLoadSeq;
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
            } catch (Exception e) {
                // HkException（请求/解析失败）与解析器里的未预料异常都走同一条错误态。
                error = e.getMessage();
            }
            final List<Vod> result = vods;
            final String diagText = diag;
            final String err = error;
            App.post(() -> {
                if (!isAdded() || seq != mLoadSeq) return;
                onPageLoaded(page, append, result, diagText, err);
            });
        }).start();
    }

    private void onPageLoaded(int page, boolean append, List<Vod> vods, String diag, String error) {
        mBinding.swipeLayout.setRefreshing(false);
        if (vods == null) {
            if (!append) {
                // 首页失败：错误态 + 点重试跑当前页。
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
                mAdapter.clear(() -> mAdapter.addAll(vods, this::checkMore));
            }
        }
    }

    private void checkMore() {
        mBinding.recycler.post(() -> mScroller.checkMore(mBinding.recycler));
    }

    @Override
    public void onRefresh() {
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

    /** 点卡片：进详情页（第 4 步），不进空白蜘蛛页。 */
    @Override
    public void onItemClick(Vod item) {
        HkDetailActivity.open(requireContext(), mRule.getTitle(), item.getId(), item.getName(), item.getPic(), item.getRemarks());
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
