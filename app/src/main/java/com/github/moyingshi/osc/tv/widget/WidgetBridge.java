package com.github.moyingshi.osc.tv.widget;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.bean.TmdbConfig;
import com.github.moyingshi.osc.tv.service.TmdbService;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Widget 宿主 Java 桥：组件脚本能碰到的唯一原生入口。
 *
 * <p>沙箱收紧：脚本只能通过 widget_host.js shim 调用这里的 {@code __widget*} 全局函数；
 * 不暴露 local（drpy 的本地文件桥）、不暴露任何 Android 类、不暴露文件系统、
 * 不暴露 Setting/VodConfig/TmdbConfig；TMDB Key 只在宿主侧注入，绝不进脚本。
 * 所有方法都是同步的（在 JS 单线程执行器上跑，UI 侧永远在后台线程调 WidgetHost）。
 */
public class WidgetBridge {

    private static final int DEFAULT_TIMEOUT_MS = 20000;
    private static final int MAX_TIMEOUT_MS = 60000;
    private static final int MAX_DOM_RESULTS = 200;

    private final String widgetId;
    private final TmdbService tmdbService = new TmdbService();
    private final Map<String, Object> domNodes = new ConcurrentHashMap<>();
    private final AtomicInteger domSeq = new AtomicInteger();

    public WidgetBridge(String widgetId) {
        this.widgetId = widgetId;
    }

    /** storage 的 Prefers 前缀（与 drpy 的 local 隔离）。 */
    public String prefix() {
        return "widget_" + widgetId + "_";
    }

    /** 把 __widget* 全局函数挂到 QuickJS 全局对象上（照 Global.create 的注册方式）。 */
    public void bind(QuickJSContext ctx) {
        ctx.getGlobalObject().setProperty("__widgetHttp", (JSCallFunction) args -> safe(() -> http(str(args, 0), str(args, 1), str(args, 2), strOrNull(args, 3), num(args, 4, DEFAULT_TIMEOUT_MS), bool(args, 5, true)), httpError()));
        ctx.getGlobalObject().setProperty("__widgetTmdb", (JSCallFunction) args -> safe(() -> tmdb(str(args, 0), str(args, 1)), tmdbError("TMDB 请求失败")));
        ctx.getGlobalObject().setProperty("__widgetStorageGet", (JSCallFunction) args -> safe(() -> storageGet(str(args, 0)), null));
        ctx.getGlobalObject().setProperty("__widgetStorageSet", (JSCallFunction) args -> safe(() -> {
            storageSet(str(args, 0), strOrNull(args, 1));
            return null;
        }, null));
        ctx.getGlobalObject().setProperty("__widgetStorageRemove", (JSCallFunction) args -> safe(() -> {
            storageRemove(str(args, 0));
            return null;
        }, null));
        ctx.getGlobalObject().setProperty("__widgetStorageClear", (JSCallFunction) args -> safe(() -> {
            storageClear();
            return null;
        }, null));
        ctx.getGlobalObject().setProperty("__widgetDomParse", (JSCallFunction) args -> safe(() -> domParse(str(args, 0)), ""));
        ctx.getGlobalObject().setProperty("__widgetDomSelect", (JSCallFunction) args -> safe(() -> domSelect(str(args, 0), str(args, 1)), "[]"));
        ctx.getGlobalObject().setProperty("__widgetDomSelectFirst", (JSCallFunction) args -> safe(() -> domSelectFirst(str(args, 0), str(args, 1)), "null"));
        ctx.getGlobalObject().setProperty("__widgetDomText", (JSCallFunction) args -> safe(() -> domText(str(args, 0)), ""));
        ctx.getGlobalObject().setProperty("__widgetDomHtml", (JSCallFunction) args -> safe(() -> domHtml(str(args, 0)), ""));
        ctx.getGlobalObject().setProperty("__widgetDomAttr", (JSCallFunction) args -> safe(() -> domAttr(str(args, 0), str(args, 1)), ""));
        ctx.getGlobalObject().setProperty("__widgetDomRemove", (JSCallFunction) args -> safe(() -> {
            domRemove(str(args, 0));
            return null;
        }, null));
        ctx.getGlobalObject().setProperty("__widgetLog", (JSCallFunction) args -> safe(() -> {
            log(str(args, 0), str(args, 1));
            return null;
        }, null));
    }

