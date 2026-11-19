package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkClickDispatcher;
import com.github.moyingshi.osc.tv.api.hk.HkCard;
import com.github.moyingshi.osc.tv.api.hk.HkDetail;
import com.github.moyingshi.osc.tv.api.hk.HkDetailBridge;
import com.github.moyingshi.osc.tv.api.hk.HkDetailHelper;
import com.github.moyingshi.osc.tv.api.hk.HkDetailItem;
import com.github.moyingshi.osc.tv.api.hk.HkEngineManager;
import com.github.moyingshi.osc.tv.api.hk.HkErrorUtil;
import com.github.moyingshi.osc.tv.api.hk.HkHistoryRecorder;
import com.github.moyingshi.osc.tv.api.hk.HkItem;
import com.github.moyingshi.osc.tv.api.hk.HkParserKit;
import com.github.moyingshi.osc.tv.api.hk.HkRefreshPageEvent;
import com.github.moyingshi.osc.tv.api.hk.HkRouter;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.api.hk.event.home.ToastEvent;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.FragmentHkDetailBinding;
import com.github.moyingshi.osc.tv.ui.activity.HkPageActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.utils.Notify;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 海阔小程序详情 Fragment。
 * <p>
 * 后台线程调 {@link HkRouter#detail(String, HkItem, boolean)} 取详情（新引擎门面，
 * 经 HkEngineManager 常驻运行时），{@link HkDetailHelper#split(List, String, String, String)}
 * 切成信息头 + 分线路选集，单 RecyclerView（GridLayoutManager + SpanSizeLookup）渲染：
 * 信息与线路头占满整行，选集占 1 列。点选集拼 {@link HkDetailBridge#toVod} 的 Vod
 * 经 {@link VideoActivity#startHk} 进播放器（懒加载解析在播放器内走
 * {@link HkRouter#play(String)}）。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 * <p>
 * 第 7 步之七：详情页多一行可点动作行（线路 tab / 排序切换 / toast 提示行），
 * 点开走 {@link HkClickDispatcher} 执行（5e；新运行时 vars 在进程级 varMap 共享，
 * 不再需要 mVars 快照中转）。
 */
public class HkDetailFragment extends BaseFragment implements SwipeRefreshLayout.OnRefreshListener, HkDetailAdapter.Listener, HkClickDispatcher.Host {

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
    private static final String TAG = "HkDetailFragment";
    private HkDetailAdapter mAdapter;
    private HkRule mRule;
    private HkDetailHelper.Detail mDetail;
    /** toVod 拼出的 Vod（与 mDetail 同源，供点选集与阅读器复用）。 */
    private Vod mVod;
    private int mLoadSeq;
    /** 新引擎路由（5d）：懒建，首次取数时在后台线程建（构造器跑常驻运行时接线）。 */
    private HkRouter mRouter;
    /** refreshPage 事件驱动重载进行中（对齐第 3 项）：loadDetail 开始置 true，onDetailLoaded 清掉。 */
    private boolean mLoading;
    /** 加载进行中收到的 refreshPage：等本次加载完成后再重载一次（不重入、不丢）。 */
    private boolean mPendingRefresh;
    /** 攒下的刷新是否回顶（多事件按 OR 合并，有一个要回顶就回顶）。 */
    private boolean mPendingRefreshToTop;
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
        // 5d：记一面（HkEngineManager 常驻运行时按规则记面；归零时清该规则的 JS 定时器/任务）。
        HkEngineManager.surfaceOpened(mRule);
        mAdapter = new HkDetailAdapter(this);
        mBinding.recycler.setAdapter(mAdapter);
        loadDetail();
    }

    @Override
    public void onDestroy() {
        // 5d：路由 destroy 对常驻引擎是空操作（生命周期归 HkEngineManager 管）；销一面。
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

    /** 懒建路由（5c 同款）：只在后台线程调；构造器抛异常时调用方按取数失败收口。 */
    private synchronized HkRouter routerFor(HkRule rule) throws Exception {
        // 规则对象换了即重建路由，免得拿旧规则对象跑新文件。
        if (mRouter != null && mRouter.getRule() != rule) mRouter = null;
        if (mRouter == null) mRouter = new HkRouter(rule);
        return mRouter;
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
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

    /** 取数结果（后台线程产出，主线程消费）：正常路带 Detail + Vod + 列数；标记路只带标记字段。 */
    private static final class LoadResult {
        HkDetailHelper.Detail detail;
        Vod vod;
        int columns = 4;
        String error;
        boolean tabSwitch;
        String directPlayUrl = "";
        String dealKind = "";
        String dealUrl = "";
        String textResult = "";
        HkCard actionCard;
    }

    /** 后台线程跑详情解析，回来带序号防串位（下拉重载时旧结果丢掉）。 */
    private void loadDetail() {
        if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
        final int seq = ++mLoadSeq;
        mLoading = true;
        final HkRule rule = mRule;
        final String url = arg("url");
        final String fbName = arg("name");
        final String fbPic = arg("pic");
        final String fbDesc = arg("desc");
        new Thread(() -> {
            LoadResult out = new LoadResult();
            try {
                // 5d：取数源改走新引擎 HkRouter.detail（门面 detail；失败抛错、
                // 标记路回 HkDetail 不抛错，见 docs/miniapp-xingchen-5d-detail-play.md §3）。
                // fromSearch=false：与旧 HkExecutor.detailOutcome 同口径，不折叠搜索引擎。
                HkRouter router = routerFor(rule);
                HkItem item = new HkItem(fbName, url, fbPic, fbDesc);
                HkDetail hd = router.detail(url, item, false);
                if (hd.isLazyParseFailed()) {
                    out.error = "详情解析失败";
                } else if (hd.isTabSwitch()) {
                    out.tabSwitch = true;
                } else if (!TextUtils.isEmpty(hd.getDirectPlayUrl())) {
                    out.directPlayUrl = hd.getDirectPlayUrl();
                } else if (!TextUtils.isEmpty(hd.getDealKind())) {
                    out.dealKind = hd.getDealKind();
                    out.dealUrl = hd.getDealUrl();
                } else if (!TextUtils.isEmpty(hd.getTextResult())) {
                    out.textResult = hd.getTextResult();
                } else if (!TextUtils.isEmpty(hd.getActionUrl())) {
                    out.actionCard = new HkCard("", hd.getActionUrl(), "", "");
                } else {
                    List<HkDetailItem> rawItems = hd.getRawItems();
                    List<HkCard> cards = HkCard.fromDetailItems(rawItems);
                    HkDetailHelper.Detail detail = HkDetailHelper.split(cards, fbName, fbPic, fbDesc);
                    if (!TextUtils.isEmpty(hd.getLastChapter())) {
                        // 第 7 步之九：小说/漫画规则的最新章节，追加到简介末尾。
                        String line = App.get().getString(R.string.hk_detail_last_chapter, hd.getLastChapter());
                        detail.info.desc = TextUtils.isEmpty(detail.info.desc) ? line : detail.info.desc + "\n" + line;
                    }
                    stampLines(rawItems, cards, detail);
                    out.vod = HkDetailBridge.toVod(rule, url, hd);
                    out.detail = detail;
                    out.columns = HkDetailHelper.columnsFor(rule.getDetailColType());
                }
            } catch (Exception e) {
                // 取数/解析失败与解析器里的未预料异常都走同一条错误态。
                out.error = e.getMessage();
            }
            final LoadResult result = out;
            App.post(() -> {
                if (!isAdded()) { mLoading = false; return; }
                if (seq != mLoadSeq) return;
                onDetailLoaded(result);
                consumePendingRefresh();
            });
        }).start();
    }

    /**
     * 把 split 的线路名写回原条目（toVod 按 {@code HkDetailItem.line} 分组；
     * 选择器解析的条目 line 恒为空，不写回则 Vod 恒为单线路“默认”，与详情页的多线路
     * 不一致，startHk 的 playFlag 也对不上）。fromDetailItems 是 1:1 保序转换
     * （只跳 null），按同一顺序回找原条目。
     */
    private static void stampLines(List<HkDetailItem> rawItems, List<HkCard> cards,
                                   HkDetailHelper.Detail detail) {
        if (rawItems == null || cards == null || detail == null) return;
        Map<HkCard, HkDetailItem> itemOf = new IdentityHashMap<>();
        Iterator<HkDetailItem> it = rawItems.iterator();
        for (HkCard card : cards) {
            HkDetailItem di = null;
            while (it.hasNext()) {
                di = it.next();
                if (di != null) break;
            }
            if (di != null) itemOf.put(card, di);
        }
        for (HkDetailHelper.Line line : detail.lines) {
            if (line == null || line.episodes == null) continue;
            for (HkCard ep : line.episodes) {
                HkDetailItem di = itemOf.get(ep);
                if (di != null) di.setLine(line.title == null ? "" : line.title);
            }
        }
    }

    private void onDetailLoaded(LoadResult r) {
        mLoading = false;
        mBinding.swipeLayout.setRefreshing(false);
        if (r.error != null) {
            // 取数失败：错误态 + 点重试重跑。
            mBinding.progressLayout.showError(R.string.hk_detail_error, r.error, R.string.hk_detail_retry, v -> loadDetail());
            return;
        }
        if (r.tabSwitch) {
            // 分类切换：懒规则只改了变量、不进详情页；直接结束本页回列表
            //（详情页重跑同一懒规则会循环触发；列表重刷靠 refreshPage 事件，见 5d 文档 §3）。
            requireActivity().finish();
            return;
        }
        if (!TextUtils.isEmpty(r.directPlayUrl)) {
            playDirect(r.directPlayUrl);
            return;
        }
        if (!TextUtils.isEmpty(r.dealKind)) {
            handleDealKind(r.dealKind, r.dealUrl);
            return;
        }
        if (!TextUtils.isEmpty(r.textResult)) {
            showTextResult(r.textResult);
            return;
        }
        if (r.actionCard != null) {
            // 动作 URL：走 HkClickDispatcher 执行（5e），然后结束本页。
            HkClickDispatcher.handle(this, r.actionCard);
            requireActivity().finish();
            return;
        }
        HkDetailHelper.Detail detail = r.detail;
        if (detail == null) {
            // 取数失败：错误态 + 点重试重跑。
            mBinding.progressLayout.showError(R.string.hk_detail_error, "", R.string.hk_detail_retry, v -> loadDetail());
            return;
        }
        mDetail = detail;
        mVod = r.vod;
        if (detail.lines.isEmpty()) {
            // 有信息无选集也是成功态：补一行“暂无选集”，不显示空态。
            HkDetailHelper.Line empty = new HkDetailHelper.Line();
            empty.title = getString(R.string.hk_detail_no_episodes);
            detail.lines.add(empty);
        }
        final int span = r.columns;
        GridLayoutManager layout = new GridLayoutManager(requireContext(), r.columns);
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

    /** 详情页本身就是可播条目（directPlayUrl / dealKind=video/magnet）：跳过详情页直接播放。 */
    private void playDirect(String episodeUrl) {
        if (mRule == null) {
            requireActivity().finish();
            return;
        }
        VideoActivity.startHk(requireActivity(), minimalVod(), "", arg("name"), episodeUrl);
        requireActivity().finish();
    }

    /** 无详情 Vod 时的最小 Vod（directPlay / dealKind 分流用；站 key 与 toVod 同款 hk://）。 */
    private Vod minimalVod() {
        Vod vod = new Vod();
        vod.setSite(HkStore.siteOf(mRule));
        vod.setId(arg("url"));
        vod.setName(arg("name"));
        vod.setPic(arg("pic"));
        vod.setContent(arg("desc"));
        return vod;
    }

    /** dealKind 分流（HkDetail 的官方 dealWithUrl 口径，不进 V4）。 */
    private void handleDealKind(String kind, String url) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        switch (kind) {
            case "video":
            case "magnet":
                // video：直接播放；magnet：与旧链路同款直通（集址=磁力链，播放器侧原样接）。
                playDirect(url);
                return;
            case "pics":
            case "x5":
            case "web":
            case "image":
                // 7a：四支委托 HkPageActivity.handleDealUrl（星辰 handleDealUrl 全套，
                // 详情面与列表面共用一处实现）；消费后仍结束本页（5d 行为不变）。
                HkPageActivity.handleDealUrl(activity, mRule, kind, url, arg("name"), arg("pic"), minimalVod());
                requireActivity().finish();
                return;
            default:
                Notify.show(R.string.hk_action_no_result);
                requireActivity().finish();
                return;
        }
    }

    /** 纯文本结果：弹窗显示文本，关闭时结束本页。 */
    private void showTextResult(String text) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        final Activity act = activity;
        new AlertDialog.Builder(act)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (!act.isFinishing()) act.finish();
                })
                .setOnCancelListener(d -> {
                    if (!act.isFinishing()) act.finish();
                })
                .show();
    }

    @Override
    public void onRefresh() {
        loadDetail();
    }

    /**
     * 点选集：影视类拼 Vod 进播放器（5d：toVod 的 Vod + startHk，懒加载解析在播放器内
     * 走 HkRouter.play）；非影视类（小说/漫画/音乐/图片）走 HkPageActivity.routeEpisode 分流
     * （7a：自 HkNonVideo 搬家而来，阅读器 / 音频页 / 图集 / 明确提示）。
     */
    @Override
    public void onEpisodeClick(HkDetailHelper.Line line, HkCard episode) {
        if (mDetail == null || mRule == null) return;
        // MY_POSITION 槽：行内下标 + 规则名 + 集址身份，供 Router.playRoute 消费
        //（旧链路从未有人放值，恒为 -1；身份不合时消费方忽略，不串位）。
        int position = line.episodes == null ? -1 : line.episodes.indexOf(episode);
        if (position >= 0) HkDetailBridge.setClickPosition(position, mRule.getTitle(), episode.getUrl());
        if (HkRouter.isNonVideoRule(mRule)) {
            recordHistory(line, episode);
            HkPageActivity.routeEpisode(requireActivity(), mRule, mDetail, line, episode, arg("url"), mVod);
            return;
        }
        if (mVod == null) return;
        recordHistory(line, episode);
        VideoActivity.startHk(requireActivity(), mVod, line.title, episode.getTitle(), episode.getUrl());
    }

    /**
     * 点选集记历史（7b：星辰 HkHistoryRecorder.recordOpened，分流/交接处建记录；见 7b 文档 §3.3）：
     * 键与播放器 getHistoryKey 同形（toVod 口径已核对），不存在才建、已有只刷新展示字段与时间，
     * 进度字段不动；mVod == null 的影视支不记（没播的东西不建行）。
     */
    private void recordHistory(HkDetailHelper.Line line, HkCard episode) {
        HkHistoryRecorder.recordOpened(HkHistoryRecorder.keyOf(mRule.getTitle(), arg("url")),
                mDetail.info.title, mDetail.info.pic, line.title, episode.getTitle(), episode.getUrl());
    }

    /** 点动作行：走 HkClickDispatcher 执行（5e），不进详情页。 */
    @Override
    public void onActionClick(HkCard action) {
        if (mRule == null) return;
        HkClickDispatcher.handle(this, action);
    }

    /* ---------------- HkClickDispatcher.Host（5e） ---------------- */

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
        // 先按规则基地址补全（绝对地址原样返回，见 HkParserKit.resolveUrl）。
        if (mRule == null) return arg("url");
        return HkParserKit.resolveUrl(mRule.getUrl(), arg("url"));
    }

    @Override
    public HkRouter router() {
        return mRouter;
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

    @Override
    public void openSubpage(String url, String title) {
        if (mRule == null) return;
        HkListActivity.openSubpage(requireContext(), mRule.getTitle(), url);
    }
}
