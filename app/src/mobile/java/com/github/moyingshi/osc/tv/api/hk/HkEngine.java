package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.collection.ArrayMap;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.App;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * 规则 JS 里用的内置 API（注入 scope 的 JS 函数，见 {@link #BOOTSTRAP}）。
     * 覆盖 js_api.md 里列表解析真正用到的那部分：输出（setResult/setError/log/
     * toast）、请求（fetch/post/getResCode）、DOM（parseDom/pdfh/pdfa）、
     * 编解码（md5/base64）、变量（putVar/getVar/putMyVar/getMyVar/getParam）、
     * 域内变量（MY_URL/MY_HOME/MY_RULE/MY_PAGE/MY_TYPE/MY_PARAMS/MOBILE_UA/
     * PC_UA）与 $ 工具（$.toString/$.stringify）。
     * 暂不支持的（xpath/batchFetch/getCryptoJS/aes/encodeStr/require/request 等）
     * 在 BOOTSTRAP 里是会抛明确错误的桩，不是静默缺失——规则用到了能在日志里
     * 一眼看到缺的是哪个 API。
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
            function getVar(k) { return __hk.getVar(k); }
            function clearVar() { __hk.clearVar(); }
            function putMyVar(k, v) { __hk.putMyVar(k, v); }
            function getMyVar(k) { return __hk.getMyVar(k); }
            function clearMyVar() { __hk.clearMyVar(); }
            function getParam(k, d) { return __hk.getParam(k, d); }
            function getRule() { return JSON.parse(__hk.getRuleJson()); }
            var MY_RULE = JSON.parse(__hk.getRuleJson());
            var MY_PARAMS = JSON.parse(__hk.getParamsJson());
            var MY_URL = __hk.getPageUrl();
            var MY_HOME = __hk.getHomeUrl();
            var MY_PAGE = __hk.getPage();
            var MY_TYPE = __hk.getPageType();
            var MOBILE_UA = __hk.getMobileUa();
            var PC_UA = __hk.getPcUa();
            var $ = {
              toString: function(fn) { var args = Array.prototype.slice.call(arguments, 1); return '(' + fn.toString() + ')(' + args.map(function(a) { return JSON.stringify(a); }).join(',') + ')'; },
              stringify: function(o) { return JSON.stringify(o); }
            };
            var xpath = __unsupported('xpath');
            var xpa = xpath;
            var xpathArray = __unsupported('xpathArray');
            var batchFetch = __unsupported('batchFetch');
            var bf = batchFetch;
            var getCryptoJS = __unsupported('getCryptoJS');
            var encodeStr = __unsupported('encodeStr');
            var decodeStr = __unsupported('decodeStr');
            var aesEncode = __unsupported('aesEncode');
            var aesDecode = __unsupported('aesDecode');
            var require = __unsupported('require');
            var request = __unsupported('request');
            """;

    /** 引擎抛出的异常：JS 语法/运行错、preRule 失败、网络失败都包成这个。 */
    public static class HkException extends Exception {
        public HkException(String message) { super(message); }
        public HkException(String message, Throwable cause) { super(message, cause); }
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

    /** JS 通过 __hk 调到的 Java 桥。必须是 public static 类，否则 Rhino 反射调不到方法。 */
    public static class Bridge {
        private final HkEngine engine;

        public Bridge(HkEngine engine) {
            this.engine = engine;
        }

        public String fetch(String url) {
            return fetch(url, null);
        }

        /** 同步请求，返回响应体字符串；options 支持 headers/method/body/timeout。 */
        public String fetch(String url, Object options) {
            if (TextUtils.isEmpty(url)) return "";
            Map<String, String> headers = new HashMap<>();
            String method = "GET";
            Object body = null;
            long timeout = DEFAULT_TIMEOUT_MS;
            if (options instanceof NativeObject) {
                NativeObject opt = (NativeObject) options;
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
                if (m instanceof String && !((String) m).isEmpty()) method = ((String) m).toUpperCase();
                body = ScriptableObject.getProperty(opt, "body");
                if (body instanceof Undefined) body = null;
                Object t = ScriptableObject.getProperty(opt, "timeout");
                if (t instanceof Number) timeout = ((Number) t).longValue();
            }
            return engine.httpFetch(url, headers, method, body, timeout);
        }

        public String post(String url, Object options) {
            if (options instanceof NativeObject) {
                ScriptableObject.putProperty((Scriptable) options, "method", "POST");
                return fetch(url, options);
            }
            return fetch(url, null);
        }

        public String getResCode() {
            return engine.resCode == null ? "" : engine.resCode;
        }

        public void setResult(Object d) {
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
            // 第 1 步没有 UI，记一条日志即可；列表页阶段再决定要不要真弹 toast。
            Log.i(TAG, "rule toast: " + msg);
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

        public Object getVar(String k) {
            Object v = k == null ? null : engine.vars.get(k);
            return v == null ? null : v;
        }

        public void clearVar() {
            engine.vars.clear();
        }

        public void putMyVar(String k, Object v) {
            if (k != null) engine.myVars.put(k, v);
        }

        public Object getMyVar(String k) {
            Object v = k == null ? null : engine.myVars.get(k);
            return v == null ? null : v;
        }

        public void clearMyVar() {
            engine.myVars.clear();
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

    private Scriptable scope;
    private HkRule rule;
    private String ruleJson;
    private String pageUrl = "";
    private String homeUrl = "";
    private int page = 1;
    private String pageType = "home";
    private String resCode = "";
    private List<HkCard> result;
    private final Map<String, Object> vars = new HashMap<>();
    private final Map<String, Object> myVars = new HashMap<>();
    private final Map<String, String> params = new HashMap<>();

    /**
     * 准备一次页面执行：建 scope、注入 __hk 桥与 BOOTSTRAP、设置域内变量。
     * 必须在 eval* 之前调；resCode 是本页已抓到的原文（getResCode() 返回它）。
     */
    public void prepare(HkRule rule, String pageUrl, int page, String pageType, String resCode) throws HkException {
        this.rule = rule;
        this.ruleJson = rule == null ? "{}" : rule.toJson();
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.homeUrl = homeOf(this.pageUrl);
        this.page = page;
        this.pageType = TextUtils.isEmpty(pageType) ? "home" : pageType;
        this.resCode = resCode == null ? "" : resCode;
        this.result = null;
        this.myVars.clear();
        parseParams(this.pageUrl);
        Context cx = factory.enterContext();
        try {
            scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "__hk", Context.javaToJS(bridge, scope));
            cx.evaluateString(scope, BOOTSTRAP, "hk-bootstrap", 1, null);
        } catch (RhinoException e) {
            throw new HkException("JS 环境初始化失败: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    /** 执行规则的 preRule（有才跑，与 find_rule 共用 scope），失败抛异常。 */
    public void evalPreRule() throws HkException {
        if (rule == null || TextUtils.isEmpty(rule.getPreRule())) return;
        eval(rule.getPreRule(), "preRule");
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
        Context cx = factory.enterContext();
        try {
            return cx.evaluateString(scope, js, sourceName, 1, null);
        } catch (RhinoException e) {
            throw new HkException("JS 执行失败: " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    /** 把 JS 侧 setResult/直接返回的值转成卡片列表；不是数组/对象时返回 null。 */
    List<HkCard> cardsFromJs(Object d) {
        if (d == null || d instanceof Undefined) return null;
        if (d instanceof String) {
            try {
                JsonArray array = JsonParser.parseString((String) d).getAsJsonArray();
                List<HkCard> cards = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i).isJsonObject()) cards.add(cardFromJson(array.get(i).getAsJsonObject()));
                }
                return cards;
            } catch (Exception e) {
                return null;
            }
        }
        if (d instanceof NativeArray) {
            NativeArray array = (NativeArray) d;
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
        if (d instanceof Scriptable) {
            HkCard card = cardFromJsObject((Scriptable) d);
            if (card == null) return null;
            List<HkCard> cards = new ArrayList<>();
            cards.add(card);
            return cards;
        }
        return null;
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

    /** 桥里 fetch/post 的实现：同步请求，charset 按 Content-Type 猜，默认 UTF-8。 */
    private String httpFetch(String url, Map<String, String> headers, String method, Object body, long timeout) {
        try {
            OkHttpClient client = timeout > 0 ? OkHttp.client(timeout) : OkHttp.client();
            Request.Builder builder = new Request.Builder().url(url);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
            }
            if ("POST".equalsIgnoreCase(method)) {
                builder.post(buildBody(body));
            } else if (!"GET".equalsIgnoreCase(method)) {
                builder.method(method, body == null ? null : buildBody(body));
            }
            try (Response res = client.newCall(builder.build()).execute()) {
                if (res.body() == null) return "";
                byte[] bytes = res.body().bytes();
                String contentType = res.header("Content-Type", "");
                return new String(bytes, charsetOf(contentType));
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + url, e);
            return "";
        }
    }

    private RequestBody buildBody(Object body) {
        if (body == null) return RequestBody.create(new byte[0]);
        if (body instanceof String) return RequestBody.create(((String) body).getBytes(StandardCharsets.UTF_8));
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