    private interface Supplier {
        String call() throws Exception;
    }

    private String safe(Supplier supplier, String fallback) {
        try {
            return supplier.call();
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static String str(Object[] args, int i) {
        return args != null && args.length > i && args[i] != null ? String.valueOf(args[i]) : "";
    }

    private static String strOrNull(Object[] args, int i) {
        if (args == null || args.length <= i || args[i] == null) return null;
        String value = String.valueOf(args[i]);
        return value.isEmpty() ? null : value;
    }

    private static int num(Object[] args, int i, int def) {
        try {
            if (args == null || args.length <= i || args[i] == null) return def;
            if (args[i] instanceof Number) return ((Number) args[i]).intValue();
            return (int) Double.parseDouble(String.valueOf(args[i]));
        } catch (Throwable e) {
            return def;
        }
    }

    private static boolean bool(Object[] args, int i, boolean def) {
        if (args == null || args.length <= i || args[i] == null) return def;
        if (args[i] instanceof Boolean) return (Boolean) args[i];
        return Boolean.parseBoolean(String.valueOf(args[i]));
    }

    // ---------- http ----------

    private String httpError() {
        return "{\"status\":0,\"headers\":{},\"body\":\"\"}";
    }

    /**
     * 同步 HTTP：返回 JSON 串 {"status":200,"headers":{...},"body":"..."}。
     * 网络异常不抛到脚本，返回 status 0，由 shim 转成 {ok:false}。
     */
    public String http(String method, String url, String headersJson, String body, int timeoutMs, boolean redirect) {
        JsonObject result = new JsonObject();
        try {
            if (url == null || !url.startsWith("http")) throw new IllegalArgumentException("url 非法");
            int timeout = Math.max(1000, Math.min(timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs, MAX_TIMEOUT_MS));
            Request.Builder builder = new Request.Builder().url(url);
            Map<String, String> headers = Json.toMap(headersJson);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) builder.header(entry.getKey(), entry.getValue());
                }
            }
            String m = method == null ? "GET" : method.toUpperCase();
            if ("POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m)) {
                String contentType = headers != null ? headerIgnoreCase(headers, "Content-Type") : null;
                MediaType mediaType = contentType != null ? MediaType.parse(contentType) : MediaType.parse("application/json; charset=utf-8");
                RequestBody requestBody = RequestBody.create(body == null ? "" : body, mediaType);
                if ("POST".equals(m)) builder.post(requestBody);
                else if ("PUT".equals(m)) builder.put(requestBody);
                else builder.delete(requestBody);
            } else {
                builder.get();
            }
            try (Response res = OkHttp.client(redirect, timeout).newCall(builder.build()).execute()) {
                result.addProperty("status", res.code());
                JsonObject hs = new JsonObject();
                for (String name : res.headers().names()) {
                    List<String> values = res.headers(name);
                    hs.addProperty(name, values == null ? "" : String.join(", ", values));
                }
                result.add("headers", hs);
                result.addProperty("body", res.body() == null ? "" : res.body().string());
            }
        } catch (Throwable e) {
            SpiderDebug.log("widget", "http failed: %s", e.getMessage());
            result.addProperty("status", 0);
            result.add("headers", new JsonObject());
            result.addProperty("body", "");
        }
        return App.gson().toJson(result);
    }

    private static String headerIgnoreCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    // ---------- tmdb ----------

    private String tmdbError(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return App.gson().toJson(result);
    }

    /**
     * TMDB 通道：API Key 在宿主侧注入，绝不进脚本。
     * 返回 {"ok":true,"body":"{...}"} 或 {"ok":false,"error":"..."}，由 shim 转成值或 JS Error。
     */
    public String tmdb(String path, String paramsJson) {
        try {
            Map<String, String> params = Json.toMap(paramsJson);
            TmdbConfig config = TmdbConfig.objectFrom(Setting.getTmdbConfig());
            JsonObject body = tmdbService.get(path, params, config);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("body", App.gson().toJson(body));
            return App.gson().toJson(result);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? "TMDB 请求失败" : e.getMessage();
            SpiderDebug.log("widget", "tmdb failed: %s", message);
            return tmdbError(message);
        }
    }

    // ---------- storage ----------

    public String storageGet(String key) {
        try {
            return Prefers.getString(prefix() + key, null);
        } catch (Throwable e) {
            return null;
        }
    }

    public void storageSet(String key, String value) {
        if (value == null) {
            storageRemove(key);
            return;
        }
        Prefers.put(prefix() + key, value);
    }

    public void storageRemove(String key) {
        Prefers.remove(prefix() + key);
    }

    public void storageClear() {
        String p = prefix();
        try {
            for (String key : Prefers.getPrefers().getAll().keySet()) {
                if (key.startsWith(p)) Prefers.remove(key);
            }
        } catch (Throwable e) {
            SpiderDebug.log("widget", "storage clear failed: %s", e.getMessage());
        }
    }

    // ---------- dom（jsoup 基础版） ----------

    public String domParse(String html) {
        String id = "d" + domSeq.incrementAndGet();
        domNodes.put(id, Jsoup.parse(html == null ? "" : html));
        return id;
    }

    private Object resolve(String target) {
        if (target == null) return null;
        Object node = domNodes.get(target);
        if (node != null) return node;
        return Jsoup.parse(target);
    }

    public String domSelect(String target, String selector) {
        try {
            Object root = resolve(target);
            Elements elements = root instanceof Document ? ((Document) root).select(selector) : root instanceof Element ? ((Element) root).select(selector) : null;
            if (elements == null) return "[]";
            JsonArray array = new JsonArray();
            int count = 0;
            for (Element el : elements) {
                if (count++ >= MAX_DOM_RESULTS) break;
                array.add(wrap(el));
            }
            return App.gson().toJson(array);
        } catch (Throwable e) {
            return "[]";
        }
    }

    public String domSelectFirst(String target, String selector) {
        try {
            Object root = resolve(target);
            Elements elements = root instanceof Document ? ((Document) root).select(selector) : root instanceof Element ? ((Element) root).select(selector) : null;
            if (elements == null || elements.isEmpty()) return "null";
            return App.gson().toJson(wrap(elements.first()));
        } catch (Throwable e) {
            return "null";
        }
    }

    public String domText(String id) {
        Object node = resolve(id);
        if (node instanceof Element) return ((Element) node).text();
        if (node instanceof Document) return ((Document) node).body().text();
        return "";
    }

    public String domHtml(String id) {
        Object node = resolve(id);
        if (node instanceof Element) return ((Element) node).html();
        if (node instanceof Document) return ((Document) node).body().html();
        return "";
    }

    public String domAttr(String id, String name) {
        Object node = resolve(id);
        if (node instanceof Element) return ((Element) node).attr(name == null ? "" : name);
        return "";
    }

    public void domRemove(String id) {
        domNodes.remove(id);
    }

    private JsonObject wrap(Element el) {
        String id = "n" + domSeq.incrementAndGet();
        domNodes.put(id, el);
        JsonObject object = new JsonObject();
        object.addProperty("__node", id);
        object.addProperty("tag", el.tagName().toLowerCase());
        object.addProperty("text", el.text().trim());
        object.addProperty("html", el.html());
        object.addProperty("outerHtml", el.outerHtml());
        JsonObject attrs = new JsonObject();
        for (org.jsoup.nodes.Attribute attr : el.attributes()) attrs.addProperty(attr.getKey(), attr.getValue());
        object.add("attributes", attrs);
        object.add("attrs", attrs);
        object.addProperty("id", el.id());
        object.addProperty("className", el.className());
        JsonArray classes = new JsonArray();
        for (String cls : el.classNames()) classes.add(cls);
        object.add("classes", classes);
        return object;
    }

    // ---------- log ----------

    public void log(String level, String message) {
        SpiderDebug.log("widget", "[%s] %s", level == null ? "log" : level, message == null ? "" : message);
    }
}
