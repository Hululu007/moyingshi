package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.download.VideoDownloadManager;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.activity.HkPageActivity;
import com.github.moyingshi.osc.tv.ui.web.CatWebActivity;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 动作 URL 执行器（5e：自 {@code HkActionHandler} 改写，见
 * docs/miniapp-xingchen-5e-click-dispatch-cleanup.md §2.1/§2.2）。
 * <p>
 * 与旧版的差别（其余分支逐字保留）：
 * <ul>
 * <li>懒求值改走常驻引擎（{@code Host.router()}）：{@code .js:}/{@code js:} 路用
 * {@code setNextInputOverride(input) + evalLazy(js, pageUrl, -1)}（{@code HkEngine.evalExpression}
 * 同款）；选择器路用 {@code HkRuleFetch} 抓页 + {@code getSelector().evalField}；不重跑
 * {@code preRule}（常驻引擎在页面加载时已跑过；重跑会重放青苹果♨️ {@code initConfig}+{@code rc}
 * 等副作用，{@code Router.evalTab} 也不重跑）；{@code refreshRequested} 由求值后
 * {@code consumeRefreshRequest()} 取（{@code evalTab} 同款，读后清零）。</li>
 * <li>{@code Host} 缩水：{@code vars()}/{@code updateVars()} 删除（新运行时 vars 在进程级
 * {@code varMap} 共享，懒求值与详情重跑读同一张表，不再需要 Fragment 的 {@code mVars}
 * 快照中转）；新增 {@code router()} 与 {@code openSubpage(url, title)}。</li>
 * <li>新增两支：裸 {@code hiker://page/…} → 7a 起先走 {@code HkPageActivity.openPage}
 * （{@code getNextPage} 组子规则开新页），组页失败回落 {@code host.openSubpage}
 * （5e 的 {@code HkSubpage.expand} 单页路；旧版返回 false → 详情页 →
 * {@code Router.detail} 判 {@code lazyParseFailed} → 错误态）；
 * 裸 {@code x5://…} → 剥前缀后进 {@code CatWebActivity}（与详情页 {@code dealKind=x5} 支同款）。</li>
 * <li>7a dealKind 短路：{@code pics/magnet/image} 三种不进详情页，直接走
 * {@code HkPageActivity.handleDealUrl}；{@code video/web} 走详情页原路。</li>
 * <li>懒结果分发：{@code http(s)} 与子页面支拆开——{@code http(s)} → {@code openDetail}，
 * {@code hiker://page/} → {@code openSubpage}（旧版两者都进 {@code openDetail}，子页面会
 * 撞上 {@code Router.detail} 的 {@code lazyParseFailed}）。</li>
 * <li>{@code DialogHost} 删除：{@code HkDialogs} 的异步三法（{@code inputAsync}/{@code confirmAsync}/
 * {@code selectAsync}，含 {@code Once}/{@code onMain}/{@code usable}）逐字移入本类作私有静态
 * 方法，弹框行为一字不变；同步三法与 {@code awaitResult} latch 整段删除（唯一用途是旧
 * Rhino 桥的 {@code DialogHost}）。</li>
 * <li>{@code pics://} 支仍 toast {@code hk_action_pics_unsupported}（旧行为；7a 起
 * 列表点击的 pics 卡由 {@code handleDealUrl} 直接进阅读器，本支只剩懒结果路）。</li>
 * </ul>
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkClickDispatcher {

    private static final String TAG = "HkClick";

    /** 点击宿主：三个 Fragment 各自实现（详情页不再持有 vars，列表/搜索页不保留）。 */
    public interface Host {
        /** 当前 Activity（可能已 detach，返回 null；弹框拿不到时回取消默认值）。 */
        Activity activity();

        HkRule rule();

        /** 懒执行时的 MY_URL：详情页 = 详情 URL；列表/搜索页 = 规则 url。 */
        String pageUrl();

        /** 常驻引擎所在的 Router（卡片可点时已在 loadPage/loadDetail 里建好）。 */
        HkRouter router();

        /** refreshPage() 语义：重跑当前页。 */
        void reloadPage();

        /** 懒结果是可打开的地址时，进详情页。 */
        void openDetail(String url, String title, String pic, String desc);

        /** 懒结果/卡片是子页面时，开新列表页（单页渲染，见 HkListFragment 子页面模式）。 */
        void openSubpage(String url, String title);
    }

    private HkClickDispatcher() {
    }

    /**
     * 卡片点击入口（主线程调）。返回 true = 已接管（弹框/执行/入队已开始，
     * 调用方不要再进详情页）；false = 普通 URL，调用方走原路。
     */
    public static boolean handle(Host host, HkCard card) {
        if (host == null || card == null) return false;
        String url = card.getUrl() == null ? "" : card.getUrl().trim();
        if (url.isEmpty()) return false;
        // @lazyRule= / @input= / @rule= 优先于裸 scheme（臻品fix 的 tab 卡片是
        // hiker://empty@lazyRule=…，@ 后半段才是动作）。
        int at = indexOfLazy(url);
        if (at >= 0) {
            executeLazy(host, card, url, at);
            return true;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("toast://")) {
            showToastText(url.substring("toast://".length()));
            return true;
        }
        if (lower.startsWith("copy://")) {
            handleCopy(host.activity(), url.substring("copy://".length()));
            return true;
        }
        if (lower.startsWith("download://")) {
            handleDownload(host.rule(), card, url.substring("download://".length()).trim());
            return true;
        }
        if (lower.startsWith("input://")) {
            handleInputBare(host, card, url.substring("input://".length()).trim());
            return true;
        }
        if (lower.startsWith("confirm://")) {
            handleConfirmBare(host, card, url.substring("confirm://".length()).trim());
            return true;
        }
        if (lower.startsWith("select://")) {
            handleSelectBare(host, card, url.substring("select://".length()).trim());
            return true;
        }
        if (lower.startsWith("hiker://page/")) {
            // 7a：先走 HkPageActivity.openPage（getNextPage 组子规则开新页，真规则、
            // 有分类有翻页）；组页失败回落 5e 的 openSubpage（HkSubpage.expand 单页路）。
            Activity activity = host.activity();
            if (activity != null && host.rule() != null
                    && HkPageActivity.openPage(activity, host.rule(), url, card.getExtra(),
                    cleanTitle(card.getTitle()))) {
                return true;
            }
            host.openSubpage(url, cleanTitle(card.getTitle()));
            return true;
        }
        if (lower.startsWith("x5://")) {
            Activity activity = host.activity();
            if (activity == null) {
                Log.w(TAG, "x5 action without activity, dropped");
                return true;
            }
            // WebView 载不了 x5:// scheme：剥前缀再进站内 WebView（星辰 handleDealUrl 同款）。
            activity.startActivity(CatWebActivity.intent(activity, url.replaceFirst("(?i)^x5://", "")));
            return true;
        }
        // 7a dealKind 短路（星辰列表点击路 handleDealUrl 同款）：pics/magnet/image 三种
        // 不进详情页，直接分流；video/web/"" 走详情页原路（与今日一致）。
        String dealKind = HkRouter.dealKind(url);
        if ("pics".equals(dealKind) || "magnet".equals(dealKind) || "image".equals(dealKind)) {
            Activity activity = host.activity();
            if (activity == null) {
                Log.w(TAG, "dealKind action without activity, dropped");
                return true;
            }
            if (HkPageActivity.handleDealUrl(activity, host.rule(), dealKind, url,
                    cleanTitle(card.getTitle()), card.getPic(), null)) {
                return true;
            }
        }
        if (lower.startsWith("hiker://empty")) {
            // 占位地址，无动作。
            return true;
        }
        return false;
    }

    /** 第一个 @lazyRule= / @input= / @rule= 的下标，没有返回 -1。 */
    private static int indexOfLazy(String url) {
        int best = -1;
        for (String marker : new String[]{"@lazyRule=", "@input=", "@rule="}) {
            int at = url.indexOf(marker);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** 匹配到的懒标记（@lazyRule= / @input= / @rule=），没有返回 ""。 */
    private static String lazyMarker(String url, int at) {
        for (String marker : new String[]{"@lazyRule=", "@input=", "@rule="}) {
            if (url.regionMatches(at, marker, 0, marker.length())) return marker;
        }
        return "";
    }

    /** 点击执行 @lazyRule=/@input=：@input= 先弹输入框，再后台线程跑懒 JS。 */
    private static void executeLazy(Host host, HkCard card, String url, int at) {
        if (host.rule() == null) {
            Log.w(TAG, "lazy action without rule, dropped");
            return;
        }
        String marker = lazyMarker(url, at);
        String pagePart = url.substring(0, at).trim();
        String lazy = stripNowSuffix(unescapeLazy(url.substring(at + marker.length()).trim()));
        if ("@input=".equals(marker)) {
            Activity activity = host.activity();
            if (activity == null) {
                Log.w(TAG, "input action without activity, dropped");
                return;
            }
            inputAsync(activity, cleanTitle(card.getTitle()), "", "", text -> {
                if (text == null) {
                    Notify.show(R.string.hk_action_cancelled);
                    return;
                }
                runLazy(host, card, pagePart, lazy, text);
            });
            return;
        }
        runLazy(host, card, pagePart, lazy, HkParserKit.resolveUrl(host.rule().getUrl(), pagePart));
    }

    /**
     * 后台线程跑懒 JS（常驻引擎），回来主线程按结果分发。
     * <p>
     * 旧版（{@code HkExecutor.evalLazy}）每求值新建一个引擎并重跑 {@code preRule}；新版用
     * Fragment 已有的常驻引擎（页面加载时已 {@code prepare + evalPreRule}），只求值不重跑
     * （重跑会重放 {@code initConfig}/{@code rc} 等副作用；{@code Router.evalTab} 同款）。
     * {@code DialogHost} 不再注入：新运行时 JS 的 {@code confirm/select/input} 走
     * {@code authGate}/{@code AuthBridgeEvent} 授权链，不 consult {@code DialogHost}。
     */
    private static void runLazy(Host host, HkCard card, String pagePart, String lazy, String input) {
        HkRule rule = host.rule();
        String pageUrl = host.pageUrl();
        HkRouter router = host.router();
        if (router == null || router.getEngine() == null || router.getEngine().getJsRuntime() == null) {
            Notify.show(App.get().getString(R.string.hk_list_load_failed, "引擎未就绪"));
            return;
        }
        new Thread(() -> {
            String result = "";
            boolean refresh = false;
            String error = null;
            try {
                HkJsRuntime rt = router.getEngine().getJsRuntime();
                String js = lazy == null ? "" : lazy.trim();
                if (js.startsWith(".js:") || js.startsWith("js:")) {
                    String body = js.startsWith(".js:") ? js.substring(4) : js.substring(3);
                    if (!TextUtils.isEmpty(body.trim())) {
                        // HkEngine.evalExpression 同款：input 覆盖 + evalLazy(js, pageUrl, -1)。
                        rt.setNextInputOverride(input == null ? "" : input);
                        String ret = rt.evalLazy(body, pageUrl, -1);
                        if (ret != null && !"undefined".equals(ret)) result = ret;
                    }
                } else {
                    HkRuleFetch.RequestSpec spec = HkRuleFetch.buildDetailRequest(rule, pageUrl);
                    String pageText = HkRuleFetch.isHttpUrl(spec.url) ? HkRuleFetch.fetch(spec) : "";
                    result = router.getEngine().getSelector().evalField(pageText, js, pageUrl);
                    if (result == null) result = "";
                }
                // evalTab 同款：求值后读 refreshPage 标记，读后清零，不误判残留标记。
                refresh = rt.consumeRefreshRequest();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String r = result;
            final boolean ref = refresh;
            final String err = error;
            App.post(() -> dispatchLazyResult(host, card, r, ref, err, false));
        }).start();
    }

    /** 懒执行结果分发（主线程）。入参由旧版 LazyOutcome 改为 (result, refreshRequested, error)。 */
    private static void dispatchLazyResult(Host host, HkCard card, String result,
                                           boolean refreshRequested, String error, boolean webRuleDone) {
        if (error != null) {
            Notify.show(TextUtils.isEmpty(error) ? App.get().getString(R.string.hk_list_load_failed, "") : error);
            return;
        }
        String r = result == null ? "" : result.trim();
        // 开头的 #…# 标记段（#noHistory# / #noLoading# / #noRefresh# 等）先剥掉再分发：
        // 臻品fix 排序卡片的懒 JS 末值就是 `#noHistory#hiker://empty`。
        r = stripLeadingHashMarkers(r);
        if (r.isEmpty()) {
            if (refreshRequested) host.reloadPage();
            return;
        }
        String lower = r.toLowerCase(Locale.ROOT);
        if (lower.startsWith("webRule://")) {
            if (webRuleDone) {
                Notify.show(R.string.hk_webrule_empty);
                return;
            }
            Activity activity = host.activity();
            // r 在上面被 stripLeadingHashMarkers 重赋过，不是 effectively final，
            // lambda 里不能直接捕获：先抄一份 final 变量。
            final String webRuleUrl = r;
            new Thread(() -> {
                String webResult = null;
                String webError = null;
                try {
                    webResult = HkWebRule.resolve(activity, webRuleUrl);
                } catch (Exception e) {
                    webError = e.getMessage();
                }
                final String wr = webResult;
                final String we = webError;
                App.post(() -> {
                    if (we != null) {
                        Notify.show(TextUtils.isEmpty(we) ? App.get().getString(R.string.hk_webrule_empty) : we);
                        return;
                    }
                    dispatchLazyResult(host, card, wr == null ? "" : wr, false, null, true);
                });
            }).start();
            return;
        }
        if (lower.startsWith("toast://")) {
            showToastText(r.substring("toast://".length()));
            if (refreshRequested) host.reloadPage();
            return;
        }
        if (lower.startsWith("hiker://empty")) {
            if (refreshRequested) host.reloadPage();
            return;
        }
        if (lower.startsWith("copy://")) {
            handleCopy(host.activity(), r.substring("copy://".length()));
            return;
        }
        if (lower.startsWith("download://")) {
            handleDownload(host.rule(), card, r.substring("download://".length()).trim());
            return;
        }
        if (lower.startsWith("pics://")) {
            Notify.show(R.string.hk_action_pics_unsupported);
            return;
        }
        if (lower.startsWith("{")) {
            String first = firstMultiLineUrl(r);
            if (!TextUtils.isEmpty(first)) {
                host.openDetail(stripHashMarkers(first), cleanTitle(card.getTitle()),
                        card.getPic(), card.getDesc());
            } else {
                Notify.show(R.string.hk_action_no_result);
            }
            return;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            host.openDetail(stripHashMarkers(r), cleanTitle(card.getTitle()),
                    card.getPic(), card.getDesc());
            return;
        }
        if (HkSubpage.isSubpageUrl(r)) {
            host.openSubpage(r, cleanTitle(card.getTitle()));
            return;
        }
        // 无法识别的结果：记日志，不 toast、不跳转。
        Log.i(TAG, "unrecognized lazy result: " + r.substring(0, Math.min(r.length(), 120)));
    }

    /** 剥掉开头的 #…# 标记段（可多个连写，如 #noHistory##noLoading#）；不是标记段的 # 开头字符串原样保留。 */
    private static String stripLeadingHashMarkers(String r) {
        String s = r;
        while (s.startsWith("#")) {
            int end = s.indexOf('#', 1);
            if (end < 0) break;
            s = s.substring(end + 1).trim();
        }
        return s;
    }

    /** 多线路 JSON（{urls,…}）取首条地址；不是多线路 JSON 返回 ""。 */
    private static String firstMultiLineUrl(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return "";
            JsonArray urls = parsed.getAsJsonObject().getAsJsonArray("urls");
            if (urls == null || urls.size() == 0) return "";
            JsonElement first = urls.get(0);
            return first != null && first.isJsonPrimitive() ? first.getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 剥掉 URL 里的 #…# 标记段（#noHistory# 等，位置不限，见懒求值约定）。 */
    private static String stripHashMarkers(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash).trim() : url.trim();
    }

    /** 海阔转义还原（只对规则部分）：？？→?、＆＆→&、；；→;、，，→,。 */
    private static String unescapeLazy(String lazy) {
        if (TextUtils.isEmpty(lazy)) return "";
        return lazy.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }

    /** 剥掉懒规则尾部的 ;now（“立即执行”标记，不是 JS；童话的选集懒加载用它）。 */
    private static String stripNowSuffix(String lazy) {
        if (TextUtils.isEmpty(lazy)) return "";
        String s = lazy.trim();
        if (s.length() > 4 && s.substring(s.length() - 4).equalsIgnoreCase(";now")) {
            return s.substring(0, s.length() - 4).trim();
        }
        return s;
    }

    /** toast:// 后半段原样弹提示（空文本不弹）。 */
    public static void showToastText(String text) {
        String msg = text == null ? "" : text.trim();
        if (!msg.isEmpty()) Notify.show(msg);
    }

    /**
     * copy:// 写剪贴板（Context 拿不到时用 App 上下文兜底），返回是否写成功；
     * 调用方按返回值 toast“已复制”或失败原因。
     */
    public static boolean copyToClipboard(Context context, String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return false;
        try {
            Context ctx = context != null ? context : App.get();
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("hk", value));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "copy failed", e);
            return false;
        }
    }

    /** copy://：写剪贴板 + toast 结果。 */
    public static void handleCopy(Context context, String text) {
        if (copyToClipboard(context, text)) {
            Notify.show(R.string.hk_action_copied);
        } else {
            Notify.show(R.string.hk_action_copy_failed);
        }
    }

    /**
     * download:// 入队：直链文件 URL 走现有下载通道
     * {@link VideoDownloadManager#enqueue}，siteKey 传空串走
     * {@code SiteApi} 的直链透传分支（不经过 hk 蜘蛛链）；来源记在
     * dramaName（规则标题）与 episodeName 里。只 toast 结果（调用方在主线程）。
     */
    public static void handleDownload(HkRule rule, HkCard card, String directUrl) {
        String notice = enqueueDownload(rule, card == null ? "" : cleanTitle(card.getTitle()),
                card == null ? "" : card.getPic(), directUrl);
        if (notice == null) {
            Notify.show(R.string.hk_action_download_bad_url);
        } else {
            Notify.show(notice);
        }
    }

    /**
     * download:// 入队（不 toast，返回提示文本；地址无效返回 null）。
     * 调用方是 {@link #handleDownload}（主线程）；提示文本由调用方弹。
     */
    public static String enqueueDownload(HkRule rule, String episodeName, String poster, String directUrl) {
        String url = directUrl == null ? "" : directUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        String drama = rule == null ? "" : rule.getTitle();
        String name = fileNameOf(url);
        if (name.isEmpty()) name = episodeName == null ? "" : episodeName.trim();
        if (name.isEmpty()) name = drama;
        if (drama.isEmpty()) drama = name;
        boolean enqueued = VideoDownloadManager.get().enqueue(
                drama, "", "", name, url, poster == null ? "" : poster);
        return App.get().getString(enqueued ? R.string.hk_action_download_queued
                : R.string.hk_action_download_exists);
    }

    /** URL 路径里的文件名（去 query/fragment）；拿不到返回 ""。 */
    private static String fileNameOf(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int slash = u.lastIndexOf('/');
        String name = slash >= 0 ? u.substring(slash + 1).trim() : u.trim();
        if (name.isEmpty() || name.contains("\\") || name.contains("/")) return "";
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    /** 裸 input://：弹输入框；确认后无后续链路可走，toast 回显输入（截断 100 字）；取消弹“已取消”。 */
    private static void handleInputBare(Host host, HkCard card, String prompt) {
        Activity activity = host.activity();
        if (activity == null) {
            Log.w(TAG, "input action without activity, dropped");
            return;
        }
        String title = cleanTitle(card.getTitle());
        inputAsync(activity, title.isEmpty() ? prompt : title, prompt, "", text -> {
            if (text == null) {
                Notify.show(R.string.hk_action_cancelled);
                return;
            }
            String shown = text.length() > 100 ? text.substring(0, 100) + "…" : text;
            Notify.show(App.get().getString(R.string.hk_action_input_done, shown));
        });
    }

    /** 裸 confirm://：弹确认框；确认 toast“已确认”，取消 toast“已取消”。 */
    private static void handleConfirmBare(Host host, HkCard card, String message) {
        Activity activity = host.activity();
        if (activity == null) {
            Log.w(TAG, "confirm action without activity, dropped");
            return;
        }
        String msg = message.isEmpty() ? cleanTitle(card.getTitle()) : message;
        confirmAsync(activity, "", msg, confirmed ->
                Notify.show(confirmed ? R.string.hk_action_confirmed : R.string.hk_action_cancelled));
    }

    /**
     * 裸 select://：{@code <标题>@<选项1>@<选项2>…}（无 @ 时按 , 切分，标题回落卡片标题）；
     * 选中 toast“已选择：<项>”，取消 toast“已取消”。
     */
    private static void handleSelectBare(Host host, HkCard card, String text) {
        Activity activity = host.activity();
        if (activity == null) {
            Log.w(TAG, "select action without activity, dropped");
            return;
        }
        List<String> options = new ArrayList<>();
        String title = cleanTitle(card.getTitle());
        if (text.contains("@")) {
            // <标题>@<选项1>@<选项2>…：parts[0] 是 URL 里带的标题（可能为空，即文本以 @ 开头）；
            // 选项一律从 parts[1] 起取。
            String[] parts = text.split("@");
            String urlTitle = parts.length > 0 ? parts[0].trim() : "";
            if (title.isEmpty()) title = urlTitle;
            for (int i = 1; i < parts.length; i++) {
                String opt = parts[i].trim();
                if (!opt.isEmpty()) options.add(opt);
            }
        } else {
            for (String part : text.split(",")) {
                String opt = part.trim();
                if (!opt.isEmpty()) options.add(opt);
            }
        }
        if (options.isEmpty()) {
            Notify.show(R.string.hk_action_cancelled);
            return;
        }
        selectAsync(activity, title, options, selected -> {
            if (selected == null) {
                Notify.show(R.string.hk_action_cancelled);
            } else {
                Notify.show(App.get().getString(R.string.hk_action_chosen, selected));
            }
        });
    }

    /** 卡片标题去 HTML 标签（规则标题常带 <font> 等，弹框标题与详情标题都用干净文本）。 */
    static String cleanTitle(String title) {
        return HkDetailHelper.stripHtml(title == null ? "" : title);
    }

    /** Activity 还能不能弹框。 */
    private static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** 回调只投递一次（确认投递后 dismiss 触发的 onDismiss 不再投递取消值）。 */
    private static final class Once<T> {
        private final AtomicBoolean delivered = new AtomicBoolean(false);
        private final Consumer<T> target;

        Once(Consumer<T> target) {
            this.target = target;
        }

        void deliver(T value) {
            if (delivered.compareAndSet(false, true) && target != null) target.accept(value);
        }
    }

    /** 确保在主线程执行（已在主线程直接跑，否则 post 到主线程）。 */
    private static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else App.post(r);
    }

    /**
     * 输入框（异步）：标题 + 提示 + 预填值；确认回输入文本，取消回 null。
     * 空输入弹“内容不能为空”且不关框（与 ActionCardHelper 同款）。
     */
    private static void inputAsync(Activity activity, String title, String hint, String preset,
                                   Consumer<String> onResult) {
        Once<String> once = new Once<>(onResult);
        if (!usable(activity)) {
            once.deliver(null);
            return;
        }
        onMain(() -> {
            if (!usable(activity)) {
                once.deliver(null);
                return;
            }
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_action_input, null);
            EditText edit = view.findViewById(R.id.action_input);
            if (!TextUtils.isEmpty(preset)) edit.setText(preset);
            if (!TextUtils.isEmpty(hint)) edit.setHint(hint);
            AlertDialog dialog = new WebHtvAlertDialogBuilder(activity)
                    .setTitle(TextUtils.isEmpty(title) ? activity.getString(R.string.hk_action_input_title) : title)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnDismissListener((DialogInterface d) -> once.deliver(null));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = edit.getText().toString().trim();
                if (TextUtils.isEmpty(text)) {
                    Notify.show(R.string.hk_action_input_empty);
                    return;
                }
                once.deliver(text);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
        });
    }

    /**
     * 确认框（异步）：确认回 true，取消/返回键/点框外回 false。
     */
    private static void confirmAsync(Activity activity, String title, String message,
                                     Consumer<Boolean> onResult) {
        Once<Boolean> once = new Once<>(onResult);
        if (!usable(activity)) {
            once.deliver(false);
            return;
        }
        onMain(() -> {
            if (!usable(activity)) {
                once.deliver(false);
                return;
            }
            AlertDialog dialog = new WebHtvAlertDialogBuilder(activity)
                    .setTitle(TextUtils.isEmpty(title) ? activity.getString(R.string.hk_action_confirm_title) : title)
                    .setMessage(message == null ? "" : message)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnDismissListener((DialogInterface d) -> once.deliver(false));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                once.deliver(true);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
        });
    }

    /**
     * 选项列表（异步）：点某一项回该项文本，取消/返回键/点框外回 null。
     * options 为空时不弹框，直接回 null。
     */
    private static void selectAsync(Activity activity, String title, List<String> options,
                                    Consumer<String> onResult) {
        Once<String> once = new Once<>(onResult);
        if (options == null || options.isEmpty() || !usable(activity)) {
            once.deliver(null);
            return;
        }
        onMain(() -> {
            if (!usable(activity)) {
                once.deliver(null);
                return;
            }
            CharSequence[] items = options.toArray(new CharSequence[0]);
            AlertDialog dialog = new WebHtvAlertDialogBuilder(activity)
                    .setTitle(TextUtils.isEmpty(title) ? activity.getString(R.string.hk_action_select_title) : title)
                    .setItems(items, (d, which) -> {
                        if (which >= 0 && which < options.size()) once.deliver(options.get(which));
                        else once.deliver(null);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnDismissListener((DialogInterface d) -> once.deliver(null));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
        });
    }
}
