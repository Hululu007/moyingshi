package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 规则执行器骨架，对应魔影小程序的 f22（见逆向报告 §7.10：detail/t() 等
 * 核心方法反编译失败，首页流程 k() 有源码，按它搭骨架）。
 * <p>
 * 首页流程（f22.k 的同款顺序）：校验 → 拼 URL（fy 占位符）→ OkHttp 抓页 →
 * preRule 先跑 → find_rule 是 js: 走 Rhino（w02），否则走选择器解析（b32）。
 * <p>
 * 第 1 步只做到首页（home），且只实现 GET；搜索、详情、懒加载播放、云口令、
 * 导出都是后面的阶段。调用方必须在后台线程跑（网络同步 + Rhino 执行）。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkExecutor {

    private static final String TAG = "HkExecutor";
    private static final Pattern FIRST_PAGE = Pattern.compile("\\[firstPage=([^\\]]+)\\]");

    /** 拼好的请求：最终 URL + headers + 编码。包内可见，供搜索/详情阶段复用。 */
    public static class RequestSpec {
        public String url = "";
        public Map<String, String> headers = new HashMap<>();
        public String charset = "UTF-8";
    }

    /** 首页，默认第 1 页、第一个分类。 */
    public static List<HkCard> home(HkRule rule) throws HkEngine.HkException {
        return home(rule, 1, null);
    }

    /** 首页。page 从 1 起；classId 为空取第一个分类（分类名或下标都行）。 */
    public static List<HkCard> home(HkRule rule, int page, String classId) throws HkEngine.HkException {
        if (rule == null) throw new HkEngine.HkException("规则为空");
        String err = rule.validate();
        if (err != null) throw new HkEngine.HkException(err);
        RequestSpec spec = buildHomeRequest(rule, page, classId);
        String pageText = fetch(spec);
        HkEngine engine = new HkEngine();
        engine.prepare(rule, spec.url, page, "home", pageText);
        engine.evalPreRule();
        if (rule.isJsFindRule()) {
            return engine.evalFindRule(rule.getFindRule());
        }
        return HkParser.parseHomeList(pageText, rule.getFindRule(), spec.url, engine);
    }

    /**
     * 拼首页请求。模板分两种形态（真实规则里两种都有）：
     * <ul>
     *   <li>ID 式：class_url 是 ID 列表（如 1&2&3），模板是规则 url，fyclass 换成
     *       选中分类对应的 ID；</li>
     *   <li>模板式：class_url 每一项就是完整 URL 模板，模板取对应项，fyclass
     *       若还在里面则换成分类名（外推，待真实规则验证）。</li>
     * </ul>
     * fyarea/fyyear/fysort 现阶段取各组第一个选项（筛选 UI 是后面的阶段）。
     * URL 后缀修饰符：{@code ;get} / {@code ;POST} / {@code ;GBK} /
     * {@code ;UTF-8} / {@code ;{headers JSON}}。POST 第 1 步不支持（body 格式
     * 未核实，见 docs/miniapp-foundation.md），遇到直接抛错、不静默发 GET。
     */
    public static RequestSpec buildHomeRequest(HkRule rule, int page, String classId) throws HkEngine.HkException {
        String[] classUrls = rule.classUrls();
        String[] classNames = rule.classNames();
        boolean urlStyle = false;
        for (String u : classUrls) {
            if (u.startsWith("http") || u.contains("://")) {
                urlStyle = true;
                break;
            }
        }
        String template;
        String classValue = "";
        String className = "";
        if (urlStyle) {
            template = rule.classUrlFor(classId);
            className = classNameFor(rule, classId);
        } else {
            template = rule.getUrl();
            int index = classIndexFor(rule, classId);
            if (index >= 0 && index < classUrls.length) classValue = classUrls[index];
            if (TextUtils.isEmpty(classValue) && classUrls.length > 0) classValue = classUrls[0];
            if (TextUtils.isEmpty(classValue) && classNames.length > 0) classValue = classNames[0];
        }
        if (TextUtils.isEmpty(template)) throw new HkEngine.HkException("规则缺少 url");

        // 1. 拆 ; 修饰符（引号内保护，JSON 里的 ; 不切）。
        List<String> segments = HkParser.splitOutsideQuotes(template, ";");
        StringBuilder urlPart = new StringBuilder(segments.get(0));
        String method = "GET";
        String charset = "UTF-8";
        Map<String, String> urlHeaders = new HashMap<>();
        boolean modifiersStarted = false;
        for (int i = 1; i < segments.size(); i++) {
            String seg = segments.get(i).trim();
            if (seg.equalsIgnoreCase("get")) {
                method = "GET";
                modifiersStarted = true;
                continue;
            }
            if (seg.equalsIgnoreCase("post")) {
                method = "POST";
                modifiersStarted = true;
                continue;
            }
            if (isCharset(seg)) {
                charset = seg.toUpperCase();
                modifiersStarted = true;
                continue;
            }
            if (seg.startsWith("{")) {
                String json = seg;
                while (!bracesBalanced(json) && i + 1 < segments.size()) {
                    json += ";" + segments.get(++i);
                }
                parseJsonHeaders(json, urlHeaders);
                modifiersStarted = true;
                continue;
            }
            if (!modifiersStarted) {
                // 修饰符还没开始：这个 ; 是 URL 本身的一部分，拼回去。
                urlPart.append(';').append(segments.get(i));
            } else {
                Log.w(TAG, "忽略无法识别的 URL 修饰符: " + seg);
            }
        }
        if ("POST".equals(method)) {
            throw new HkEngine.HkException("POST 请求暂不支持（第 1 步仅实现 GET）");
        }
        String url = urlPart.toString();

        // 2. [firstPage=N]：第 1 页用 N 做页码。
        int effectivePage = Math.max(1, page);
        Matcher firstPage = FIRST_PAGE.matcher(url);
        if (firstPage.find()) {
            if (effectivePage == 1) {
                try {
                    effectivePage = Integer.parseInt(firstPage.group(1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            url = firstPage.replaceAll("");
        }

        // 3. fypage（含 @...@ 运算，如 fypage@-1@*20@ = (page-1)*20）。
        url = replacePagePlaceholders(url, effectivePage);

        // 4. fyclass / fyarea / fyyear / fysort。
        if (urlStyle) {
            url = url.replace("fyclass", TextUtils.isEmpty(className) ? "" : className);
        } else {
            url = url.replace("fyclass", TextUtils.isEmpty(classValue) ? "" : classValue);
        }
        url = url.replace("fyarea", firstId(rule.getAreaUrl()));
        url = url.replace("fyyear", firstId(rule.getYearUrl()));
        url = url.replace("fysort", firstId(rule.getSortUrl()));

        RequestSpec spec = new RequestSpec();
        spec.url = url;
        spec.charset = charset;
        Map<String, String> headers = new HashMap<>();
        parseFirstHeader(rule.getFirstHeader(), headers);
        if (!TextUtils.isEmpty(rule.getUa())) headers.put("User-Agent", rule.getUa());
        headers.putAll(urlHeaders);
        spec.headers = headers;
        return spec;
    }

    /** 同步抓一页，按指定编码解码；失败抛 HkException（带 URL 与原因）。 */
    static String fetch(RequestSpec spec) throws HkEngine.HkException {
        OkHttpClient client = OkHttp.client();
        Request.Builder builder = new Request.Builder().url(spec.url);
        for (Map.Entry<String, String> e : spec.headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
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

    private static Charset charsetFor(String name) {
        if (TextUtils.isEmpty(name)) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(name.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static boolean isCharset(String seg) {
        String s = seg.trim().toUpperCase();
        return "UTF-8".equals(s) || "GBK".equals(s) || "GB2312".equals(s) || "GB18030".equals(s);
    }

    private static boolean bracesBalanced(String json) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth <= 0;
    }

    private static void parseJsonHeaders(String json, Map<String, String> out) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            Log.w(TAG, "URL headers JSON 解析失败: " + json, e);
        }
    }

    /**
     * 解析规则 firstHeader：JSON（{"User-Agent": "..."}）或 key: value 行
     *（& 或换行分隔）。格式是外推（报告里只给了字段名），待真实规则验证。
     */
    private static void parseFirstHeader(String firstHeader, Map<String, String> out) {
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

    /** fypage 后跟的 @...@ 运算组：-1 加减、*20 乘、/2 除，按顺序应用。 */
    private static String replacePagePlaceholders(String url, int page) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < url.length()) {
            int at = url.indexOf("fypage", i);
            if (at < 0) {
                out.append(url, i, url.length());
                break;
            }
            out.append(url, i, at);
            double value = page;
            int j = at + "fypage".length();
            while (j + 1 < url.length() && url.charAt(j) == '@') {
                int end = url.indexOf('@', j + 1);
                if (end < 0) break;
                value = applyPageOp(value, url.substring(j + 1, end));
                j = end + 1;
            }
            out.append((int) Math.round(value));
            i = j;
        }
        return out.toString();
    }

    private static double applyPageOp(double value, String op) {
        op = op.trim();
        if (op.isEmpty()) return value;
        try {
            if (op.startsWith("*")) return value * Double.parseDouble(op.substring(1));
            if (op.startsWith("/")) {
                double d = Double.parseDouble(op.substring(1));
                return d == 0 ? value : value / d;
            }
            if (op.startsWith("+")) return value + Double.parseDouble(op.substring(1));
            if (op.startsWith("-")) return value - Double.parseDouble(op.substring(1));
            return Double.parseDouble(op);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** classId（分类名或下标）在 class_name 里的下标，找不到返回 -1。 */
    private static int classIndexFor(HkRule rule, String classId) {
        if (TextUtils.isEmpty(classId)) return 0;
        String[] names = rule.classNames();
        for (int i = 0; i < names.length; i++) {
            if (classId.equals(names[i])) return i;
        }
        try {
            int index = Integer.parseInt(classId);
            if (index >= 0 && index < names.length) return index;
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    private static String classNameFor(HkRule rule, String classId) {
        int index = classIndexFor(rule, classId);
        String[] names = rule.classNames();
        if (index >= 0 && index < names.length) return names[index];
        return TextUtils.isEmpty(classId) ? "" : classId;
    }

    /** 取筛选组（area_url/year_url/sort_url）的第一个 ID；是 URL 模板时返回 ""。 */
    private static String firstId(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String first = value.split("&")[0].trim();
        if (first.startsWith("http") || first.contains("://")) return "";
        return first;
    }
}
