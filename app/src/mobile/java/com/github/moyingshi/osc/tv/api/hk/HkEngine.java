package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.collection.ArrayMap;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 最小 Rhino 引擎封装，对应魔影小程序的 w02（见逆向报告 §7.18 与评估）。
 * <p>
 * 只做一件事：给一条规则的 JS 提供运行环境（解释器模式 + 海阔 JS API 的
 * 第 1 步子集）。一次 HkEngine 实例对应一次页面执行（preRule + find_rule
 * 共用同一个 scope），单线程使用，不要跨线程共用。
 * <p>
 * 关键约束：
 * <ul>
 *   <li>解释器模式：{@link HkContextFactory} 把 optimizationLevel 固定为 -1。
 *       Android 上不能用 Rhino 的优化模式（动态生成字节码），否则跑不起来。
 *   <li>规则 JS 是 ES5（var/function/普通 for）；Rhino 默认语言版本即可。
 *   <li>网络是同步的（fetch/getResCode 都是同步返回）：调用方必须在后台
 *       线程跑 HkExecutor，绝不能在主线程调。
 * </ul>
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkEngine {

    private static final String TAG = "HkEngine";
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /**
     * 把规则 ua 简写映射成真浏览器 UA（海阔规则的 ua 是 "mobile"/"auto"/"pc"
     * 这类简写；原样发出会被站点当爬虫/非浏览器挡掉，见 docs/miniapp-nodata-fix.md）。
     * 已是 Mozilla/ 开头的真 UA 或其它自定义值原样通过。
     */
    public static String resolveUa(String ua) {
        if (TextUtils.isEmpty(ua)) return MOBILE_UA;
        String v = ua.trim();
        if ("mobile".equalsIgnoreCase(v) || "auto".equalsIgnoreCase(v)) return MOBILE_UA;
        if ("pc".equalsIgnoreCase(v)) return PC_UA;
        return v;
    }

    /**
     * 规则 JS 里用的内置 API（注入 scope 的 JS 函数，见 {@link #BOOTSTRAP}）。
     * 覆盖 js_api.md 里列表解析真正用到的那部分：输出（setResult/setError/log/
     * toast）、请求（fetch/post/request/getResCode）、DOM（parseDom/pdfh/pdfa）、
     * 编解码（md5/base64）、变量（putVar/getVar/putMyVar/getMyVar/getParam，
     * putVar 支持 {key, value} 对象形式）、文件与持久 KV（readFile/writeFile/
     * saveFile/fileExist/deleteFile、getItem/setItem（对齐第 5 项：落盘
     * data/&lt;标题&gt;/kv.json，三参可跨规则读写；`storage0` 对象照官方
     * plugin.js，getItem/setItem/getPublicItem/setPublicItem/getVar/putVar/
     * getMyVar/putMyVar，JSON 自动转换；公共 KV 在 rules/_item00.json，
     * 全规则共享）；myVar 与 item 都落盘到
     * plugins/hk/data/&lt;标题&gt;/，见 {@link HkStore}）、子页面与库
     * （require/$.require 走 {@link HkSubpage}，库 URL 走 require.json 清单）、
     * 规则配置（对齐第 1 项，照官方 plugin.js 的 JS 版：`config` 是全局上的
     * accessor，get 时 `config0` 为空才从 `getMyVar('initConfig')` 懒加载并缓存，
     * set 只写 `config0`、不回写存储；`initConfig(o)` 把对象**合并**进
     * `getMyVar('initConfig')` 的 JSON（非对象/空对象入参时返回现有 config、
     * 不写不抛，见 docs/miniapp-align-1-config.md §3.1/§3.3）；存储是
     * per-rule 的 `putMyVar`/`getMyVar`（内存 + `data/&lt;标题&gt;/myvar.json`，
     * 按标题隔离，等价于官方 varMap 的 `&lt;标题&gt;@initConfig` 键）——preRule
     * 每次求值前都跑，播种后同进程后续求值都读得到；`config/*.json` 磁盘文件
     * 官方 JS 版不写、且在我们这里是 require.json 清单的存放处，故不写它。
     * `rc(url, hours)` 走 `requireCache`：小时数兑现（默认 24，-1 永久，0 永不
     * 命中，见 {@link #isCacheFresh}）；plain `require` 传 -1，与官方一致。
     * 域内变量（MY_URL/MY_HOME/MY_RULE/MY_PAGE/MY_TYPE/MY_PARAMS/MOBILE_UA/
     * PC_UA）与 $ 工具（$.toString/$.stringify/$.require/$.hiker/$.importParam/
     * $.exports；$ 本身可调用，返回带 lazyRule/input 的构造器，只产字符串，
     * 点击执行走 {@link HkActionHandler}（第 7 步之七）；getUrl()/getHome()
     * 取当前页/主页地址，refreshPage() 置位并发事件驱动当前页重求值（对齐第 3 项，
     * 见 {@link HkRefreshPageEvent}；点击懒规则求值时事件关掉、只置位，由调用方消费）。
     * 暂不支持的（xpath/getCryptoJS/aes/encodeStr 等）在 BOOTSTRAP 里是会抛
     * 明确错误的桩，不是静默缺失——规则用到了能在日志里一眼看到缺的是哪个 API。
     * batchFetch 已实现（见 {@link Bridge#batchFetch}），不再是桩。
     * confirm/select/input 对话框是第 7 步之七接通的（经 {@link DialogHost} 弹框，
     * 无 host 时仍抛明确错）。注意：没有全局 input() 函数——
     * {@code input} 是懒加载求值时的输入变量名（见 {@link #evalExpression}），
     * 同名函数会把它盖掉；输入框用 $(...).input(fn) 构造器。
     */
    private static final String BOOTSTRAP = """
            function __unsupported(name) { return function() { throw new Error(name + ' 暂不支持（小程序地基第 1 步未实现）'); }; }
            function fetch(url, options) { return __hk.fetch(url, options); }
            function post(url, options) { return __hk.post(url, options); }
            function getResCode() { return __hk.getResCode(); }
            function setResult(d) { __hk.setResult(d); }
            function setHomeResult(d) { __hk.setResult(d); }
            function setSearchResult(d) { __hk.setResult(d); }
            function setError(msg) { __hk.setError(msg); }
            var error = setError;
            function log(x) { __hk.log(x); }
            function toast(msg) { __hk.toast(msg); }
            function parseDom(html, sel) { return __hk.parseDom(html, sel); }
            var pd = parseDom;
            function parseDomForHtml(html, sel) { return __hk.parseDomForHtml(html, sel); }
            var pdfh = parseDomForHtml;
            function parseDomForArray(html, sel) { return __hk.parseDomForArray(html, sel); }
            var pdfa = parseDomForArray;
            function md5(x) { return __hk.md5(x); }
            function base64Encode(x) { return __hk.base64Encode(x); }
            function base64Decode(x) { return __hk.base64Decode(x); }
            function putVar(k, v) { __hk.putVar(k, v); }
            function getUrl() { return __hk.getPageUrl(); }
            function getHome(url) { return __hk.getHome(url); }
            function setPageTitle(t) { __hk.setPageTitle(t); }
            function refreshPage(scrollTop) { if (scrollTop === undefined) { __hk.refreshPage(); } else { __hk.refreshPage(scrollTop); } }
            function getVar(k, d) { return __hk.getVar(k, d); }
            function clearVar(k) { if (k === undefined || k === null) { __hk.clearVar(); } else { __hk.clearVar(String(k)); } }
            function putMyVar(k, v) { __hk.putMyVar(k, v); }
            function getMyVar(k, d) { return __hk.getMyVar(k, d); }
            function clearMyVar() { __hk.clearMyVar(); }
            function getParam(k, d) { return __hk.getParam(k, d); }
            function readFile(name) { return __hk.readFile(name); }
            function writeFile(name, content) { return __hk.writeFile(name, content); }
            function saveFile(name, content) { return __hk.saveFile(name, content); }
            function fileExist(name) { return __hk.fileExist(name); }
            function deleteFile(name) { return __hk.deleteFile(name); }
            function getItem(k, d, t) { return __hk.getItem(k, d, t); }
            function setItem(k, v, t) { __hk.setItem(k, v, t); }
            function getPublicItem(k, d) { return __hk.getPublicItem(k, d); }
            function setPublicItem(k, v) { __hk.setPublicItem(k, v); }
            function clearPublicItem(k) { __hk.clearPublicItem(k); }
            // storage0（对齐第 5 项，照官方 plugin.js）：与官方两处必要改写——
            // toJsonObj 先判 typeof（我们的 getVar/getMyVar 可能返回真对象，
            // 官方只返回字符串）；{ / [ 判断用 charAt 而不用 startsWith/endsWith。
            function toJsonStr(obj) {
              if (obj == null) { obj = ''; }
              if (typeof obj !== 'string') { obj = JSON.stringify(obj); }
              return obj;
            }
            function toJsonObj(a) {
              if (!a) { return a; }
              if (typeof a !== 'string') { return a; }
              if (a.charAt(0) === '{' && a.charAt(a.length - 1) === '}') {
                try { return JSON.parse(a); } catch (e) {}
              }
              if (a.charAt(0) === '[' && a.charAt(a.length - 1) === ']') {
                try { return JSON.parse(a); } catch (e) {}
              }
              return a;
            }
            var storage0 = {
              getItem: function(key, defaultVal) {
                var a = getItem(key, toJsonStr(defaultVal));
                return toJsonObj(a);
              },
              setItem: function(key, obj) { setItem(key, toJsonStr(obj)); },
              getPublicItem: function(key, defaultVal) {
                var a = getPublicItem(key, toJsonStr(defaultVal));
                return toJsonObj(a);
              },
              setPublicItem: function(key, obj) { setPublicItem(key, toJsonStr(obj)); },
              getVar: function(key, defaultVal) {
                var a = getVar(key, toJsonStr(defaultVal));
                return toJsonObj(a);
              },
              putVar: function(key, obj) { putVar(key, toJsonStr(obj)); },
              getMyVar: function(key, defaultVal) {
                var a = getMyVar(key, toJsonStr(defaultVal));
                return toJsonObj(a);
              },
              putMyVar: function(key, obj) { putMyVar(key, toJsonStr(obj)); }
            };
            function confirm(o) { return __hk.confirm(o); }
            function select(o) { return __hk.select(o); }
            function require(url, params) { return __hk.require(url, params); }
            (function(windows) {
              function initConfig(obj) {
                if (obj == null || typeof obj !== 'object') { return windows.config; }
                var merged = {};
                try { var s = getMyVar('initConfig', '{}'); if (s) { merged = JSON.parse(s); } } catch (e) {}
                var empty = true;
                try { empty = (JSON.stringify(obj) === '{}'); } catch (e) { empty = true; }
                if (empty) { return windows.config; }
                var keys = Object.keys(obj);
                for (var i = 0; i < keys.length; i++) { merged[keys[i]] = obj[keys[i]]; }
                putMyVar('initConfig', JSON.stringify(merged));
                windows.config = merged;
                return windows.config;
              }
              windows.initConfig = initConfig;
              try {
                Object.defineProperty(windows, "config", {
                  enumerable: true,
                  configurable: true,
                  get: function() {
                    if (windows.config0 == null) {
                      var c = {};
                      try { var s = getMyVar('initConfig', '{}'); if (s) { c = JSON.parse(s); } } catch (e) {}
                      windows.config0 = c;
                    }
                    return windows.config0;
                  },
                  set: function(v) { windows.config0 = v; }
                });
              } catch (e) { log(e.toString()); }
            })(this);
            function rc(url, hours) { return __hk.requireCache(url, hours); }
            function getRule() { return JSON.parse(__hk.getRuleJson()); }
            var MY_RULE = JSON.parse(__hk.getRuleJson());
            var MY_PARAMS = JSON.parse(__hk.getParamsJson());
            var MY_URL = __hk.getPageUrl();
            var MY_HOME = __hk.getHomeUrl();
            var MY_PAGE = __hk.getPage();
            var MY_TYPE = __hk.getPageType();
            var MOBILE_UA = __hk.getMobileUa();
            var PC_UA = __hk.getPcUa();
            function $(sel, hint) {
              var s = (sel === undefined || sel === null) ? '' : String(sel);
              return {
                lazyRule: function(fn) { var args = Array.prototype.slice.call(arguments, 1); return s + '@lazyRule=' + $.toString.apply(null, [fn].concat(args)); },
                input: function(fn) { var args = Array.prototype.slice.call(arguments, 1); return s + '@input=' + $.toString.apply(null, [fn].concat(args)); }
              };
            }
            $.toString = function(fn) { var args = Array.prototype.slice.call(arguments, 1); return '(' + fn.toString() + ')(' + args.map(function(a) { return JSON.stringify(a); }).join(',') + ')'; };
            $.stringify = function(o) { return JSON.stringify(o); };
            $.require = function(url, params) { return __hk.require(url, params); };
            $.hiker = {};
            $.importParam = {};
            $.exports = undefined;
            var xpath = __unsupported('xpath');
            var xpa = xpath;
            var xpathArray = __unsupported('xpathArray');
            function batchFetch(arr, threadNum) { return __hk.batchFetch(arr, threadNum); }
            var bf = batchFetch;
            var getCryptoJS = __unsupported('getCryptoJS');
            var encodeStr = __unsupported('encodeStr');
            var decodeStr = __unsupported('decodeStr');
            var aesEncode = __unsupported('aesEncode');
            var aesDecode = __unsupported('aesDecode');
            function request(url, options) { return __hk.fetch(url, options); }
            if (typeof unescape === 'undefined') { var unescape = function(s) { try { return decodeURIComponent(String(s)); } catch (e) { return String(s); } }; }
            """;

    /** 引擎抛出的异常：JS 语法/运行错、preRule 失败、网络失败都包成这个。 */
    public static class HkException extends Exception {
        public HkException(String message) { super(message); }
        public HkException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 弹框宿主（第 7 步之七）：规则 JS 调 {@code confirm/select/input} 时，
     * 引擎经它弹框问用户；返回 null/false = 用户取消。宿主由调用方
     * （Fragment/Activity）在后台线程求值前通过 {@link #setDialogHost} 挂上；
     * 没挂 host 时 confirm/select 仍抛明确错（第 1–6 步的原行为）。
     * 实现方（见 {@code HkDialogs}）必须在主线程弹框、后台线程 latch 等结果，
     * 取消路径（按钮/返回键/点框外/Activity 销毁）都要回取消默认值，不悬挂。
     */
    public interface DialogHost {
        boolean confirm(String title, String message);

        String select(String title, List<String> options);

        String input(String title, String hint, String preset);
    }

    /** 强制解释器模式的 ContextFactory：optimizationLevel 一律 -1。 */
    private static class HkContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            return cx;
        }
    }

    /**
     * fetch/batchFetch 共用的抓取选项。纯 Java 持有者，可跨线程（batchFetch 的
     * 工作线程只碰它，不碰 Rhino 对象；见 docs/miniapp-align-4-batch-fetch.md）。
     * body 只取线程安全的形态：String、表单 Map&lt;String,String&gt;（ArrayMap）、null。
     */
    private static final class FetchOpts {
        final Map<String, String> headers;
        final String method;
        final Object body;
        final long timeout;
        /** withHeaders 或 withStatusCode：返回 wrapFetch 包络 JSON，而不是纯体。 */
        final boolean envelope;
        /** redirect:false 时为 false（不跟随重定向）。 */
        final boolean followRedirects;
        /** onlyHeaders：只要状态码 + 头，不读体。 */
        final boolean onlyHeaders;

        FetchOpts(Map<String, String> headers, String method, Object body, long timeout,
                  boolean envelope, boolean followRedirects, boolean onlyHeaders) {
            this.headers = headers;
            this.method = method;
            this.body = body;
            this.timeout = timeout;
            this.envelope = envelope;
            this.followRedirects = followRedirects;
            this.onlyHeaders = onlyHeaders;
        }

        static FetchOpts defaults() {
            return new FetchOpts(new HashMap<>(), "GET", null, DEFAULT_TIMEOUT_MS, false, true, false);
        }
    }

    /** batchFetch 的一项：调用线程上从 JS 值抽出的纯 Java 持有者，可安全跨线程。 */
    private static final class BatchItem {
        final String url;
        final FetchOpts opts;

        BatchItem(String url, FetchOpts opts) {
            this.url = url;
            this.opts = opts;
        }
    }

    /** JS 通过 __hk 调到的 Java 桥。必须是 public static 类，否则 Rhino 反射调不到方法。 */
    public static class Bridge {
        private final HkEngine engine;

        public Bridge(HkEngine engine) {
            this.engine = engine;
        }

        public String fetch(String url) throws HkException {
            return fetch(url, null);
        }

        /**
         * 同步请求，返回响应体字符串；options 支持 headers/method/body/timeout，
         * 以及 withHeaders/withStatusCode（返回 wrapFetch 包络 JSON）、
         * redirect:false（不跟随重定向）、onlyHeaders（只要状态码 + 头，不读体）。
         * 官方 fetchSync 同款语义，见 docs/miniapp-align-4-batch-fetch.md §3.2。
         */
        public String fetch(String url, Object options) throws HkException {
            if (TextUtils.isEmpty(url)) return "";
            FetchOpts opts = options instanceof NativeObject
                    ? fetchOptsFromNativeObject((NativeObject) options) : FetchOpts.defaults();
            return engine.httpFetch(url, opts);
        }

        public String post(String url, Object options) throws HkException {
            if (options instanceof NativeObject) {
                ScriptableObject.putProperty((Scriptable) options, "method", "POST");
                return fetch(url, options);
            }
            return fetch(url, null);
        }

        /**
         * 批量抓取（对齐第 4 项，照官方 batchFetchImpl）：入参是
         * [{url, options}]（options 是嵌套对象）或纯 URL 字符串数组，也接受
         * JSON 字符串形式的数组；并发 min(n,16)，第二参 threadNum 可覆盖；
         * 整体超时 (n/16)*10+10 秒；单项失败记 "" 不抛，超时未完成位为 null；
         * 返回按下标对齐的 NativeArray。非数组入参返回空数组。
         * 入参在调用线程上抽成纯 Java 的 BatchItem，Rhino 对象不跨线程；
         * 工作线程只调 {@link HkEngine#httpFetch(String, FetchOpts)}（纯 Java，
         * 不碰 Rhino，不需进 Context）。见 docs/miniapp-align-4-batch-fetch.md。
         */
        public Object batchFetch(Object arg) {
            return batchFetch(arg, null);
        }

        public Object batchFetch(Object arg, Object threadNum) {
            List<BatchItem> items = extractBatchItems(arg);
            int size = items.size();
            Context cx = Context.getCurrentContext();
            if (size == 0) return emptyJsArray(cx);
            int poolSize = Math.min(size, 16);
            if (threadNum != null && !(threadNum instanceof Undefined)) {
                try {
                    poolSize = Math.max(1, Math.min(size, (int) toBatchThreadNum(threadNum)));
                } catch (Exception ignored) {
                    // 解析失败回落默认 min(size,16)，与官方一致。
                }
            }
            ExecutorService pool = Executors.newFixedThreadPool(poolSize);
            ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(size);
            try {
                for (int i = 0; i < size; i++) {
                    final int index = i;
                    final BatchItem item = items.get(i);
                    pool.execute(() -> {
                        try {
                            String text = item.url == null ? "" : engine.httpFetch(item.url, item.opts);
                            results.put(index, text == null ? "" : text);
                        } catch (Throwable t) {
                            // 官方同款：单项失败该位 ""，不抛。
                            results.put(index, "");
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                // 官方同款：(size/16)*10+10 秒（整除）；超时后未入位的槽组装时为 null。
                latch.await((size / 16) * 10 + 10, TimeUnit.SECONDS);
            } catch (Throwable t) {
                Log.w(TAG, "batchFetch failed: " + t.getMessage());
                return emptyJsArray(cx);
            } finally {
                pool.shutdownNow();
            }
            Object[] out = new Object[size];
            for (int i = 0; i < size; i++) out[i] = results.get(i);
            if (cx == null) return out;
            return cx.newArray(engine.scope, out);
        }

        private Object emptyJsArray(Context cx) {
            if (cx == null) return new Object[0];
            return cx.newArray(engine.scope, new Object[0]);
        }

        private static double toBatchThreadNum(Object v) {
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) return Double.parseDouble(((String) v).trim());
            throw new IllegalArgumentException("not a number: " + v);
        }

        /** 调用线程上把 batchFetch 入参抽成 BatchItem 列表；非法入参 → 空列表。 */
        private static List<BatchItem> extractBatchItems(Object arg) {
            List<BatchItem> items = new ArrayList<>();
            if (arg instanceof NativeArray) {
                NativeArray arr = (NativeArray) arg;
                for (int i = 0; i < arr.size(); i++) items.add(batchItemFromJs(arr.get(i, arr)));
            } else if (arg instanceof String) {
                // 官方同款：字符串入参按 JSON 数组解析，不是数组则空数组。
                try {
                    JsonElement parsed = JsonParser.parseString((String) arg);
                    if (parsed != null && parsed.isJsonArray()) {
                        for (JsonElement el : parsed.getAsJsonArray()) items.add(batchItemFromJson(el));
                    }
                } catch (Exception ignored) {
                }
            }
            return items;
        }

        private static BatchItem batchItemFromJs(Object item) {
            if (item instanceof String) {
                return new BatchItem((String) item, FetchOpts.defaults());
            }
            if (item instanceof NativeObject) {
                NativeObject obj = (NativeObject) item;
                Object u = ScriptableObject.getProperty(obj, "url");
                String url = (u == null || u instanceof Undefined) ? null : Context.toString(u);
                Object options = ScriptableObject.getProperty(obj, "options");
                FetchOpts opts = options instanceof NativeObject
                        ? fetchOptsFromNativeObject((NativeObject) options) : FetchOpts.defaults();
                return new BatchItem(url, opts);
            }
            return new BatchItem(null, FetchOpts.defaults());
        }

        private static BatchItem batchItemFromJson(JsonElement el) {
            if (el == null || el.isJsonNull()) return new BatchItem(null, FetchOpts.defaults());
            if (el.isJsonPrimitive()) {
                JsonPrimitive p = el.getAsJsonPrimitive();
                return new BatchItem(p.isString() ? p.getAsString() : p.toString(), FetchOpts.defaults());
            }
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                String url = obj.has("url") && !obj.get("url").isJsonNull()
                        ? jsonToString(obj.get("url")) : null;
                FetchOpts opts = obj.has("options") && obj.get("options").isJsonObject()
                        ? fetchOptsFromJson(obj.getAsJsonObject("options")) : FetchOpts.defaults();
                return new BatchItem(url, opts);
            }
            return new BatchItem(null, FetchOpts.defaults());
        }

        /** 从 JS options 对象抽取抓取选项（调用线程上读 NativeObject，不跨线程）。 */
        private static FetchOpts fetchOptsFromNativeObject(NativeObject opt) {
            Map<String, String> headers = new HashMap<>();
            String method = "GET";
            Object body = null;
            long timeout = DEFAULT_TIMEOUT_MS;
            boolean envelope = false;
            boolean followRedirects = true;
            boolean onlyHeaders = false;
            if (opt != null) {
                Object h = ScriptableObject.getProperty(opt, "headers");
                if (h instanceof NativeObject) {
                    NativeObject headersObj = (NativeObject) h;
                    for (Object id : headersObj.getIds()) {
                        String key = String.valueOf(id);
                        Object value = ScriptableObject.getProperty(headersObj, key);
                        if (value != null && !(value instanceof Undefined)) headers.put(key, Context.toString(value));
                    }
                }
                Object m = ScriptableObject.getProperty(opt, "method");
                if (m instanceof String && !((String) m).isEmpty()) method = ((String) m).toUpperCase(Locale.ROOT);
                body = ScriptableObject.getProperty(opt, "body");
                if (body instanceof Undefined) body = null;
                else if (body instanceof NativeObject) body = formParams((NativeObject) body);
                Object t = ScriptableObject.getProperty(opt, "timeout");
                if (t instanceof Number) timeout = ((Number) t).longValue();
                envelope = jsTruthy(ScriptableObject.getProperty(opt, "withHeaders"))
                        || jsTruthy(ScriptableObject.getProperty(opt, "withStatusCode"));
                Object r = ScriptableObject.getProperty(opt, "redirect");
                if (r instanceof Boolean) followRedirects = (Boolean) r;
                onlyHeaders = jsTruthy(ScriptableObject.getProperty(opt, "onlyHeaders"));
            }
            return new FetchOpts(headers, method, body, timeout, envelope, followRedirects, onlyHeaders);
        }

        /** 从 JSON options 对象抽取抓取选项（字符串入参路径，与上面同款语义）。 */
        private static FetchOpts fetchOptsFromJson(JsonObject opt) {
            Map<String, String> headers = new HashMap<>();
            String method = "GET";
            Object body = null;
            long timeout = DEFAULT_TIMEOUT_MS;
            boolean envelope = false;
            boolean followRedirects = true;
            boolean onlyHeaders = false;
            if (opt != null) {
                if (opt.has("headers") && opt.get("headers").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : opt.getAsJsonObject("headers").entrySet()) {
                        if (e.getValue() != null && !e.getValue().isJsonNull()) headers.put(e.getKey(), jsonToString(e.getValue()));
                    }
                }
                if (opt.has("method") && opt.get("method").isJsonPrimitive()) {
                    String m = opt.get("method").getAsString();
                    if (!m.isEmpty()) method = m.toUpperCase(Locale.ROOT);
                }
                if (opt.has("body") && !opt.get("body").isJsonNull()) {
                    JsonElement b = opt.get("body");
                    if (b.isJsonPrimitive()) body = b.getAsJsonPrimitive().isString() ? b.getAsString() : b.toString();
                    else if (b.isJsonObject()) {
                        ArrayMap<String, String> params = new ArrayMap<>();
                        for (Map.Entry<String, JsonElement> e : b.getAsJsonObject().entrySet()) {
                            if (e.getValue() != null && !e.getValue().isJsonNull()) params.put(e.getKey(), jsonToString(e.getValue()));
                        }
                        body = params;
                    } else body = b.toString();
                }
                if (opt.has("timeout") && opt.get("timeout").isJsonPrimitive()) {
                    try {
                        timeout = opt.get("timeout").getAsLong();
                    } catch (Exception ignored) {
                    }
                }
                envelope = jsonTruthy(opt.get("withHeaders")) || jsonTruthy(opt.get("withStatusCode"));
                if (opt.has("redirect") && opt.get("redirect").isJsonPrimitive()
                        && opt.get("redirect").getAsJsonPrimitive().isBoolean()) {
                    followRedirects = opt.get("redirect").getAsBoolean();
                }
                onlyHeaders = jsonTruthy(opt.get("onlyHeaders"));
            }
            return new FetchOpts(headers, method, body, timeout, envelope, followRedirects, onlyHeaders);
        }

        /** NativeObject 形式的表单 body 在调用线程转成 ArrayMap（线程安全形态）。 */
        private static ArrayMap<String, String> formParams(NativeObject obj) {
            ArrayMap<String, String> params = new ArrayMap<>();
            for (Object id : obj.getIds()) {
                String key = String.valueOf(id);
                Object value = ScriptableObject.getProperty(obj, key);
                if (value != null && !(value instanceof Undefined)) params.put(key, Context.toString(value));
            }
            return params;
        }

        private static boolean jsTruthy(Object v) {
            if (v == null || v instanceof Undefined) return false;
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).doubleValue() != 0.0d;
            if (v instanceof String) return "true".equalsIgnoreCase((String) v);
            return false;
        }

        private static boolean jsonTruthy(JsonElement v) {
            if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return false;
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0.0d;
            if (p.isString()) return "true".equalsIgnoreCase(p.getAsString());
            return false;
        }

        private static String jsonToString(JsonElement v) {
            if (v == null || v.isJsonNull()) return "";
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) return v.getAsString();
            return v.toString();
        }

        public String getResCode() {
            return engine.resCode == null ? "" : engine.resCode;
        }

        public void setResult(Object d) {
            engine.resultSet = true;
            engine.resultRaw = d;
            engine.result = engine.cardsFromJs(d);
        }

        public void setError(String msg) {
            Log.w(TAG, "rule error: " + msg);
        }

        public void log(Object x) {
            String text = x == null ? "null" : Context.toString(x);
            if (text.length() > 500) text = text.substring(0, 500) + "…";
            Log.i(TAG, text);
        }

        public void toast(String msg) {
            if (msg == null) return;
            if (engine.dialogHost != null) {
                // 有 UI 宿主（第 7 步之七）：真弹提示；调用方在后台线程，post 到主线程弹。
                App.post(() -> Notify.show(msg));
            } else {
                // 没有 UI 宿主时记一条日志即可（第 1 步的原行为）。
                Log.i(TAG, "rule toast: " + msg);
            }
        }

        /**
         * 海阔 refreshPage(scrollTop?)：置位 + 事件驱动当前页重求值（对齐第 3 项，
         * 照官方 lambda$registerApi$138：refreshRequested / refreshToTop 无条件置位，
         * 事件 200ms 节流；scrollTop 无参/非布尔参回顶，false 不回顶，见 refreshScrollTop）。
         * 事件开关关时（如点击懒规则求值，见 {@link HkEngine#setRefreshEventEnabled}）
         * 只置位不发事件，刷新由调用方经 wasRefreshRequested() 消费。
         */
        public void refreshPage() {
            requestRefresh(true);
        }

        public void refreshPage(Object scrollTop) {
            requestRefresh(scrollTop instanceof Boolean ? (Boolean) scrollTop : true);
        }

        private void requestRefresh(boolean toTop) {
            engine.refreshRequested = true;
            engine.refreshToTop = toTop;
            if (!engine.refreshEventEnabled) {
                Log.i(TAG, "rule refreshPage requested (event suppressed) toTop=" + toTop);
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastRefreshPageAt >= REFRESH_PAGE_THROTTLE_MS) {
                lastRefreshPageAt = now;
                HkRefreshPageEvent.post(toTop);
            }
            Log.i(TAG, "rule refreshPage requested toTop=" + toTop);
        }

        public String parseDom(String html, String sel) {
            if (html == null || sel == null) return "";
            return HkParser.parseDomSingle(html, sel, engine.pageUrl);
        }

        public String parseDomForHtml(String html, String sel) {
            if (html == null || sel == null) return "";
            return HkParser.parseDomForHtml(html, sel);
        }

        /** 返回 String[]，JS 侧当数组用（length/下标都正常）。 */
        public Object parseDomForArray(String html, String sel) {
            if (html == null || sel == null) return new String[0];
            return HkParser.parseDomForArray(html, sel, engine.pageUrl);
        }

        public String md5(String s) {
            if (s == null) return "";
            try {
                byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(32);
                for (byte b : digest) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        public String base64Encode(String s) {
            if (s == null) return "";
            return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }

        public String base64Decode(String s) {
            if (TextUtils.isEmpty(s)) return "";
            try {
                return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        public void putVar(String k, Object v) {
            if (k != null) engine.vars.put(k, v);
        }

        /**
         * putVar 对象形式（臻品fix 的 tab 懒加载用 putVar({key, value})；只写 vars，
         * 不触发刷新——tab 切换本身是第 7 步）。Rhino 按参数个数选重载：
         * 1 个对象参数走这里，2 参数走 putVar(String, Object)，互不干扰。
         */
        public void putVar(Scriptable obj) {
            if (obj == null) return;
            Object k = ScriptableObject.getProperty(obj, "key");
            Object v = ScriptableObject.getProperty(obj, "value");
            if (k == null || k instanceof Undefined) return;
            engine.vars.put(Context.toString(k), v);
        }

        public Object getVar(String k) {
            return getVar(k, null);
        }

        /**
         * 取变量，带默认值（第 4 步补：臻品fix 详情用 getVar(MY_URL, '0') 选线路，
         * 缺省要回 '0'；1 参数版缺省回 null，与原来一致）。
         */
        public Object getVar(String k, Object def) {
            if (def instanceof Undefined) def = null;
            Object v = k == null ? null : engine.vars.get(k);
            if (v == null || v instanceof Undefined) return def;
            return v;
        }

        public void clearVar() {
            engine.vars.clear();
        }

        public void putMyVar(String k, Object v) {
            if (k != null) engine.putMyVarValue(k, v);
        }

        public Object getMyVar(String k) {
            return getMyVar(k, null);
        }

        /** 与 getVar 同款：缺省回默认值（阅动漫用 getMyVar，第 7 步用得上，先对齐）。 */
        public Object getMyVar(String k, Object def) {
            if (def instanceof Undefined) def = null;
            Object v = k == null ? null : engine.myVars.get(k);
            if (v == null || v instanceof Undefined) return def;
            return v;
        }

        public void clearMyVar() {
            engine.myVars.clear();
        }

        /**
         * require()：hiker://page/... 走子页面展开（见 {@link HkSubpage}），
         * 库文件 URL 走 require.json 清单/HTTP；params 是 require 的第二个参数
         * （子页面的 $.importParam），不传时是 Undefined。
         */
        public Object require(String url, Object params) throws HkException {
            return engine.require(url, params);
        }

        /**
         * 海阔 rc(url, hours) = requireCache（对齐第 1 项）：小时数兑现。
         * hours 解析照官方 cacheHoursValue：Number → int，缺失/Undefined/
         * 非数字 → 24。-1 = 永久有效，0 = 永不命中缓存（见 {@link HkEngine#isCacheFresh}）。
         */
        public Object requireCache(String url, Object hours) throws HkException {
            int cacheHours = 24;
            if (hours instanceof Number) cacheHours = ((Number) hours).intValue();
            return engine.require(url, null, cacheHours);
        }

        /** 取某个 URL 的主页地址（协议 + host + 端口 + /），对应海阔 getHome()。 */
        public String getHome(String url) {
            return engine.homeOf(url);
        }

        /** 海阔 setPageTitle()：本引擎没有页面标题可改，记一条日志即可。 */
        public void setPageTitle(String title) {
            Log.i(TAG, "rule setPageTitle: " + title);
        }

        /** 海阔 readFile()：读规则私有数据文件，缺失返回 ""（不抛错）。 */
        public String readFile(String name) {
            return HkStore.readDataFile(engine.ruleTitle(), name);
        }

        /** 海阔 writeFile()：写规则私有数据文件，返回是否写成功。 */
        public boolean writeFile(String name, String content) {
            return HkStore.writeDataFile(engine.ruleTitle(), name, content);
        }

        /** 海阔 saveFile()：writeFile 的别名。 */
        public boolean saveFile(String name, String content) {
            return writeFile(name, content);
        }

        /** 海阔 fileExist()：规则私有数据文件是否存在。 */
        public boolean fileExist(String name) {
            if (TextUtils.isEmpty(name)) return false;
            return HkStore.dataFile(engine.ruleTitle(), name).exists();
        }

        /** 海阔 deleteFile()：删规则私有数据文件，返回是否删掉。 */
        public boolean deleteFile(String name) {
            return HkStore.deleteDataFile(engine.ruleTitle(), name);
        }

        /**
         * 海阔 getItem()：读规则持久 KV（data/&lt;标题&gt;/kv.json），缺失回默认值。
         * 默认值照官方 getString：字符串才有效，其余（null/Undefined/数字/对象）
         * 一律按 null 计——缺失时返回 null 而非 ""（对齐第 5 项）。
         */
        public String getItem(String key, Object def) {
            return engine.getItem(key, toItemStringOrNull(def));
        }

        public String getItem(String key) {
            return engine.getItem(key, null);
        }

        /** 三参版 getItem(key, def, ruleTitle)：跨规则读（官方公开签名）。 */
        public String getItem(String key, Object def, Object ruleTitle) {
            return engine.getItem(key, toItemStringOrNull(def), toTitleString(ruleTitle));
        }

        /**
         * 海阔 setItem()：写规则持久 KV（data/&lt;标题&gt;/kv.json），立即落盘。
         * 值照官方 mustBeString：只有字符串才存；null/Undefined/数字/布尔/对象
         * 不存（官方存 null、落盘时 Gson 丢 null，键等于不存在；这里直接删键，
         * 可观测结果相同）。
         */
        public void setItem(String key, Object value) {
            engine.setItem(key, toItemStringOrNull(value));
        }

        /** 三参版 setItem(key, value, ruleTitle)：跨规则写（官方公开签名）。 */
        public void setItem(String key, Object value, Object ruleTitle) {
            engine.setItem(key, toItemStringOrNull(value), toTitleString(ruleTitle));
        }

        /**
         * 海阔 getPublicItem()：读全规则共享的公共 KV（rules/_item00.json），
         * 缺失回默认值（非字符串默认值按 null 计，与 getItem 同款）。
         */
        public String getPublicItem(String key, Object def) {
            return engine.getPublicItem(key, toItemStringOrNull(def));
        }

        /** 海阔 setPublicItem()：写公共 KV，立即落盘；非字符串值删键（同 setItem）。 */
        public void setPublicItem(String key, Object value) {
            engine.setPublicItem(key, toItemStringOrNull(value));
        }

        /** 海阔 clearPublicItem()：删公共 KV 里的键，立即落盘。 */
        public void clearPublicItem(String key) {
            engine.clearPublicItem(key);
        }

        /** getItem/setItem 的字符串参数归一化：CharSequence → 字符串，其余 → null。 */
        private static String toItemStringOrNull(Object value) {
            if (value instanceof CharSequence) return value.toString();
            return null;
        }

        /** 三参版的规则标题归一化：非字符串或空串 → null（= 当前规则）。 */
        private static String toTitleString(Object title) {
            if (title instanceof CharSequence) {
                String s = title.toString();
                return s.isEmpty() ? null : s;
            }
            return null;
        }

        /** clearVar(name)：只清一个页面变量（无参版清全部，见 {@link #clearVar()}）。 */
        public void clearVar(String key) {
            if (key != null) engine.vars.remove(key);
        }

        /**
         * 海阔 confirm() 对话框（第 7 步之七）：经 DialogHost 弹确认框，返回用户是否确认；
         * 用户取消回 false。参数是字符串（当 message）或对象（取 title + message|msg|content|text）。
         * 没挂 host 时抛明确错（第 1–6 步的原行为）。
         */
        public Object confirm(Object options) throws HkException {
            DialogHost host = engine.dialogHost;
            if (host == null) throw new HkException("confirm 需要页面上下文（未接入弹框）");
            String title = "请确认";
            String message = "";
            if (options instanceof String) {
                message = (String) options;
            } else if (options instanceof NativeObject) {
                NativeObject obj = (NativeObject) options;
                title = optString(obj, "请确认", "title");
                message = optString(obj, "", "message", "msg", "content", "text");
            } else if (options != null && !(options instanceof Undefined)) {
                message = Context.toString(options);
            }
            return host.confirm(title, message);
        }

        /**
         * 海阔 select() 对话框（第 7 步之七）：经 DialogHost 弹选项列表，返回选中项文本；
         * 用户取消回 null（JS 侧得 null，不断链）。参数是数组（当选项列表）或对象
         * （取 title + options|list|items 数组）。没挂 host 时抛明确错。
         */
        public Object select(Object options) throws HkException {
            DialogHost host = engine.dialogHost;
            if (host == null) throw new HkException("select 需要页面上下文（未接入弹框）");
            String title = "请选择";
            List<String> items = new ArrayList<>();
            if (options instanceof NativeArray) {
                items.addAll(stringList((NativeArray) options));
            } else if (options instanceof NativeObject) {
                NativeObject obj = (NativeObject) options;
                title = optString(obj, "请选择", "title");
                for (String key : new String[]{"options", "list", "items"}) {
                    Object value = ScriptableObject.getProperty(obj, key);
                    if (value instanceof NativeArray) {
                        items.addAll(stringList((NativeArray) value));
                        break;
                    }
                }
            } else if (options instanceof String) {
                title = (String) options;
            }
            if (items.isEmpty()) throw new HkException("select 没有可选项");
            return host.select(title, items);
        }

        /** 取 NativeObject 里第一个非空字符串属性（没有返回 fallback）。 */
        private String optString(NativeObject obj, String fallback, String... keys) {
            for (String key : keys) {
                Object value = ScriptableObject.getProperty(obj, key);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    return ((String) value).trim();
                }
            }
            return fallback;
        }

        /** NativeArray 转字符串列表（null/Undefined 跳过）。 */
        private List<String> stringList(NativeArray array) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < array.getLength(); i++) {
                Object item = array.get(i, array);
                if (item == null || item instanceof Undefined) continue;
                out.add(Context.toString(item));
            }
            return out;
        }

        public String getParam(String key) {
            return getParam(key, "");
        }

        public String getParam(String key, String def) {
            if (key == null) return def == null ? "" : def;
            String value = engine.params.get(key);
            return value == null ? (def == null ? "" : def) : value;
        }

        public String getRuleJson() {
            return engine.ruleJson == null ? "{}" : engine.ruleJson;
        }

        public String getParamsJson() {
            if (engine.params.isEmpty()) return "{}";
            // 用 Gson 拼（不要手拼：参数值里可能有引号/反斜杠，手拼会拼出非法 JSON，
            // BOOTSTRAP 里的 JSON.parse 就会炸掉整个 prepare）。
            return App.gson().toJson(engine.params);
        }

        public String getPageUrl() {
            return engine.pageUrl == null ? "" : engine.pageUrl;
        }

        public String getHomeUrl() {
            return engine.homeUrl == null ? "" : engine.homeUrl;
        }

        public int getPage() {
            return engine.page;
        }

        public String getPageType() {
            return engine.pageType == null ? "" : engine.pageType;
        }

        public String getMobileUa() {
            return MOBILE_UA;
        }

        public String getPcUa() {
            return PC_UA;
        }
    }

    private final HkContextFactory factory = new HkContextFactory();
    private final Bridge bridge = new Bridge(this);

    /** 弹框宿主（第 7 步之七）：confirm/select/input 经它问用户；没挂时抛明确错。 */
    private DialogHost dialogHost;
    /** 本次执行里规则 JS 是否调过 refreshPage()（点击执行路径读它，决定是否重跑当前页；页面求值路径则发事件驱动重载，见 {@link HkRefreshPageEvent}）。 */
    private boolean refreshRequested;
    /** 本次执行里 refreshPage() 的回顶意图（无参/非布尔参为 true，false 才不回顶）。 */
    private boolean refreshToTop;
    /** 是否发 refreshPage 事件（默认开；点击懒规则/播放/最新章节求值关，刷新由调用方消费，见 {@link HkExecutor}）。 */
    private boolean refreshEventEnabled = true;
    /** 进程内上次发 refreshPage 事件的时间（静态：引擎每次求值新建，实例位拦不住 200ms 节流）。 */
    private static volatile long lastRefreshPageAt;
    /** refreshPage 事件节流阈值（官方 lambda$registerApi$138 同款 200ms）。 */
    private static final long REFRESH_PAGE_THROTTLE_MS = 200;

    /** 设是否发 refreshPage 事件（关时 Bridge 只置位不发事件，调用方读 wasRefreshRequested() 消费）。 */
    public void setRefreshEventEnabled(boolean enabled) {
        this.refreshEventEnabled = enabled;
    }

    /** 挂弹框宿主（调用方在 prepare 之前调；子引擎从父引擎抄，见 HkSubpage.require）。 */
    public void setDialogHost(DialogHost host) {
        this.dialogHost = host;
    }

    /** 取弹框宿主（子引擎抄 host 时用；没挂返回 null）。 */
    public DialogHost getDialogHost() {
        return dialogHost;
    }

    /** 本次执行里规则 JS 是否调过 refreshPage()。 */
    public boolean wasRefreshRequested() {
        return refreshRequested;
    }

    /** 当前 vars 的一份拷贝（点击执行后写回调用方，供下次执行预填）。 */
    public Map<String, Object> getVarsSnapshot() {
        return new HashMap<>(vars);
    }

    private Scriptable scope;
    private HkRule rule;
    private String ruleJson;
    private String pageUrl = "";
    private String homeUrl = "";
    private int page = 1;
    private String pageType = "home";
    private String resCode = "";

    /** 最近一次真实 HTTP 抓取的状态码（require 报错用；-1 = 未抓到/未知）。 */
    private int lastHttpCode = -1;
    private List<HkCard> result;
    private boolean resultSet;
    /**
     * setResult 交的原始值（第 7 步之九）：cardsFromJs 会把纯字符串结果
     * （如 last_chapter_rule 的 setResult("更新至第10集")）丢掉，原始值留
     * 在这里供 evalTextRule 取字符串结果；prepare 时随 result 一起重置。
     */
    private Object resultRaw;
    private final Map<String, Object> vars = new HashMap<>();
    private final Map<String, Object> myVars = new HashMap<>();
    private final Map<String, String> params = new HashMap<>();
    /** 同一引擎里已 require 过的库 URL（防同一 scope 里 let 重声明）。 */
    private final Set<String> requiredLibs = new HashSet<>();
    /** getItem/setItem 的内存缓存（kv.json 的内容，prepare 时加载）。 */
    private final Map<String, String> items = new HashMap<>();
    private boolean itemsLoaded;
    /** getPublicItem/setPublicItem/clearPublicItem 的内存缓存（rules/_item00.json，首次访问现读）。 */
    private final Map<String, String> publicItems = new HashMap<>();
    private boolean publicItemsLoaded;

    /**
     * 准备一次页面执行：建 scope、注入 __hk 桥与 BOOTSTRAP、设置域内变量。
     * 必须在 eval* 之前调；resCode 是本页已抓到的原文（getResCode() 返回它）。
     * myVars 不再清空，改为从 data/&lt;标题&gt;/myvar.json 加载（持久变量跨页可见）；
     * items 同样从 kv.json 加载。
     */
    public void prepare(HkRule rule, String pageUrl, int page, String pageType, String resCode) throws HkException {
        prepare(rule, pageUrl, page, pageType, resCode, null);
    }

    /**
     * prepare 的重载（第 7 步之七）：initialVars 非空时预填页面变量
     * （点击执行 @lazyRule= 后重跑详情页时，把执行里 putVar 的值带过去，
     * 臻品fix 的 tab/排序切换靠它；null 时 vars 从空开始，与原来一致）。
     */
    public void prepare(HkRule rule, String pageUrl, int page, String pageType, String resCode,
                        Map<String, Object> initialVars) throws HkException {
        this.rule = rule;
        this.ruleJson = rule == null ? "{}" : rule.toJson();
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.homeUrl = homeOf(this.pageUrl);
        this.page = page;
        this.pageType = TextUtils.isEmpty(pageType) ? "home" : pageType;
        this.resCode = resCode == null ? "" : resCode;
        this.result = null;
        this.resultSet = false;
        this.resultRaw = null;
        this.refreshRequested = false;
        this.refreshToTop = false;
        this.requiredLibs.clear();
        this.vars.clear();
        if (initialVars != null) this.vars.putAll(initialVars);
        parseParams(this.pageUrl);
        try (CxHandle handle = enterCx()) {
            Context cx = handle.cx;
            scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "__hk", Context.javaToJS(bridge, scope));
            try {
                cx.evaluateString(scope, BOOTSTRAP, "hk-bootstrap", 1, null);
            } catch (RhinoException e) {
                throw new HkException("JS 环境初始化失败: " + e.getMessage(), e);
            }
            loadMyVars(cx);
            loadItems();
        }
    }

    /**
     * 进 Context 的句柄：require 在 JS 回调里建子引擎时，当前线程已有 Context
     * （调用方 JS 正在跑），复用它而不是再进一个——Rhino 嵌套 enter() 的行为
     * 本地无法实测，不赌它；没有 Context 时才新进一个。配套 close() 只在新进
     * 时才 exit，复用时不 exit（调用方自己负责）。
     */
    private final class CxHandle implements AutoCloseable {
        final Context cx;
        private final boolean entered;

        CxHandle(Context cx, boolean entered) {
            this.cx = cx;
            this.entered = entered;
        }

        @Override
        public void close() {
            if (entered) Context.exit();
        }
    }

    private CxHandle enterCx() {
        Context current = Context.getCurrentContext();
        if (current != null) return new CxHandle(current, false);
        return new CxHandle(factory.enterContext(), true);
    }

    /** 当前规则标题（持久变量/文件 API 的分区键）；无规则返回 ""。 */
    String ruleTitle() {
        return rule == null ? "" : rule.getTitle();
    }

    /**
     * 从 data/&lt;标题&gt;/myvar.json 加载持久变量进内存（prepare 时调；require 返回
     * 后重载也调）。字符串/数字/布尔直接进 map；对象/数组在 scope 里 JSON.parse
     * 还原成真 JS 值。文件缺失或解析失败时从空开始，不抛错。
     */
    private void loadMyVars(Context cx) {
        myVars.clear();
        String json = HkStore.readMyVars(ruleTitle());
        if (TextUtils.isEmpty(json)) return;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive()) {
                    JsonPrimitive p = v.getAsJsonPrimitive();
                    if (p.isString()) myVars.put(e.getKey(), p.getAsString());
                    else if (p.isNumber()) myVars.put(e.getKey(), p.getAsDouble());
                    else if (p.isBoolean()) myVars.put(e.getKey(), p.getAsBoolean());
                } else if (v.isJsonObject() || v.isJsonArray()) {
                    try {
                        Object restored = cx.evaluateString(scope,
                                "JSON.parse(" + App.gson().toJson(v.toString()) + ")", "hk-myvar", 1, null);
                        if (restored != null && !(restored instanceof Undefined)) myVars.put(e.getKey(), restored);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load myvars failed, starting empty", e);
        }
    }

    /** require 返回后重载一次持久变量（子引擎可能 putMyVar 写盘）。 */
    void reloadMyVars() {
        if (scope == null) return;
        try (CxHandle handle = enterCx()) {
            loadMyVars(handle.cx);
        }
    }

    /** putMyVar：写内存 + 直写回盘（子引擎/下次执行能读到）。 */
    void putMyVarValue(String key, Object value) {
        if (key == null) return;
        myVars.put(key, value);
        saveMyVars();
    }

    /** 把内存里的 myVars 整文件写回 data/&lt;标题&gt;/myvar.json。 */
    private void saveMyVars() {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Object> e : myVars.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Undefined) continue;
            if (v instanceof String) obj.addProperty(e.getKey(), (String) v);
            else if (v instanceof Number) obj.addProperty(e.getKey(), (Number) v);
            else if (v instanceof Boolean) obj.addProperty(e.getKey(), (Boolean) v);
            else {
                String json = stringifyInScope(v);
                if (json != null) {
                    try {
                        obj.add(e.getKey(), JsonParser.parseString(json));
                        continue;
                    } catch (Exception ignored) {
                    }
                }
                obj.addProperty(e.getKey(), Context.toString(v));
            }
        }
        HkStore.saveMyVars(ruleTitle(), obj.toString());
    }

    /** 在当前 Context 里把一个 JS 值 JSON.stringify（供 saveMyVars 用；不在 JS 回调里时返回 null）。 */
    private String stringifyInScope(Object value) {
        Context cx = Context.getCurrentContext();
        if (cx == null || scope == null) return null;
        try {
            ScriptableObject.putProperty(scope, "__hk_tmp", value);
            Object json = cx.evaluateString(scope, "JSON.stringify(__hk_tmp)", "hk-stringify", 1, null);
            return json instanceof String ? (String) json : null;
        } catch (Exception e) {
            return null;
        } finally {
            ScriptableObject.deleteProperty(scope, "__hk_tmp");
        }
    }

    /** 解析 kv.json 文本成字符串 map（解析失败/非对象回空 map，不抛错）。 */
    private static Map<String, String> parseItems(String json) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(json)) return map;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return map;
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            Log.w(TAG, "load items failed, starting empty", e);
        }
        return map;
    }

    /** 从 data/&lt;标题&gt;/kv.json 加载 getItem/setItem 的缓存（prepare 时调）。 */
    private void loadItems() {
        items.clear();
        itemsLoaded = true;
        items.putAll(parseItems(HkStore.readItems(ruleTitle())));
    }

    /** getItem：读规则持久 KV，缺失回默认值（可为 null，官方 getString 语义）。 */
    String getItem(String key, String def) {
        if (key == null || key.isEmpty()) return def;
        if (!itemsLoaded) loadItems();
        String value = items.get(key);
        return value == null ? def : value;
    }

    /**
     * 三参版 getItem（官方公开签名）：title 为空或等于当前规则走本规则缓存；
     * 规则不存在回 ""（不是 def，官方 getItemNow 语义）；存在则现读该规则 kv.json。
     */
    String getItem(String key, String def, String ruleTitle) {
        if (key == null || key.isEmpty()) return def;
        if (ruleTitle == null || ruleTitle.isEmpty() || ruleTitle.equals(ruleTitle())) {
            return getItem(key, def);
        }
        if (!HkStore.exists(ruleTitle)) return "";
        String value = parseItems(HkStore.readItems(ruleTitle)).get(key);
        return value == null ? def : value;
    }

    /** setItem：写规则持久 KV，立即落盘；value 为 null 时删键（官方存 null 语义）。 */
    void setItem(String key, String value) {
        setItem(key, value, null);
    }

    /**
     * 三参版 setItem（官方公开签名）：空 key 空操作；title 为空或等于当前规则
     * 写本规则缓存，否则规则不存在空操作、存在则现读现改现写该规则 kv.json。
     */
    void setItem(String key, String value, String ruleTitle) {
        if (key == null || key.isEmpty()) return;
        if (ruleTitle == null || ruleTitle.isEmpty() || ruleTitle.equals(ruleTitle())) {
            if (!itemsLoaded) loadItems();
            if (value == null) items.remove(key);
            else items.put(key, value);
            String json = App.gson().toJson(items);
            checkItemSize(json, key, value);
            HkStore.saveItems(ruleTitle(), json);
            return;
        }
        if (!HkStore.exists(ruleTitle)) return;
        Map<String, String> other = parseItems(HkStore.readItems(ruleTitle));
        if (value == null) other.remove(key);
        else other.put(key, value);
        String json = App.gson().toJson(other);
        checkItemSize(json, key, value);
        HkStore.saveItems(ruleTitle, json);
    }

    /** 官方 checkSetItemSize：只记日志，不拦截（整表超 1MB / 单值超 512KB）。 */
    private static void checkItemSize(String json, String key, String value) {
        try {
            if (value != null && value.getBytes(StandardCharsets.UTF_8).length > 524288) {
                Log.d(TAG, "setItem 内容过大（512K）: " + key);
            }
            if (json != null && json.getBytes(StandardCharsets.UTF_8).length > 1048576) {
                Log.d(TAG, "setItem 私有存储内容过大（1MB）");
            }
        } catch (Exception ignored) {
        }
    }

    private void loadPublicItems() {
        publicItems.clear();
        publicItemsLoaded = true;
        publicItems.putAll(parseItems(HkStore.readPublicItems()));
    }

    /** getPublicItem：读全规则共享的公共 KV，缺失回默认值（可为 null）。 */
    String getPublicItem(String key, String def) {
        if (key == null || key.isEmpty()) return def;
        if (!publicItemsLoaded) loadPublicItems();
        String value = publicItems.get(key);
        return value == null ? def : value;
    }

    /** setPublicItem：写公共 KV，立即落盘；value 为 null 时删键。 */
    void setPublicItem(String key, String value) {
        if (key == null || key.isEmpty()) return;
        if (!publicItemsLoaded) loadPublicItems();
        if (value == null) publicItems.remove(key);
        else publicItems.put(key, value);
        HkStore.savePublicItems(App.gson().toJson(publicItems));
    }

    /** clearPublicItem：删公共 KV 里的键，立即落盘。 */
    void clearPublicItem(String key) {
        if (key == null || key.isEmpty()) return;
        if (!publicItemsLoaded) loadPublicItems();
        publicItems.remove(key);
        HkStore.savePublicItems(App.gson().toJson(publicItems));
    }

    /** 执行规则的 preRule（有才跑，与 find_rule 共用 scope），失败抛异常。 */
    public void evalPreRule() throws HkException {
        if (rule == null || TextUtils.isEmpty(rule.getPreRule())) return;
        eval(rule.getPreRule(), "preRule");
    }

    /** JS 是否调过 setResult/setHomeResult/setSearchResult（命中诊断用）。 */
    public boolean wasResultSet() {
        return resultSet;
    }

    /**
     * 执行整段 js: find_rule，返回卡片列表。优先取 JS 里调 setResult 交的结果；
     * 没调 setResult 时，若 JS 直接返回了数组就转换它；都没有返回空列表。
     */
    public List<HkCard> evalFindRule(String findRule) throws HkException {
        String js = HkParser.jsBody(findRule);
        Object returned = eval(js, "find_rule");
        if (result != null) return result;
        List<HkCard> fromReturn = cardsFromJs(returned);
        return fromReturn == null ? new ArrayList<>() : fromReturn;
    }

    /**
     * 执行一段返回字符串的 JS 规则（第 7 步之九，last_chapter_rule 用）。
     * 优先取 JS 调 setResult 交的原值（字符串直接取；字符串数组取首个非空元素；
     * 其它类型回落到卡片首标题）；没调 setResult 时取 JS 直接返回值（同款转换）；
     * 都没有返回 ""。调用方须先 prepare + evalPreRule。
     */
    public String evalTextRule(String rule) throws HkException {
        String js = HkParser.jsBody(rule);
        Object returned = eval(js, "text_rule");
        if (resultSet) {
            String fromRaw = rawToString(resultRaw);
            if (!TextUtils.isEmpty(fromRaw)) return fromRaw;
            if (result != null && !result.isEmpty()) {
                String title = result.get(0).getTitle();
                if (!TextUtils.isEmpty(title)) return title;
            }
            return "";
        }
        String fromReturn = rawToString(returned);
        if (!TextUtils.isEmpty(fromReturn)) return fromReturn;
        List<HkCard> fromCards = cardsFromJs(returned);
        if (fromCards != null && !fromCards.isEmpty()) {
            String title = fromCards.get(0).getTitle();
            if (!TextUtils.isEmpty(title)) return title;
        }
        return "";
    }

    /** setResult 原值 / JS 返回值转字符串：字符串本身、数字/布尔、字符串数组首元素；其余回 ""。 */
    private static String rawToString(Object value) {
        if (value == null || value instanceof Undefined) return "";
        if (value instanceof String) return ((String) value).trim();
        if (value instanceof Number || value instanceof Boolean) return Context.toString(value);
        if (value instanceof NativeArray) {
            NativeArray array = (NativeArray) value;
            for (int i = 0; i < array.getLength(); i++) {
                Object item = array.get(i);
                if (item instanceof String && !TextUtils.isEmpty(((String) item).trim())) {
                    return ((String) item).trim();
                }
            }
        }
        return "";
    }

    /**
     * 求一段 .js: 后处理表达式的值，input 是待处理的值（进 scope 当全局变量）。
     * 返回字符串；JS 返回 undefined/null 时返回 ""。
     */
    public String evalExpression(String js, String input) throws HkException {
        if (TextUtils.isEmpty(js)) return input == null ? "" : input;
        if (scope == null) throw new HkException("引擎未 prepare");
        Context cx = factory.enterContext();
        try {
            ScriptableObject.putProperty(scope, "input", input == null ? "" : input);
            Object value = cx.evaluateString(scope, js, "hk-js", 1, null);
            if (value == null || value instanceof Undefined) return "";
            return Context.toString(value);
        } catch (RhinoException e) {
            throw new HkException(".js: 执行失败: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    /** 在当前 scope 里执行一段 JS，返回求值结果（调用方决定怎么解读）。 */
    public Object eval(String js, String sourceName) throws HkException {
        if (scope == null) throw new HkException("引擎未 prepare");
        try (CxHandle handle = enterCx()) {
            try {
                return handle.cx.evaluateString(scope, js, sourceName, 1, null);
            } catch (RhinoException e) {
                throw new HkException("JS 执行失败: " + e.getMessage(), e);
            }
        }
    }

    /** 读当前 scope 里 $.exports（页面规则用它交导出值；未设置返回 Undefined）。 */
    public Object getExports() {
        if (scope == null) return Undefined.instance;
        try (CxHandle handle = enterCx()) {
            Scriptable dollar = dollarObject();
            if (dollar == null) return Undefined.instance;
            Object value = ScriptableObject.getProperty(dollar, "exports");
            return value == null ? Undefined.instance : value;
        }
    }

    /** 把 $.importParam 设为 require 的第二个参数（任意 JS 值；Undefined 时不改）。 */
    public void setImportParam(Object value) {
        if (scope == null || value == null || value instanceof Undefined) return;
        try (CxHandle handle = enterCx()) {
            Scriptable dollar = dollarObject();
            if (dollar != null) ScriptableObject.putProperty(dollar, "importParam", value);
        }
    }

    /** 把 $.importParam 设为给定的 query 参数对象（子页面展开时用）。 */
    public void setImportParams(Map<String, String> params) {
        if (scope == null) return;
        try (CxHandle handle = enterCx()) {
            Scriptable dollar = dollarObject();
            if (dollar == null) return;
            NativeObject obj = new NativeObject();
            if (params != null) {
                for (Map.Entry<String, String> e : params.entrySet()) {
                    ScriptableObject.putProperty(obj, e.getKey(), e.getValue());
                }
            }
            ScriptableObject.putProperty(dollar, "importParam", obj);
        }
    }

    /** 取 scope 里的 $ 对象（函数）；没有返回 null。 */
    private Scriptable dollarObject() {
        if (scope == null) return null;
        Object dollar = ScriptableObject.getProperty(scope, "$");
        return dollar instanceof Scriptable ? (Scriptable) dollar : null;
    }

    /** JS 调过 setResult 后交的卡片列表（require 取子引擎结果时用）；没调过返回 null。 */
    List<HkCard> getResultCards() {
        return result;
    }

    /**
     * 把卡片列表转回调用方 scope 里的 JS 数组（require 返回子引擎的 setResult
     * 结果时用，供调用方 .filter/.map；空列表返回 []）。
     */
    public Object jsValueFromCards(List<HkCard> cards) throws HkException {
        if (scope == null) throw new HkException("引擎未 prepare");
        if (cards == null || cards.isEmpty()) return eval("[]", "hk-cards");
        List<Map<String, Object>> plain = new ArrayList<>();
        for (HkCard card : cards) {
            Map<String, Object> m = new HashMap<>();
            m.put("title", card.getTitle());
            m.put("url", card.getUrl());
            m.put("img", card.getPic());
            m.put("pic", card.getPic());
            m.put("desc", card.getDesc());
            m.put("col_type", card.getColType());
            if (!TextUtils.isEmpty(card.getExtra())) {
                try {
                    m.put("extra", JsonParser.parseString(card.getExtra()));
                } catch (Exception ignored) {
                    m.put("extra", card.getExtra());
                }
            }
            plain.add(m);
        }
        String json = App.gson().toJson(plain);
        return eval("JSON.parse(" + App.gson().toJson(json) + ")", "hk-cards");
    }

    /**
     * JS 的 require()：hiker://page/... 走子页面（见 {@link HkSubpage}）；
     * 库文件 URL 先查 require.json 清单（plugins/hk/libs/），命中读本地文件，
     * 否则 http(s) 抓下来；同一个库 URL 在同一引擎里只 eval 一次（防 let 重声明）；
     * 其余地址抛明确错。库文件 eval 后若设了 $.exports，返回它，否则返回 eval 值。
     * plain require 的缓存小时数是 -1（永久有效），与官方 require0 调用一致；
     * rc(url, hours) 走 {@link #require(String, Object, int)}。
     */
    Object require(String url, Object params) throws HkException {
        return require(url, params, -1);
    }

    /**
     * 带缓存小时数的 require（对齐第 1 项，照官方 require0 的有效期判定；
     * 对齐第 2 项，坏内容回退旧缓存）：
     * http(s) 库先查 `plugins/hk/libs/&lt;md5(url)&gt;.js` 缓存，命中且在有效期内
     * 直接用，不发网络；未命中时抓下来，好内容（2xx + isJsCode）写缓存再 eval，
     * 坏内容（空、非 2xx、HTML 拦截页、isJsCode 失败）静默回退磁盘上已有的旧
     * 缓存（不论是否过期），无缓存才抛明确错。
     * 子页面分支与 require.json 清单分支在缓存检查之前，不变。
     */
    Object require(String url, Object params, int cacheHours) throws HkException {
        if (TextUtils.isEmpty(url)) throw new HkException("require 地址为空");
        String u = url.trim();
        if (HkSubpage.isSubpageUrl(u)) {
            Object result = HkSubpage.require(this, rule, u, params);
            reloadMyVars();
            return result;
        }
        if (requiredLibs.contains(u)) return Undefined.instance;
        File lib = HkStore.resolveLibFile(ruleTitle(), u);
        String code;
        if (lib != null) {
            code = HkStore.readTextFile(lib);
            if (code == null) throw new HkException("require 库文件读取失败：" + lib.getName());
        } else if (u.startsWith("http://") || u.startsWith("https://")) {
            String cachedCode = null;
            File cached = HkStore.rcCacheFile(u);
            if (cached != null && cached.exists() && isCacheFresh(cached, cacheHours)) {
                cachedCode = HkStore.readTextFile(cached);
            }
            if (!TextUtils.isEmpty(cachedCode)) {
                code = cachedCode;
            } else {
                code = httpFetch(u, null, "GET", null, DEFAULT_TIMEOUT_MS);
                if (!isGoodLib(code)) {
                    // 取到坏内容（空、非 2xx、HTML 拦截页、isJsCode 失败）：静默回退
                    // 磁盘上已有的旧缓存（官方 require0 :24458-24461 同款行为），不论
                    // 该缓存是否过期；无缓存才报清楚错（902c1e8c 的报错原样保留）。
                    String fallback = readStaleLibCache(u);
                    if (!TextUtils.isEmpty(fallback)) {
                        Log.i(TAG, "require fallback to cached lib (bad fetch, HTTP " + lastHttpCode + "): " + u);
                        code = fallback;
                    } else if (TextUtils.isEmpty(code)) {
                        throw new HkException(lastHttpCode >= 0
                                ? "require 取数失败（HTTP " + lastHttpCode + "，空内容）：" + u
                                : "require 取数失败（空内容）：" + u);
                    } else {
                        throw new HkException(lastHttpCode >= 0
                                ? "require 取到 HTTP " + lastHttpCode + "（不是 JS 脚本，可能是拦截/错误页）：" + u
                                : "require 取到的不是 JS 脚本（可能是拦截/错误页）：" + u);
                    }
                } else {
                    HkStore.saveRcCache(u, code);
                }
            }
        } else {
            throw new HkException("require 不支持的地址：" + u + "（只支持 hiker://page/... 与库文件 URL）");
        }
        requiredLibs.add(u);
        clearExports();
        Object result = eval(code, "require:" + u);
        Object exports = getExports();
        if (exports != null && !(exports instanceof Undefined)) return exports;
        return result;
    }

    /**
     * rc/require 缓存是否有效（对齐第 1 项，照官方 require0 的判定式，
     * `HkJsRuntime.java:24457`：版本检查无版本信息时按通过计）：
     * hours &lt; 0（plain require 的 -1）→ 文件存在即永久有效；
     * hours == 0 → 永不命中；hours &gt; 0 → `age &lt;= hours * 3600000`。
     * 用文件 lastModified 计 age，与官方一致（写缓存时即更新 mtime）。
     */
    static boolean isCacheFresh(File file, int hours) {
        if (file == null) return false;
        if (hours < 0) return true;
        if (hours == 0) return false;
        return System.currentTimeMillis() - file.lastModified() <= (long) hours * 3600000L;
    }

    /**
     * 是否 JS 代码（对齐第 2 项，逐字照官方 `HkJsRuntime.java:4174` isJsCode）：
     * 空 → 否；trim 后以 `<!DOCTYPE` / `<html` / `<?xml` 开头 → 否；
     * 以 `</html>` / `</rss>` 结尾 → 否；含 `var ` / `let ` / `function` /
     * `eval(` / `call(` / `eval (` / `call (` / ` => ` / `)=>` 任一 → 是；
     * 官方还查 methodList（已注册 API 方法名表），我们没有等价物，该分支略过——
     * 真实库总含上述标记之一。
     */
    static boolean isJsCode(String code) {
        if (TextUtils.isEmpty(code)) return false;
        String t = code.trim();
        String[] badStarts = {"<!DOCTYPE", "<html", "<?xml"};
        for (String s : badStarts) {
            if (t.startsWith(s)) return false;
        }
        String[] badEnds = {"</html>", "</rss>"};
        for (String s : badEnds) {
            if (t.endsWith(s)) return false;
        }
        String[] markers = {"var ", "let ", "function", "eval(", "call(", "eval (", "call (", " => ", ")=>"};
        for (String m : markers) {
            if (t.contains(m)) return true;
        }
        return false;
    }

    /**
     * 新取到的库内容是否可用（对齐第 2 项，照官方 require0 + requestForCode + isJsCode）：
     * 空 → 坏；HTTP 状态非 2xx → 坏（官方 requestForCode :24389-24395 只认 2xx，
     * 非 2xx 直接回 null）；isJsCode 失败 → 坏；另保留 f0c10fb7 的小写 doctype 兜底
     * （官方 isJsCode 只判大写 `<!DOCTYPE`，小写 doctype 且结尾又不是 `</html>`/`</rss>`
     * 的页面会漏进 marker 检查——拦截页内联脚本里常有 `function`；完整 HTML 页官方
     * 靠 `</html>` 后缀已能拦住；JS 库不会以 `<!doctype`/`<html` 开头，多这一层
     * 不会误伤真库）。
     */
    private boolean isGoodLib(String code) {
        if (TextUtils.isEmpty(code)) return false;
        if (lastHttpCode >= 0 && (lastHttpCode < 200 || lastHttpCode >= 300)) return false;
        String lower = code.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("<!doctype") || lower.startsWith("<html")) return false;
        return isJsCode(code);
    }

    /** 坏内容回退用：读磁盘上已有的库缓存（不论是否过期），没有/读失败/空返回 null。 */
    private static String readStaleLibCache(String url) {
        File cached = HkStore.rcCacheFile(url);
        if (cached == null || !cached.exists()) return null;
        return HkStore.readTextFile(cached);
    }

    /** 把 $.exports 重置为 undefined（库文件 eval 前调，防拿到上一个库的旧导出）。 */
    private void clearExports() {
        if (scope == null) return;
        try (CxHandle handle = enterCx()) {
            Scriptable dollar = dollarObject();
            if (dollar != null) ScriptableObject.putProperty(dollar, "exports", Undefined.instance);
        }
    }

    /** 把 JS 侧 setResult/直接返回的值转成卡片列表；不是数组/对象时返回 null。 */
    List<HkCard> cardsFromJs(Object d) {
        if (d == null || d instanceof Undefined) return null;
        if (d instanceof String) {
            try {
                JsonElement parsed = JsonParser.parseString((String) d);
                // 规则常以 res.data = d; setHomeResult(res) 交结果：先拆 data 数组。
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("data")
                        && parsed.getAsJsonObject().get("data").isJsonArray()) {
                    return cardsFromJsonArray(parsed.getAsJsonObject().getAsJsonArray("data"));
                }
                if (parsed.isJsonArray()) return cardsFromJsonArray(parsed.getAsJsonArray());
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        if (d instanceof NativeArray) {
            return cardsFromNativeArray((NativeArray) d);
        }
        if (d instanceof Scriptable) {
            // 同上：对象带 data 数组时拆它（奇优/童话详情、臻品fix 首页都这么交结果）。
            Object data = ScriptableObject.getProperty((Scriptable) d, "data");
            if (data instanceof NativeArray) return cardsFromNativeArray((NativeArray) data);
            if (data instanceof String) {
                List<HkCard> fromData = cardsFromJs(data);
                if (fromData != null) return fromData;
            }
            HkCard card = cardFromJsObject((Scriptable) d);
            if (card == null) return null;
            List<HkCard> cards = new ArrayList<>();
            cards.add(card);
            return cards;
        }
        return null;
    }

    private List<HkCard> cardsFromJsonArray(JsonArray array) {
        List<HkCard> cards = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isJsonObject()) cards.add(cardFromJson(array.get(i).getAsJsonObject()));
        }
        return cards;
    }

    private List<HkCard> cardsFromNativeArray(NativeArray array) {
        List<HkCard> cards = new ArrayList<>();
        for (int i = 0; i < array.getLength(); i++) {
            Object item = array.get(i, array);
            if (item instanceof Scriptable) {
                HkCard card = cardFromJsObject((Scriptable) item);
                if (card != null) cards.add(card);
            }
        }
        return cards;
    }

    private HkCard cardFromJsObject(Scriptable obj) {
        String title = jsString(obj, "title");
        String url = jsString(obj, "url");
        String pic = jsString(obj, "img");
        if (TextUtils.isEmpty(pic)) pic = jsString(obj, "pic_url");
        if (TextUtils.isEmpty(pic)) pic = jsString(obj, "pic");
        HkCard card = new HkCard(title, url, pic, jsString(obj, "desc"));
        card.setColType(jsString(obj, "col_type"));
        Object extra = ScriptableObject.getProperty(obj, "extra");
        if (extra instanceof String) {
            card.setExtra((String) extra);
        } else if (extra instanceof Scriptable && !(extra instanceof Undefined)) {
            Context cx = Context.getCurrentContext();
            if (cx != null && scope != null) {
                try {
                    Object json = NativeJSON.stringify(cx, scope, extra, null, null);
                    if (json instanceof String) card.setExtra((String) json);
                } catch (Exception ignored) {
                }
            }
        }
        return card;
    }

    private HkCard cardFromJson(JsonObject obj) {
        String pic = jsonString(obj, "img");
        if (TextUtils.isEmpty(pic)) pic = jsonString(obj, "pic_url");
        if (TextUtils.isEmpty(pic)) pic = jsonString(obj, "pic");
        HkCard card = new HkCard(
                jsonString(obj, "title"),
                jsonString(obj, "url"),
                pic,
                jsonString(obj, "desc"));
        card.setColType(jsonString(obj, "col_type"));
        if (obj.has("extra")) card.setExtra(obj.get("extra").toString());
        return card;
    }

    /** 取 JSON 对象里的字符串字段：缺失或不是基本类型返回 ""（不抛错）。 */
    private String jsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        try {
            return obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String jsString(Scriptable obj, String name) {
        Object value = ScriptableObject.getProperty(obj, name);
        if (value == null || value instanceof Undefined) return "";
        return Context.toString(value);
    }

    /**
     * 桥里 fetch/post 的实现：同步请求，charset 按 Content-Type 猜，默认 UTF-8。
     * hiker:// 不走 OkHttp（OkHttp 只认 http/https）：
     * <ul>
     *   <li>{@code hiker://page/...}：子页面展开，返回页面 JSON（含 rule 字段）；
     *       页不存在抛 HkException（带页名），不返回 ""——否则 JS 侧
     *       {@code JSON.parse('')} 报错含糊。</li>
     *   <li>{@code hiker://files/...}：读 data/&lt;标题&gt;/ 下对应文件，缺失返回 ""。</li>
     *   <li>其他 {@code hiker://}（empty/search/collection/history 等导航类地址）：
     *       取不到正文，返回 ""。</li>
     * </ul>
     */
    private String httpFetch(String url, Map<String, String> headers, String method, Object body, long timeout) throws HkException {
        return httpFetch(url, new FetchOpts(
                headers == null ? new HashMap<>() : headers,
                method == null ? "GET" : method, body, timeout, false, true, false));
    }

    /**
     * 桥里 fetch/post/batchFetch 的实现：同步请求，charset 按 Content-Type 猜，默认 UTF-8。
     * hiker:// 不走 OkHttp（OkHttp 只认 http/https）：
     * <ul>
     *   <li>{@code hiker://page/...}：子页面展开，返回页面 JSON（含 rule 字段）；
     *       页不存在抛 HkException（带页名），不返回 ""——否则 JS 侧
     *       {@code JSON.parse('')} 报错含糊。</li>
     *   <li>{@code hiker://files/...}：读 data/&lt;标题&gt;/ 下对应文件，缺失返回 ""。</li>
     *   <li>其他 {@code hiker://}（empty/search/collection/history 等导航类地址）：
     *       取不到正文，返回 ""。</li>
     * </ul>
     * <p>
     * 线程安全：只读 rule、只写 lastHttpCode（诊断用，batchFetch 并发下最后
     * 完成的写胜出，无他用），工作线程可直接调。
     * <p>
     * opts.envelope（withHeaders/withStatusCode）时返回官方 wrapFetch 包络 JSON
     * {@code {"body","headers","url","statusCode","error"}}，headers 值是数组、
     * 键小写；失败时 statusCode 为 -1。opts.followRedirects 为 false 时不跟随
     * 重定向。opts.onlyHeaders 时只拿状态码 + 头、不读体。
     */
    private String httpFetch(String url, FetchOpts opts) throws HkException {
        lastHttpCode = -1;
        if (!TextUtils.isEmpty(url)) {
            String u = url.trim();
            if (HkSubpage.isSubpageUrl(u)) {
                return HkSubpage.pageJson(rule, u);
            }
            if (u.toLowerCase(Locale.ROOT).startsWith("hiker://files/")) {
                return HkStore.readDataFile(ruleTitle(), u);
            }
            if (u.toLowerCase(Locale.ROOT).startsWith("hiker://")) {
                Log.i(TAG, "fetch unsupported hiker url, empty: " + u);
                return "";
            }
        }
        try {
            OkHttpClient base = opts.timeout > 0 ? OkHttp.client(opts.timeout) : OkHttp.client();
            OkHttpClient client = opts.followRedirects ? base
                    : base.newBuilder().followRedirects(false).followSslRedirects(false).build();
            Request.Builder builder = new Request.Builder().url(url);
            boolean hasUa = false;
            if (opts.headers != null) {
                for (Map.Entry<String, String> e : opts.headers.entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                    if ("User-Agent".equalsIgnoreCase(e.getKey())) hasUa = true;
                }
            }
            // JS 侧 fetch()/require() 没带 UA 时默认补真浏览器 UA：OkHttp 默认的
            // okhttp/4.x 会被不少站点/WAF 当爬虫挡掉（见 docs/miniapp-nodata-fix.md）。
            if (!hasUa) builder.header("User-Agent", MOBILE_UA);
            if ("POST".equalsIgnoreCase(opts.method)) {
                builder.post(buildBody(opts.body));
            } else if (!"GET".equalsIgnoreCase(opts.method)) {
                builder.method(opts.method, opts.body == null ? null : buildBody(opts.body));
            }
            try (Response res = client.newCall(builder.build()).execute()) {
                lastHttpCode = res.code();
                if (opts.onlyHeaders) {
                    return opts.envelope ? envelope(url, "", res, null) : "";
                }
                if (res.body() == null) return opts.envelope ? envelope(url, "", res, null) : "";
                byte[] bytes = res.body().bytes();
                String contentType = res.header("Content-Type", "");
                String text = new String(bytes, charsetOf(contentType));
                return opts.envelope ? envelope(url, text, res, null) : text;
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + url, e);
            return opts.envelope ? envelope(url, "", null, e.getMessage()) : "";
        }
    }

    /**
     * 照官方 wrapFetch（HkJsRuntime.wrapFetch）：包络键固定为
     * body / headers / url / statusCode / error；headers 值是数组、键统一小写
     * （规则一律用小写 location / set-cookie 访问）；失败时 res 为 null，
     * statusCode 为 -1。
     */
    private static String envelope(String url, String body, Response res, String error) {
        JsonObject o = new JsonObject();
        o.addProperty("body", body == null ? "" : body);
        JsonObject headers = new JsonObject();
        if (res != null) {
            for (Map.Entry<String, List<String>> e : res.headers().toMultimap().entrySet()) {
                JsonArray arr = new JsonArray();
                if (e.getValue() != null) {
                    for (String v : e.getValue()) arr.add(v == null ? "" : v);
                }
                headers.add(e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT), arr);
            }
        }
        o.add("headers", headers);
        o.addProperty("url", url == null ? "" : url);
        o.addProperty("statusCode", res == null ? -1 : res.code());
        o.addProperty("error", error == null ? "" : error);
        return o.toString();
    }

    private RequestBody buildBody(Object body) {
        if (body == null) return RequestBody.create(new byte[0]);
        if (body instanceof String) return RequestBody.create(((String) body).getBytes(StandardCharsets.UTF_8));
        if (body instanceof Map) {
            // batchFetch 在调用线程上已把 NativeObject body 转成的表单（见 Bridge.formParams）。
            ArrayMap<String, String> params = new ArrayMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) body).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) params.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return OkHttp.toBody(params);
        }
        if (body instanceof NativeObject) {
            ArrayMap<String, String> params = new ArrayMap<>();
            NativeObject obj = (NativeObject) body;
            for (Object id : obj.getIds()) {
                String key = String.valueOf(id);
                Object value = ScriptableObject.getProperty(obj, key);
                if (value != null && !(value instanceof Undefined)) params.put(key, Context.toString(value));
            }
            return OkHttp.toBody(params);
        }
        return RequestBody.create(Context.toString(body).getBytes(StandardCharsets.UTF_8));
    }

    private Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private void parseParams(String url) {
        params.clear();
        if (TextUtils.isEmpty(url)) return;
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) return;
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    params.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } else if (!pair.isEmpty()) {
                    params.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 由页面 URL 推主页地址（协议 + host + 端口 + /），对应 MY_HOME。 */
    private String homeOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            StringBuilder sb = new StringBuilder(u.getProtocol()).append("://").append(u.getHost());
            if (u.getPort() != -1) sb.append(':').append(u.getPort());
            return sb.append('/').toString();
        } catch (Exception e) {
            return url;
        }
    }
}
