package com.github.moyingshi.osc.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.Product;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Value;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.FragmentTypeBinding;
import com.github.moyingshi.osc.tv.model.SiteViewModel;
import com.github.moyingshi.osc.tv.ui.activity.HistoryResumeCoordinator;
import com.github.moyingshi.osc.tv.ui.activity.SearchActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.adapter.VodAdapter;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.ui.custom.CustomScroller;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.HashMap;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private VodAdapter mAdapter;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, int y) {
        return newInstance(key, typeId, style, extend, folder, y, -1, null, -1);
    }

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, int y, int historyResumeCid, String historyResumeKey, int historyResumeTargetCid) {
        Bundle args = new Bundle();
        args.putInt("y", y);
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        args.putInt("historyResumeCid", historyResumeCid);
        args.putString("historyResumeKey", historyResumeKey);
        args.putInt("historyResumeTargetCid", historyResumeTargetCid);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private int getHistoryResumeCid() {
        return getArguments().getInt("historyResumeCid", -1);
    }

    private String getHistoryResumeKey() {
        return getArguments().getString("historyResumeKey");
    }

    private int getHistoryResumeTargetCid() {
        return getArguments().getInt("historyResumeTargetCid", -1);
    }

    private boolean isHistoryResume() {
        return getHistoryResumeCid() >= 0 && getHistoryResumeTargetCid() >= 0 && getHistoryResumeKey() != null && !getHistoryResumeKey().isEmpty();
    }

    private boolean isHome() {
        return "home".equals(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return (FolderFragment) getParentFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.progressLayout.showProgress();
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        setRecyclerView();
        setViewModel();
        getVideo();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
        mBinding.recycler.addOnScrollListener(mNavCollapseListener);
    }

    /**
     * 底栏上滑折叠的滚动源：CustomScroller 只管底部加载更多、不暴露 dy/方向，这里另挂一个轻量监听。
     * 只上报当前可见页（isVisible 拦住文件夹里被 hide 的旧页、VodFragment.isCurrentFolder 拦住
     * pager 切页后的旧页；VodFragment 不可见时它那头的 onContentScrolled 也会再拦一道）。
     */
    private final androidx.recyclerview.widget.RecyclerView.OnScrollListener mNavCollapseListener = new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView view, int dx, int dy) {
            if (dy == 0 || !isVisible()) return;
            FolderFragment folder = getParent();
            VodFragment vod = folder == null ? null : (VodFragment) folder.getParentFragment();
            if (vod == null || !vod.isCurrentFolder(folder)) return;
            vod.onContentScrolled(dy, !view.canScrollVertically(-1));
        }
    };

    private void setRecyclerView() {
        mBinding.recycler.setTranslationY(-ResUtil.dp2px(getY()));
        mBinding.recycler.setHasFixedSize(true);
        setStyle(getStyle());
    }

    private void setStyle(Style style) {
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, Product.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), Product.getColumn(requireActivity(), style)));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void getHome() {
        mAdapter.clear(() -> mViewModel.homeContent());
    }

    private void getVideo() {
        mScroller.reset();
        mAdapter.clear(() -> {
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
            if (isHome()) setAdapter(getParent().getResult());
            else getVideo(getTypeId(), "1");
        });
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        boolean first = mScroller.first();
        int size = result.getList().size();
        mBinding.progressLayout.showContent(first, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        Style style = result.getVod().getStyle(getStyle());
        if (!style.equals(mAdapter.getStyle())) setStyle(style);
        mAdapter.addAll(result.getList(), this::checkMore);
    }

    private void checkMore() {
        mBinding.recycler.post(() -> {
            if (isHome()) return;
            mScroller.checkMore(mBinding.recycler);
        });
    }

    public void scrollToTop() {
        mBinding.recycler.smoothScrollToPosition(0);
    }

    public void setFilter(String key, Value value) {
        if (value.isSelected()) mExtends.put(key, value.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    @Override
    public void onRefresh() {
        if (isHome()) getHome();
        else getVideo();
    }

    @Override
    public boolean onLoadMore(String page) {
        if (isHome()) return false;
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            com.github.moyingshi.osc.tv.content.ActionCardHelper.handleAction(requireActivity(), getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
        } else {
            if (getSite().isIndex()) {
                if (isHistoryResume()) HistoryResumeCoordinator.openSearch(requireActivity(), getHistoryResumeCid(), getHistoryResumeKey(), getHistoryResumeTargetCid(), item.getName());
                else SearchActivity.start(requireActivity(), item.getName());
            } else if (isHistoryResume()) {
                item.setSite(getSite());
                HistoryResumeCoordinator.openSelected(requireActivity(), getHistoryResumeCid(), getHistoryResumeKey(), getHistoryResumeTargetCid(), item);
            } else {
                VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
            }
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        SearchActivity.start(requireActivity(), item.getName());
        return true;
    }
}
