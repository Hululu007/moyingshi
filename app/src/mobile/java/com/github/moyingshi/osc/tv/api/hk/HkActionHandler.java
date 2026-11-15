package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.download.VideoDownloadManager;
import com.github.moyingshi.osc.tv.ui.hk.HkDialogs;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 动作 URL 执行器（第 7 步之七）。
 * <p>
 * 卡片点击的统一分发（列表/搜索/详情三处都先走这里）：{@code @lazyRule=} /
 * {@code @input=} 产出的懒链接先执行懒 JS 再按结果分发；裸 scheme
 * （{@code toast://} / {@code copy://} / {@code download://} /
 * {@code input://} / {@code confirm://} / {@code select://} /
 * {@code hiker://empty}）按约定执行；其余 URL 返回 false，调用方走原路
 * （进详情/播放），不进空白页。
 * <p>
 * 语义来源：逆向报告只有 {@code isActionUrl()} 谓词可读（见任务文档 E1），
 * 执行分支无源码；{@code toast://<文本>} 有官方文档长按示例佐证（E3），
 * 其余裸 scheme 的文本格式是本步约定（见任务文档 §6 缺口 2），拿到真实
 * 规则样本对不上时按样本改解析，不动弹框层。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkActionHandler {

    private static final String TAG = "HkAction";

    /** 点击宿主：三个 Fragment 各自实现（详情页持有 vars，列表/搜索页不保留）。 */
    public interface Host {
        /** 当前 Activity（可能已 detach，返回 null；弹框拿不到时回取消默认值）。 */
        Activity activity();

        HkRule rule();

        /** 懒执行时的 MY_URL：详情页 = 详情 URL；列表/搜索页 = 规则 url。 */
        String pageUrl();

        /** 当前 vars 快照（详情页持有，列表/搜索页返回空 map）。 */
        Map<String, Object> vars();

        /** 写回执行后的 vars 快照（列表/搜索页可空操作）。 */
        void updateVars(Map<String, Object> vars);

        /** refreshPage() 语义：重跑当前页。 */
        void reloadPage();

        /** 懒结果是可打开的地址时，进详情页。 */
        void openDetail(String url, String title, String pic, String desc);
    }

    private HkActionHandler() {
    }

    /** 给 Rhino 桥用的 DialogHost：弹框走 HkDialogs，Activity 取自宿主（可能为 null）。 */
    public static HkEngine.DialogHost dialogHostFor(Host host) {
        return new HkEngine.DialogHost() {
            @Override
            public boolean confirm(String title, String message) {
                return HkDialogs.confirm(host.activity(), title, message);
            }

            @Override
            public String select(String title, List<String> options) {
                return HkDialogs.select(host.activity(), title, options);
            }

            @Override
            public String input(String title, String hint, String preset) {
                return HkDialogs.input(host.activity(), title, hint, preset);
            }
        };
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
        String lazy = HkExecutor.stripNowSuffix(HkExecutor.unescapeLazy(url.substring(at + marker.length()).trim()));
        if ("@input=".equals(marker)) {
            Activity activity = host.activity();
            if (activity == null) {
                Log.w(TAG, "input action without activity, dropped");
                return;
            }
            HkDialogs.inputAsync(activity, cleanTitle(card.getTitle()), "", "", text -> {
                if (text == null) {
                    Notify.show(R.string.hk_action_cancelled);
                    return;
                }
                runLazy(host, card, pagePart, lazy, text);
            });
            return;
        }
        runLazy(host, card, pagePart, lazy, HkParser.resolveUrl(host.rule().getUrl(), pagePart));
    }

    /** 后台线程跑懒 JS（HkExecutor.evalLazy），回来主线程按结果分发。 */
    private static void runLazy(Host host, HkCard card, String pagePart, String lazy, String input) {
        HkRule rule = host.rule();
        String pageUrl = host.pageUrl();
        Map<String, Object> vars = host.vars();
        HkEngine.DialogHost dialogHost = dialogHostFor(host);
        new Thread(() -> {
            HkExecutor.LazyOutcome outcome = null;
            String error = null;
            try {
                outcome = HkExecutor.evalLazy(rule, pageUrl, lazy, input, vars, dialogHost);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final HkExecutor.LazyOutcome result = outcome;
            final String err = error;
            App.post(() -> dispatchLazyResult(host, card, result, err, false));
        }).start();
    }

    /** 懒执行结果分发（主线程，见任务文档 §3.5）。 */
    private static void dispatchLazyResult(Host host, HkCard card, HkExecutor.LazyOutcome outcome,
                                           String error, boolean webRuleDone) {
        if (error != null) {
            Notify.show(TextUtils.isEmpty(error) ? App.get().getString(R.string.hk_list_load_failed, "") : error);
            return;
        }
        if (outcome == null) return;
        if (outcome.vars != null) host.updateVars(outcome.vars);
        String result = outcome.result == null ? "" : outcome.result.trim();
        // 开头的 #…# 标记段（#noHistory# / #noLoading# / #noRefresh# 等）先剥掉再分发：
        // 臻品fix 排序卡片的懒 JS 末值就是 `#noHistory#hiker://empty`。
        result = stripLeadingHashMarkers(result);
        if (result.isEmpty()) {
            if (outcome.refreshRequested) host.reloadPage();
            return;
        }
        String lower = result.toLowerCase(Locale.ROOT);
        if (lower.startsWith("webRule://")) {
            if (webRuleDone) {
                Notify.show(R.string.hk_webrule_empty);
                return;
            }
            Activity activity = host.activity();
            // result 在上面被 stripLeadingHashMarkers 重赋过，不是 effectively final，
            // lambda 里不能直接捕获：先抄一份 final 变量。
            final String webRuleUrl = result;
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
                    HkExecutor.LazyOutcome second = new HkExecutor.LazyOutcome();
                    second.result = wr == null ? "" : wr;
                    dispatchLazyResult(host, card, second, null, true);
                });
            }).start();
            return;
        }
        if (lower.startsWith("toast://")) {
            showToastText(result.substring("toast://".length()));
            if (outcome.refreshRequested) host.reloadPage();
            return;
        }
        if (lower.startsWith("hiker://empty")) {
            if (outcome.refreshRequested) host.reloadPage();
            return;
        }
        if (lower.startsWith("copy://")) {
            handleCopy(host.activity(), result.substring("copy://".length()));
            return;
        }
        if (lower.startsWith("download://")) {
            handleDownload(host.rule(), card, result.substring("download://".length()).trim());
            return;
        }
        if (lower.startsWith("pics://")) {
            Notify.show(R.string.hk_action_pics_unsupported);
            return;
        }
        if (lower.startsWith("{")) {
            String first = firstMultiLineUrl(result);
            if (!TextUtils.isEmpty(first)) {
                host.openDetail(HkExecutor.stripHashMarkers(first), cleanTitle(card.getTitle()),
                        card.getPic(), card.getDesc());
            } else {
                Notify.show(R.string.hk_action_no_result);
            }
            return;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://") || HkSubpage.isSubpageUrl(result)) {
            host.openDetail(HkExecutor.stripHashMarkers(result), cleanTitle(card.getTitle()),
                    card.getPic(), card.getDesc());
            return;
        }
        // 无法识别的结果：记日志，不 toast、不跳转。
        Log.i(TAG, "unrecognized lazy result: " + result.substring(0, Math.min(result.length(), 120)));
    }

    /** 剥掉开头的 #…# 标记段（可多个连写，如 #noHistory##noLoading#）；不是标记段的 # 开头字符串原样保留。 */
    private static String stripLeadingHashMarkers(String result) {
        String s = result;
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
     * download:// 入队（见任务文档 §3.3）：直链文件 URL 走现有下载通道
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
     * 播放链（后台线程）复用它，提示经 {@code PlayOutcome.notice} 回主线程再弹。
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

    /** 裸 input://：弹输入框；确认后无后续链路可跑，toast 回显输入（截断 100 字）；取消弹“已取消”。 */
    private static void handleInputBare(Host host, HkCard card, String prompt) {
        Activity activity = host.activity();
        if (activity == null) {
            Log.w(TAG, "input action without activity, dropped");
            return;
        }
        String title = cleanTitle(card.getTitle());
        HkDialogs.inputAsync(activity, title.isEmpty() ? prompt : title, prompt, "", text -> {
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
        HkDialogs.confirmAsync(activity, "", msg, confirmed ->
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
        HkDialogs.selectAsync(activity, title, options, selected -> {
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

    /** 空 vars 快照（列表/搜索页的 Host 用：不保留 putVar 结果）。 */
    public static Map<String, Object> emptyVars() {
        return new HashMap<>();
    }
}
