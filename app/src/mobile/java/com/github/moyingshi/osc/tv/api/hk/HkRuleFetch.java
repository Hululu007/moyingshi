package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 规则页请求工具（5e：自 {@code HkExecutor} 逐字移入，见
 * docs/miniapp-xingchen-5e-click-dispatch-cleanup.md §2.6）。
 * <p>
 * {@code HkExecutor} 删除后仍有两处调用方（{@code HkPageActivity.sdetail}、
 * {@code HkClickDispatcher.runLazy} 的选择器路），故请求三件套单独立类；
 * {@code HkParserKit} 是逐字移植文件，不许往里加自有方法。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。调用方必须在后台线程跑（网络同步）。
 */
public final class HkRuleFetch {

    private static final String TAG = "HkRuleFetch";

    private HkRuleFetch() {
    }

    /** 拼好的请求：最终 URL + headers + 编码。包内可见，供搜索/详情阶段复用。 */
    public static class RequestSpec {
        public String url = "";
        public Map<String, String> headers = new HashMap<>();
        public String charset = "UTF-8";
        /** 请求方法：默认 GET；搜索路径的 ;post 修饰符会写成 POST。 */
        public String method = "GET";
        /** POST body：已是编码后的 query 字符串（不再二次编码），GET 时为空。 */
        public String body = "";
    }

    /** 详情请求：URL 来自卡片（无 fy 占位符），请求头与首页同款（firstHeader + ua），编码默认 UTF-8。 */
    static RequestSpec buildDetailRequest(HkRule rule, String url) {
        RequestSpec spec = new RequestSpec();
        spec.url = url;
        spec.charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
        parseFirstHeader(rule.getFirstHeader(), headers);
        if (!TextUtils.isEmpty(rule.getUa())) headers.put("User-Agent", HkEngine.resolveUa(rule.getUa()));
        spec.headers = headers;
        return spec;
    }

    /** 同步抓一页，按指定编码解码；失败抛 HkException（带 URL 与原因）。 */
    static String fetch(RequestSpec spec) throws HkEngine.HkException {
        OkHttpClient client = HkHttpClient.client();
        Request.Builder builder = new Request.Builder().url(spec.url);
        for (Map.Entry<String, String> e : spec.headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        if ("POST".equalsIgnoreCase(spec.method)) {
            // body 已是编码后的 query 字符串，不再二次编码（搜索路径用，首页不用）。
            builder.post(RequestBody.create(spec.body == null ? "" : spec.body,
                    MediaType.parse("application/x-www-form-urlencoded")));
        }
        try (Response res = client.newCall(builder.build()).execute()) {
            if (!res.isSuccessful()) {
                throw new HkEngine.HkException("请求失败: " + spec.url + " (HTTP " + res.code() + ")");
            }
            if (res.body() == null) {
                throw new HkEngine.HkException("请求无响应体: " + spec.url);
            }
            return new String(res.body().bytes(), charsetFor(spec.charset));
        } catch (HkEngine.HkException e) {
            throw e;
        } catch (Exception e) {
            throw new HkEngine.HkException("请求失败: " + spec.url + " (" + e.getMessage() + ")", e);
        }
    }

    /** 只有 http/https 才走 OkHttp：hiker://empty 等规则内地址不抓页（见 docs/miniapp-hk-fixes.md）。 */
    static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 解析规则 firstHeader：JSON（{"User-Agent": "..."}）或 key: value 行
     *（& 或换行分隔）。格式是外推（报告里只给了字段名），待真实规则验证。
     */
    static void parseFirstHeader(String firstHeader, Map<String, String> out) {
        if (TextUtils.isEmpty(firstHeader)) return;
        String text = firstHeader.trim();
        if (text.startsWith("{")) {
            parseJsonHeaders(text, out);
            return;
        }
        for (String line : text.split("[&\n]")) {
            int sep = line.indexOf(':');
            if (sep < 0) sep = line.indexOf('=');
            if (sep > 0) {
                String key = line.substring(0, sep).trim();
                String value = line.substring(sep + 1).trim();
                if (!key.isEmpty() && !value.isEmpty()) out.put(key, value);
            }
        }
    }

    static void parseJsonHeaders(String json, Map<String, String> out) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            Log.w(TAG, "URL headers JSON 解析失败: " + json, e);
        }
    }

    static Charset charsetFor(String name) {
        if (TextUtils.isEmpty(name)) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(name.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
