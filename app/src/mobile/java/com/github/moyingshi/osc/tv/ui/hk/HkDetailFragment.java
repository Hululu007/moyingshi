package com.github.moyingshi.osc.tv.ui.hk;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkDetailHelper;
import com.github.moyingshi.osc.tv.api.hk.HkExecutor;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.databinding.FragmentHkDetailBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.List;

/**
 * 海阔小程序详情 Fragment。
 * <p>
 * 后台线程调 {@link HkExecutor#detail(HkRule, String)} 取详情卡片，
 * {@link HkDetailHelper#split(List, String, String, String)} 切成信息头 +
 * 分线路选集，单 RecyclerView（GridLayoutManager + SpanSizeLookup）渲染：
 * 信息与线路头占满整行，选集占 1 列。点选集进播放是第 5 步，本步只弹
 * “播放还在做”，不进播放器。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkDetailFragment extends BaseFragment implements SwipeRefreshLayout.OnRefreshListener, HkDetailAdapter.Listener {

    public static HkDetailFragment newInstance(String ruleTitle, String url, String name, String pic, String desc) {
        Bundle args = new Bundle();
        args.putString("ruleTitle", ruleTitle);
        args.putString("url", url);
        args.putString("name", name);
        args.putString("pic", pic);
        args.putString("desc", desc);
        HkDetailFragment fragment = new HkDetailFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private FragmentHkDetailBinding mBinding;
    private HkDetailAdapter mAdapter;
    private HkRule mRule;
    private int mLoadSeq;

    private String arg(String key) {
        Bundle args = getArguments();
        if (args == null) return "";
        String value = args.getString(key);
        return value == null ? "" : value;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHkDetailBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.progressLayout.showProgress();
        mRule = HkStore.read(arg("ruleTitle"));
        if (mRule == null) {
            // 规则文件被删但站源条目还在：报一句就走，不进空白页。
            Notify.show(R.string.hk_list_rule_missing);
            requireActivity().finish();
            return;
        }
        mAdapter = new HkDetailAdapter(this);
        mBinding.recycler.setAdapter(mAdapter);
        loadDetail();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
    }

    /** 后台线程跑详情解析，回来带序号防串位（下拉重载时旧结果丢掉）。 */
    private void loadDetail() {
        if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
        final int seq = ++mLoadSeq;
        final HkRule rule = mRule;
        final String url = arg("url");
        final String fbName = arg("name");
        final String fbPic = arg("pic");
        final String fbDesc = arg("desc");
        new Thread(() -> {
            HkDetailHelper.Detail detail = null;
            int columns = 4;
            String error = null;
            try {
                List<HkCard> cards = HkExecutor.detail(rule, url);
                detail = HkDetailHelper.split(cards, fbName, fbPic, fbDesc);
                columns = HkDetailHelper.columnsFor(rule.getDetailColType());
            } catch (Exception e) {
                // HkException（请求/解析失败）与解析器里的未预料异常都走同一条错误态。
                error = e.getMessage();
            }
            final HkDetailHelper.Detail result = detail;
            final int cols = columns;
            final String err = error;
            App.post(() -> {
                if (!isAdded() || seq != mLoadSeq) return;
                onDetailLoaded(result, cols, err);
            });
        }).start();
    }

    private void onDetailLoaded(HkDetailHelper.Detail detail, int columns, String error) {
        mBinding.swipeLayout.setRefreshing(false);
        if (detail == null) {
            // 取数失败：错误态 + 点重试重跑。
            mBinding.progressLayout.showError(R.string.hk_detail_error, error == null ? "" : error, R.string.hk_detail_retry, v -> loadDetail());
            return;
        }
        if (detail.lines.isEmpty()) {
            // 有信息无选集也是成功态：补一行“暂无选集”，不显示空态。
            HkDetailHelper.Line empty = new HkDetailHelper.Line();
            empty.title = getString(R.string.hk_detail_no_episodes);
            detail.lines.add(empty);
        }
        final int span = columns;
        GridLayoutManager layout = new GridLayoutManager(requireContext(), columns);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.getItemViewType(position) == HkDetailAdapter.TYPE_EPISODE ? 1 : span;
            }
        });
        mBinding.recycler.setLayoutManager(layout);
        mBinding.progressLayout.showContent();
        mAdapter.setDetail(detail);
    }

    @Override
    public void onRefresh() {
        loadDetail();
    }

    /** 点选集：懒加载播放是第 5 步，先弹一句，不进播放器。 */
    @Override
    public void onEpisodeClick(HkCard episode) {
        Notify.show(R.string.hk_detail_play_pending);
    }
}
