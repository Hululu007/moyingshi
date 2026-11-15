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
     * saveFile/fileExist/deleteFile、getItem/setItem；myVar 与 item 都落盘到
     * plugins/hk/data/&lt;标题&gt;/，见 {@link HkStore}）、子页面与库
     * （require/$.require 走 {@link HkSubpage}，库 URL 走 require.json 清单）、
     * 域内变量（MY_URL/MY_HOME/MY_RULE/MY_PAGE/MY_TYPE/MY_PARAMS/MOBILE_UA/
     * PC_UA）与 $ 工具（$.toString/$.stringify/$.require/$.hiker/$.importParam/
     * $.exports；$ 本身可调用，返回带 lazyRule/input 的构造器，只产字符串，
     * 点击执行走 {@link HkActionHandler}（第 7 步之七）；getUrl()/getHome()
     * 取当前页/主页地址，refreshPage() 置位后由点击执行路径决定是否重跑当前页。
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
            function refreshPage() { __hk.refreshPage(); }
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
            function getItem(k, d) { return __hk.getItem(k, d); }
            function setItem(k, v) { __hk.setItem(k, v); }
            function confirm(o) { return __hk.confirm(o); }
            function select(o) { return __hk.select(o); }
            function require(url, params) { return __hk.require(url, params); }
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
            function batchFetch(arr) { return __hk.batchFetch(arr); }
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

    /** JS 通过 __hk 调到的 Java 桥。必须是 public static 类，否则 Rhino 反射调不到方法。 */
    public static class Bridge {
        private final HkEngine engine;

        public Bridge(HkEngine engine) {
            this.engine = engine;
        }

        public String fetch(String url) throws HkException {
            return fetch(url, null);
        }

        /** 同步请求，返回响应体字符串；options 支持 headers/method/body/timeout。 */
        public String fetch(String url, Object options) throws HkException {
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

        public String post(String url, Object options) throws HkException {
            if (options instanceof NativeObject) {
                ScriptableObject.putProperty((Scriptable) options, "method", "POST");
                return fetch(url, options);
            }
            return fetch(url, null);
        }

        /**
         * 批量抓取（地基第 1 步留下的抛错桩在此实现）：入参是
         * [{url, method?, headers?, body?, timeout?}] 或纯 URL 字符串数组；
         * 逐项同步抓（顺序），单项失败记 "" 不抛，让规则自己的循环跳过；
         * 返回 NativeArray 字符串数组。非数组入参返回空数组。
         */
        public Object batchFetch(Object arg) {
            List<String> out = new ArrayList<>();
            if (arg instanceof NativeArray) {
                NativeArray arr = (NativeArray) arg;
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i, arr);
                    try {
                        if (item instanceof String) {
                            out.add(fetch((String) item));
                        } else if (item instanceof NativeObject) {
                            Object u = ScriptableObject.getProperty((NativeObject) item, "url");
                            out.add(u == null || u instanceof Undefined ? "" : fetch(Context.toString(u), item));
                        } else {
                            out.add("");
                        }
                    } catch (Exception e) {
                        out.add("");
                    }
                }
            }
            Context cx = Context.getCurrentContext();
            if (cx == null) return out.toArray(new String[0]);
            return cx.newArray(engine.scope, out.toArray(new String[0]));
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
         * 海阔 refreshPage()：记一条日志 + 置位（点击执行路径读它，决定是否重跑当前页；
         * 别的路径不读，行为不变）。
         */
        public void refreshPage() {
            engine.refreshRequested = true;
            Log.i(TAG, "rule refreshPage requested");
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

        /** 海阔 getItem()：读规则持久 KV（items.json），缺失回默认值（默认 ""）。 */
        public String getItem(String key, Object def) {
            String fallback = (def == null || def instanceof Undefined) ? "" : Context.toString(def);
            return engine.getItem(key, fallback);
        }

        public String getItem(String key) {
            return engine.getItem(key, "");
        }

        /** 海阔 setItem()：写规则持久 KV（items.json），立即落盘。 */
        public void setItem(String key, Object value) {
            engine.setItem(key, value == null || value instanceof Undefined ? "" : Context.toString(value));
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
    /** 本次执行里规则 JS 是否调过 refreshPage()（点击执行路径读它，决定是否重跑当前页）。 */
    private boolean refreshRequested;

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
    /** getItem/setItem 的内存缓存（items.json 的内容，prepare 时加载）。 */
    private final Map<String, String> items = new HashMap<>();
    private boolean itemsLoaded;

    /**
     * 准备一次页面执行：建 scope、注入 __hk 桥与 BOOTSTRAP、设置域内变量。
     * 必须在 eval* 之前调；resCode 是本页已抓到的原文（getResCode() 返回它）。
     * myVars 不再清空，改为从 data/&lt;标题&gt;/myvar.json 加载（持久变量跨页可见）；
     * items 同样从 items.json 加载。
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

    /** 从 data/&lt;标题&gt;/items.json 加载 getItem/setItem 的缓存（prepare 时调）。 */
    private void loadItems() {
        items.clear();
        itemsLoaded = true;
        String json = HkStore.readItems(ruleTitle());
        if (TextUtils.isEmpty(json)) return;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) items.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            Log.w(TAG, "load items failed, starting empty", e);
        }
    }

    /** getItem：读规则持久 KV，缺失回默认值（默认 ""）。 */
    String getItem(String key, String def) {
        if (key == null) return def == null ? "" : def;
        if (!itemsLoaded) loadItems();
        String value = items.get(key);
        return value == null ? (def == null ? "" : def) : value;
    }

    /** setItem：写规则持久 KV，立即落盘。 */
    void setItem(String key, String value) {
        if (key == null) return;
        if (!itemsLoaded) loadItems();
        items.put(key, value == null ? "" : value);
        HkStore.saveItems(ruleTitle(), App.gson().toJson(items));
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
     */
    Object require(String url, Object params) throws HkException {
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
            code = httpFetch(u, null, "GET", null, DEFAULT_TIMEOUT_MS);
            if (TextUtils.isEmpty(code)) throw new HkException("require 取数失败（空内容）：" + u);
            // 取到的是 HTML 错误页/拦截页（例如 WAF 418）时不再当 JS 执行——
            // 那样只会在第 1 行报含糊的语法错；直接报清楚（见 docs/miniapp-nodata-fix.md）。
            String lower = code.trim().toLowerCase(Locale.ROOT);
            if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
                throw new HkException("require 取到的不是 JS 脚本（可能是拦截/错误页）：" + u);
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
            OkHttpClient client = timeout > 0 ? OkHttp.client(timeout) : OkHttp.client();
            Request.Builder builder = new Request.Builder().url(url);
            boolean hasUa = false;
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                    if ("User-Agent".equalsIgnoreCase(e.getKey())) hasUa = true;
                }
            }
            // JS 侧 fetch()/require() 没带 UA 时默认补真浏览器 UA：OkHttp 默认的
            // okhttp/4.x 会被不少站点/WAF 当爬虫挡掉（见 docs/miniapp-nodata-fix.md）。
            if (!hasUa) builder.header("User-Agent", MOBILE_UA);
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
