package com.github.moyingshi.osc.tv.api.hk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.utils.WebViewUtil;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code webRule://} WebView 解析分支，对应魔影小程序 {@code f22.v()}
 * （见逆向报告 §7.10，全文可读，按它搭骨架）。
 * <p>
 * 格式 {@code webRule://<url>@<js>}：隐藏 WebView 加载页面，页载完后注入
 * 轮询脚本（{@code __wrq/__wrcheck}，每 250ms 求值一次、最多 120 次），
 * 规则 JS 一出非空结果就经 {@code fy_bridge_app.finishParse} 交回，后台线程
 * 用 30 秒 latch 等。加载失败/超时/无结果都抛 {@link HkEngine.HkException}
 *（带明确原因），不回空串、不悬着。
 * <p>
 * 约束：必须在后台线程调（主线程调直接抛错，防 latch 卡死主线程）；
 * Context 必须是未销毁的 Activity（WebView 要在主线程建）；
 * 页面地址只认 http(s)；桥只暴露 {@code finishParse} 一个方法
 * （见 {@code CatWebActivity} 的注释：挂上桥就等于把 Java 方法交给页面脚本，
 * 所以桥面最小化，且建 WebView 后关文件访问）。
 * <p>
 * 已知风险：未挂到窗口的 WebView 在个别机型上 {@code setTimeout} 可能被节流，
 * 导致 30 秒轮询无果而报超时；若真机出现，改走 Java 侧 Handler 每 250ms
 * 主动 {@code evaluateJavascript("__wrq()")} 轮询，不动接口。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkWebRule {

    private static final String TAG = "HkWebRule";
    /** 与 f22.v() 同款上限：轮询 120 次 × 250ms ≈ 30 秒。 */
    private static final int MAX_POLLS = 120;
    private static final long POLL_MS = 250;
    private static final long TIMEOUT_MS = 30000;

    private HkWebRule() {
    }

    /** JS 桥：只暴露 finishParse；uuid 对不上（过期回调）直接丢弃。 */
    private static final class Bridge {
        private final String uuid;
        private final AtomicReference<String> result;
        private final CountDownLatch latch;

        Bridge(String uuid, AtomicReference<String> result, CountDownLatch latch) {
            this.uuid = uuid;
            this.result = result;
            this.latch = latch;
        }

        @JavascriptInterface
        public void finishParse(String value, String type, String id) {
            if (!uuid.equals(id)) return;
            if (result.get() == null) result.set(value == null ? "" : value);
            latch.countDown();
        }
    }

    /**
     * 解析一条 {@code webRule://<url>@<js>}，返回规则 JS 在页面上求出的字符串。
     * 失败抛 HkException（格式错 / Activity 不可用 / 加载失败 / 超时 / 无结果）。
     * 调用方必须在后台线程跑。
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static String resolve(Context context, String webRule) throws HkEngine.HkException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new HkEngine.HkException("webRule 不能在主线程解析");
        }
        if (!(context instanceof Activity)) {
            throw new HkEngine.HkException("webRule 需要页面上下文（Activity 不可用）");
        }
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) {
            throw new HkEngine.HkException("webRule 需要页面上下文（Activity 已销毁）");
        }
        String raw = webRule == null ? "" : webRule.trim();
        if (raw.length() <= "webRule://".length()
                || !raw.regionMatches(true, 0, "webRule://", 0, "webRule://".length())) {
            throw new HkEngine.HkException(context.getString(R.string.hk_webrule_bad_format));
        }
        String body = raw.substring("webRule://".length()).trim();
        int at = body.indexOf('@');
        if (at < 0) {
            throw new HkEngine.HkException(context.getString(R.string.hk_webrule_bad_format));
        }
        String pageUrl = body.substring(0, at).trim();
        String js = body.substring(at + 1).trim();
        if (TextUtils.isEmpty(pageUrl) || TextUtils.isEmpty(js)) {
            throw new HkEngine.HkException(context.getString(R.string.hk_webrule_bad_format));
        }
        String lower = pageUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new HkEngine.HkException("webRule 页面地址不是 http(s)：" + pageUrl);
        }

        String uuid = UUID.randomUUID().toString();
        AtomicReference<String> result = new AtomicReference<>(null);
        AtomicReference<String> loadError = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);
        Bridge bridge = new Bridge(uuid, result, latch);
        AtomicReference<WebView> webViewRef = new AtomicReference<>(null);
        AtomicBoolean injected = new AtomicBoolean(false);
        String pollScript = buildPollScript(js, uuid);

        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                bridge.finishParse("", "webkit", uuid);
                return;
            }
            WebView webView;
            try {
                webView = new WebView(activity);
            } catch (Exception e) {
                Log.w(TAG, "create WebView failed", e);
                loadError.set(e.getMessage() == null ? "创建 WebView 失败" : e.getMessage());
                bridge.finishParse("", "webkit", uuid);
                return;
            }
            webViewRef.set(webView);
            WebViewUtil.configureBase(webView, "hk-webrule");
            webView.getSettings().setAllowFileAccess(false);
            webView.getSettings().setAllowContentAccess(false);
            webView.addJavascriptInterface(bridge, "fy_bridge_app");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (injected.compareAndSet(false, true)) {
                        view.evaluateJavascript(pollScript, null);
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request != null && request.isForMainFrame()) {
                        loadError.set(error == null ? "页面加载失败" : String.valueOf(error.getDescription()));
                        bridge.finishParse("", "webkit", uuid);
                    }
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    // 旧接口分不清主页面还是子资源：只记错误，不提前收尾（主页面失败时
                    // onPageFinished 不会触发，30 秒 latch 超时后仍会报明确错）。
                    if (loadError.get() == null) {
                        loadError.set(description == null ? "页面加载失败" : description);
                    }
                }
            });
            webView.loadUrl(pageUrl);
        });

        boolean done;
        try {
            done = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyWebView(main, webViewRef);
            throw new HkEngine.HkException(context.getString(R.string.hk_webrule_empty));
        } finally {
            destroyWebView(main, webViewRef);
        }
        String value = result.get();
        if (!TextUtils.isEmpty(value)) return value;
        if (loadError.get() != null) {
            throw new HkEngine.HkException(context.getString(R.string.hk_webrule_load_failed, loadError.get()));
        }
        if (!done) {
            throw new HkEngine.HkException(context.getString(R.string.hk_webrule_timeout));
        }
        throw new HkEngine.HkException(context.getString(R.string.hk_webrule_empty) + "：" + pageUrl);
    }

    /** 主线程销毁 WebView（stopLoading + destroy，finally 里一定执行，不留悬空页）。 */
    private static void destroyWebView(Handler main, AtomicReference<WebView> ref) {
        main.post(() -> {
            WebView webView = ref.getAndSet(null);
            if (webView == null) return;
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception e) {
                Log.w(TAG, "destroy WebView failed", e);
            }
        });
    }

    /**
     * 轮询脚本（与 f22.v() 的拼串逐字对应结构）：{@code __wrq} 求值规则 JS，
     * 出错回 null；{@code __wrcheck} 每 250ms 试一次，非空即
     * {@code finishParse}，120 次还没结果也 {@code finishParse('')} 收尾。
     */
    private static String buildPollScript(String js, String uuid) {
        String escaped = escapeJsSingleQuoted(js);
        return "function __wrq(){try{return eval('" + escaped + "');}catch(e){return null;}}\n"
                + "var __wrc=0;\n"
                + "function __wrcheck(){if(__wrc>" + MAX_POLLS + "){fy_bridge_app.finishParse('','webkit','"
                + uuid + "');return;}\n"
                + "__wrc++;setTimeout(function(){var u=__wrq();if(u!=null&&u!==''){fy_bridge_app.finishParse(u,'webkit','"
                + uuid + "');}else{__wrcheck();}},"
                + POLL_MS + ");}\n"
                + "__wrcheck();";
    }

    /** f22.v() 的转义：\n→\\n、\r 丢弃、'→\\'、\→\\\\，其余原样。 */
    private static String escapeJsSingleQuoted(String js) {
        StringBuilder out = new StringBuilder(js.length() + 16);
        for (int i = 0; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '\n') out.append("\\n");
            else if (c == '\r') { /* 丢弃，与 v() 一致 */ }
            else if (c == '\'') out.append("\\'");
            else if (c == '\\') out.append("\\\\");
            else out.append(c);
        }
        return out.toString();
    }
}
