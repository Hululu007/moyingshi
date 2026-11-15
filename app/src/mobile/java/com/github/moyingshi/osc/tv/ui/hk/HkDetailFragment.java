package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkActionHandler;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkDetailHelper;
import com.github.moyingshi.osc.tv.api.hk.HkEngine;
import com.github.moyingshi.osc.tv.api.hk.HkExecutor;
import com.github.moyingshi.osc.tv.api.hk.HkNonVideo;
import com.github.moyingshi.osc.tv.api.hk.HkParser;
import com.github.moyingshi.osc.tv.api.hk.HkPlayer;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.databinding.FragmentHkDetailBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.HashMap;
import java.util.Map;

/**
 * 海阔小程序详情 Fragment。
 * <p>
 * 后台线程调 {@link HkExecutor#detail(HkRule, String)} 取详情卡片，
 * {@link HkDetailHelper#split(List, String, String, String)} 切成信息头 +
 * 分线路选集，单 RecyclerView（GridLayoutManager + SpanSizeLookup）渲染：
 * 信息与线路头占满整行，选集占 1 列。点选集经 {@link HkPlayer} 进播放器
 * （第 5 步：懒加载解析出地址后复用 VideoActivity 播放入口）。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 * <p>
 * 第 7 步之七：详情页多一行可点动作行（线路 tab / 排序切换 / toast 提示行），
 * 点开走 {@link HkActionHandler} 执行；动作执行里 {@code putVar} 的值存在
 * {@code mVars} 里，下次重跑详情时预填回去（tab/排序切换靠它）。
 */
public class HkDetailFragment extends BaseFragment implements SwipeRefreshLayout.OnRefreshListener, HkDetailAdapter.Listener, HkActionHandler.Host {

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
    private HkDetailHelper.Detail mDetail;
    private int mLoadSeq;
    /** 页面变量快照（第 7 步之七）：动作执行里 putVar 的值，下次重跑详情时预填。 */
    private Map<String, Object> mVars = new HashMap<>();

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
        final Map<String, Object> vars = mVars;
        final HkEngine.DialogHost dialogHost = HkActionHandler.dialogHostFor(this);
        new Thread(() -> {
            HkDetailHelper.Detail detail = null;
            int columns = 4;
            String error = null;
            try {
                HkExecutor.DetailOutcome outcome = HkExecutor.detailOutcome(rule, url, vars, dialogHost);
                detail = HkDetailHelper.split(outcome.cards, fbName, fbPic, fbDesc);
                if (!TextUtils.isEmpty(outcome.lastChapter)) {
                    // 第 7 步之九：小说/漫画规则的最新章节，追加到简介末尾。
                    String line = App.get().getString(R.string.hk_detail_last_chapter, outcome.lastChapter);
                    detail.info.desc = TextUtils.isEmpty(detail.info.desc) ? line : detail.info.desc + "\n" + line;
                }
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
        mDetail = detail;
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

    /**
     * 点选集：影视类拼 Vod 进播放器（第 5 步）；非影视类（小说/漫画/音乐/图片）
     * 走 HkNonVideo 分流（第 7 步之九：阅读器 / 音频页 / 图集 / 明确提示）。
     */
    @Override
    public void onEpisodeClick(HkDetailHelper.Line line, HkCard episode) {
        if (mDetail == null) return;
        if (HkNonVideo.isNonVideoRule(mRule)) {
            HkNonVideo.routeEpisode(requireActivity(), mRule, mDetail, line, episode, arg("url"));
            return;
        }
        HkPlayer.play(requireActivity(), mRule, mDetail, line, episode, arg("url"));
    }

    /** 点动作行：走 HkActionHandler 执行（第 7 步之七），不进详情页。 */
    @Override
    public void onActionClick(HkCard action) {
        if (mRule == null) return;
        HkActionHandler.handle(this, action);
    }

    /* ---------------- HkActionHandler.Host（第 7 步之七） ---------------- */

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
        // MY_URL 要与详情引擎里的 spec.url 是同一字符串：卡片地址是相对地址时
        // 先按规则基地址补全（绝对地址原样返回，见 HkParser.resolveUrl）。
        if (mRule == null) return arg("url");
        return HkParser.resolveUrl(mRule.getUrl(), arg("url"));
    }

    @Override
    public Map<String, Object> vars() {
        return mVars;
    }

    @Override
    public void updateVars(Map<String, Object> vars) {
        if (vars != null) mVars = vars;
    }

    @Override
    public void reloadPage() {
        loadDetail();
    }

    @Override
    public void openDetail(String url, String title, String pic, String desc) {
        if (mRule == null) return;
        HkDetailActivity.open(requireContext(), mRule.getTitle(), url, title, pic, desc);
    }
}
