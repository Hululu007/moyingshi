package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 规则执行器骨架，对应魔影小程序的 f22（见逆向报告 §7.10：detail/t() 等
 * 核心方法反编译失败，首页流程 k() 有源码，按它搭骨架）。
 * <p>
 * 首页流程（f22.k 的同款顺序）：校验 → 拼 URL（fy 占位符）→ OkHttp 抓页 →
 * preRule 先跑 → find_rule 是 js: 走 Rhino（w02），否则走选择器解析（b32）。
 * <p>
 * 第 1 步只做到首页（home），且只实现 GET；详情、懒加载播放、云口令、导出
 * 是后面的阶段。第 6 步补了搜索（search）：search_url 的 ** 占位替换 +
 * searchFind 解析，走与首页同款的执行路径；POST 只在搜索路径支持
 * （首页 buildHomeRequest 遇 POST 仍抛错，见 buildSearchRequest）。
 * 调用方必须在后台线程跑（网络同步 + Rhino 执行）。
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
        /** 请求方法：默认 GET；搜索路径的 ;post 修饰符会写成 POST。 */
        public String method = "GET";
        /** POST body：已是编码后的 query 字符串（不再二次编码），GET 时为空。 */
        public String body = "";
    }

    /** 首页，默认第 1 页、第一个分类。 */
    public static List<HkCard> home(HkRule rule) throws HkEngine.HkException {
        return home(rule, 1, null);
    }

    /** 首页。page 从 1 起；classId 为空取第一个分类（分类名或下标都行）。 */
    public static List<HkCard> home(HkRule rule, int page, String classId) throws HkEngine.HkException {
        return homeDetailed(rule, page, classId).cards;
    }

    /** 首页取数结果（含命中诊断，供列表页 0 条时展示）。 */
    public static class HomeOutcome {
        public List<HkCard> cards = new ArrayList<>();
        /** 命中诊断：页面大小、选择器命中数 / JS 返回情况（只在 0 条时有意义）。 */
        public String diag = "";
    }

    /** 首页（带命中诊断，见 docs/miniapp-detail.md“列表 0 条命中诊断”）。 */
    public static HomeOutcome homeDetailed(HkRule rule, int page, String classId) throws HkEngine.HkException {
        if (rule == null) throw new HkEngine.HkException("规则为空");
        String err = rule.validate();
        if (err != null) throw new HkEngine.HkException(err);
        RequestSpec spec = buildHomeRequest(rule, page, classId);
        // hiker://empty 等规则内地址不走 OkHttp 抓页（OkHttp 只认 http/https，
        // 否则抛 scheme 错，见 docs/miniapp-hk-fixes.md）：JS 规则直接在空页上跑，
        // 自己 fetch 取数；选择器规则对空页解析得 0 条，诊断里写明。
        boolean skippedFetch = !isHttpUrl(spec.url);
        String pageText = skippedFetch ? "" : fetch(spec);
        HkEngine engine = new HkEngine();
        engine.prepare(rule, spec.url, page, "home", pageText);
        engine.evalPreRule();
        HomeOutcome outcome = new HomeOutcome();
        String sizeKb = "约 " + (pageText.length() / 1024) + "KB";
        if (rule.isJsFindRule()) {
            outcome.cards = engine.evalFindRule(rule.getFindRule());
            outcome.diag = (skippedFetch ? "首页地址 " + spec.url + " 不抓页，" : "页面 " + sizeKb + "，")
                    + "规则返回 " + outcome.cards.size() + " 条"
                    + (engine.wasResultSet() ? "" : "（规则未调用 setResult）");
        } else {
            HkParser.ParseOutcome parsed = HkParser.parseHomeListDetailed(pageText, rule.getFindRule(), spec.url, engine, false);
            outcome.cards = parsed.cards;
            outcome.diag = (skippedFetch ? "首页地址 " + spec.url + " 不抓页，" : "页面 " + sizeKb + "，")
                    + "列表选择器命中 " + parsed.matched + " 项，得到 "
                    + outcome.cards.size() + " 张卡片"
                    + (skippedFetch ? "（选择器无页面可解析）"
                    : parsed.matched == 0 ? "（规则未命中页面结构）"
                    : outcome.cards.isEmpty() ? "（命中了但卡片都不可用，缺标题或链接）" : "");
        }
        return outcome;
    }

    /**
     * 搜索（对应报告 §7.1 的 search()，见 docs/miniapp-search.md）。
     * 流程与 homeDetailed 同款：校验 → 拼搜索请求 → OkHttp 抓页 → preRule →
     * searchFind 是 js: 走 Rhino，否则走 {@link HkParser#parseSearchList}。
     * 规则不可搜索（search_url/searchFind 任一为空）时返回空列表，不要假数据；
     * 关键词为空、请求/解析失败时抛 HkException（调用方显示错误态）。
     * 调用方必须在后台线程跑。返回原始搜索卡片，筛选由 HkListHelper.pickCards 做。
     */
    public static List<HkCard> search(HkRule rule, String keyword, int page) throws HkEngine.HkException {
        if (rule == null) throw new HkEngine.HkException("规则为空");
        String err = rule.validate();
        if (err != null) throw new HkEngine.HkException(err);
        if (TextUtils.isEmpty(keyword) || keyword.trim().isEmpty()) {
            throw new HkEngine.HkException("搜索关键词不能为空");
        }
        // E() 为假（没有搜索规则）时返回空列表，不要假数据（报告 search() 同款）。
        if (!rule.isSearchable()) return new ArrayList<>();
        RequestSpec spec = buildSearchRequest(rule, keyword.trim(), page);
        // 与 homeDetailed 同款 guard：搜索地址不是 http/https 时不抓页，JS 在空页上跑，
        // 选择器得 0 条卡片，不抛 OkHttp scheme 错。
        boolean skippedFetch = !isHttpUrl(spec.url);
        String pageText = skippedFetch ? "" : fetch(spec);
        HkEngine engine = new HkEngine();
        engine.prepare(rule, spec.url, page, "search", pageText);
        engine.evalPreRule();
        if (HkParser.isJsRule(rule.getSearchFind())) {
            return engine.evalFindRule(rule.getSearchFind());
        }
        return HkParser.parseSearchList(pageText, rule.getSearchFind(), spec.url, engine);
    }

    /**
     * 拼搜索请求。模板是规则 search_url：关键词占位是 **（官方文档；冲突时用 %%），
     * fykey 是任务描述里的写法、真实规则里没有，当无害别名一起换；页码是 fypage。
     * 修饰符与首页同款（;get/;post/;charset/;{headers JSON}）；POST 时问号后的
     * 参数进 body（官方“POST传参和get一样，真正请求时会自动转到body里面”）。
     * JsonBody 的 JSON POST 本步不支持（见任务文档“已知缺口”）。
     */
    public static RequestSpec buildSearchRequest(HkRule rule, String keyword, int page) throws HkEngine.HkException {
        String template = rule.getSearchUrl();
        if (TextUtils.isEmpty(template)) throw new HkEngine.HkException("规则缺少 search_url");
        // ？？ 转回 ?（官方 POST 一节：链接里有问号时用两个中文问号代替）。
        template = template.replace("？？", "?");
        UrlModifiers mods = splitUrlModifiers(template);
        String url = mods.url;

        // [firstPage=N] 与 fypage（含 @...@ 运算）与首页同款。
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
        url = replacePagePlaceholders(url, effectivePage);

        // 关键词先 URL 编码（用规则 charset，默认 UTF-8）再替换占位符。
        String encoded = encodeKeyword(keyword, mods.charset);
        url = url.replace("**", encoded).replace("%%", encoded).replace("fykey", encoded);

        RequestSpec spec = new RequestSpec();
        spec.method = mods.method;
        spec.charset = mods.charset;
        Map<String, String> headers = new HashMap<>();
        parseFirstHeader(rule.getFirstHeader(), headers);
        if (!TextUtils.isEmpty(rule.getUa())) headers.put("User-Agent", rule.getUa());
        headers.putAll(mods.headers);
        spec.headers = headers;
        if ("POST".equals(mods.method)) {
            int q = url.indexOf('?');
            if (q >= 0) {
                spec.body = url.substring(q + 1);
                url = url.substring(0, q);
            } else {
                spec.body = "";
            }
        }
        spec.url = url;
        return spec;
    }

    /** 关键词 URL 编码（URLEncoder：空格编成 +，query/form 都适用）；编码失败回落原文。 */
    private static String encodeKeyword(String keyword, String charset) {
        String text = keyword == null ? "" : keyword;
        try {
            return URLEncoder.encode(text, charsetFor(charset).name());
        } catch (Exception e) {
            try {
                return URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                return text;
            }
        }
    }

    /** URL 模板里 ; 修饰符的拆分结果：干净 URL + 方法 + 编码 + URL 内 headers。 */
    private static class UrlModifiers {
        String url = "";
        String method = "GET";
        String charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
    }

    /**
     * 拆 URL 模板的 ; 修饰符（首页与搜索共用；从 buildHomeRequest 原样抽出，
     * 行为不变）：get/post（大小写不敏感）、编码（UTF-8/GBK/GB2312/GB18030）、
     * {headers JSON}。引号内保护，JSON 里的 ; 不切；修饰符还没开始前的 ;
     * 是 URL 本身的一部分，拼回去。
     */
    private static UrlModifiers splitUrlModifiers(String template) {
        UrlModifiers mods = new UrlModifiers();
        List<String> segments = HkParser.splitOutsideQuotes(template, ";");
        StringBuilder urlPart = new StringBuilder(segments.get(0));
        boolean modifiersStarted = false;
        for (int i = 1; i < segments.size(); i++) {
            String seg = segments.get(i).trim();
            if (seg.equalsIgnoreCase("get")) {
                mods.method = "GET";
                modifiersStarted = true;
                continue;
            }
            if (seg.equalsIgnoreCase("post")) {
                mods.method = "POST";
                modifiersStarted = true;
                continue;
            }
            if (isCharset(seg)) {
                mods.charset = seg.toUpperCase();
                modifiersStarted = true;
                continue;
            }
            if (seg.startsWith("{")) {
                String json = seg;
                while (!bracesBalanced(json) && i + 1 < segments.size()) {
                    json += ";" + segments.get(++i);
                }
                parseJsonHeaders(json, mods.headers);
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
        mods.url = urlPart.toString();
        return mods;
    }

    /**
     * 详情（对应 f22.detail 的重写版，见 docs/miniapp-detail.md）。
     * 流程与 home() 同款：校验 → 抓详情页 → preRule → detail_find_rule 是 js:
     * 走 Rhino，否则走选择器解析。主规则出 0 张卡片且 chapter_find/movie_find
     * 非空时，用那条对同一页再跑一次（小说/漫画类备选，语义见任务文档）。
     * 调用方必须在后台线程跑。返回原始详情卡片（信息卡/线路头/选集都在里面），
     * 切分由 HkDetailHelper.split 做。
     */
    public static List<HkCard> detail(HkRule rule, String detailUrl) throws HkEngine.HkException {
        if (rule == null) throw new HkEngine.HkException("规则为空");
        String err = rule.validate();
        if (err != null) throw new HkEngine.HkException(err);
        if (TextUtils.isEmpty(rule.getDetailFindRule())) {
            throw new HkEngine.HkException("规则缺少 detail_find_rule");
        }
        String url = stripLazyMarkers(detailUrl);
        url = HkParser.resolveUrl(rule.getUrl(), url);
        if (TextUtils.isEmpty(url)) throw new HkEngine.HkException("详情地址为空");
        RequestSpec spec = buildDetailRequest(rule, url);
        // 与 homeDetailed 同款 guard：详情地址不是 http/https 时不抓页，JS 在空页上跑，
        // 选择器得 0 张卡片（详情页显示“暂无选集”），不抛 OkHttp scheme 错。
        String pageText = isHttpUrl(spec.url) ? fetch(spec) : "";
        List<HkCard> cards = evalDetailRule(rule, rule.getDetailFindRule(), spec.url, pageText);
        if (cards.isEmpty()) {
            String alt = !TextUtils.isEmpty(rule.getChapterFind()) ? rule.getChapterFind() : rule.getMovieFind();
            if (!TextUtils.isEmpty(alt)) {
                cards = evalDetailRule(rule, alt, spec.url, pageText);
            }
        }
        return cards;
    }

    /** 对一页详情正文跑一条详情规则（JS 或选择器）；与 home() 的执行顺序一致。 */
    private static List<HkCard> evalDetailRule(HkRule rule, String detailRule, String pageUrl, String pageText) throws HkEngine.HkException {
        HkEngine engine = new HkEngine();
        engine.prepare(rule, pageUrl, 1, "detail", pageText);
        engine.evalPreRule();
        if (HkParser.isJsRule(detailRule)) {
            return engine.evalFindRule(detailRule);
        }
        return HkParser.parseDetailList(pageText, detailRule, pageUrl, engine);
    }

    /** 懒加载播放解析结果：最终播放地址 + 请求头；url 为空表示未解析出地址。 */
    public static class PlayOutcome {
        public String url = "";
        public Map<String, String> headers = new HashMap<>();

        public boolean isEmpty() {
            return TextUtils.isEmpty(url);
        }
    }

    /**
     * 懒加载播放解析（对应 f22.t 的重写版，见 docs/miniapp-playback.md）。
     * 流程（报告里可读的 f/b/u 链）：在第一个 @lazyRule=（或 @rule=）处切分 →
     * 规则部分过 e() 转义 → .js:/js: 走 Rhino（input 是选集页地址），否则抓页
     * 后走选择器 → 结果再分发（递归懒链 / 多线路 JSON / 直链）。
     * 调用方必须在后台线程跑。返回空地址表示未解析出播放地址，调用方弹提示、
     * 不进播放器。
     */
    public static PlayOutcome play(HkRule rule, String episodeUrl) throws HkEngine.HkException {
        if (rule == null) throw new HkEngine.HkException("规则为空");
        String err = rule.validate();
        if (err != null) throw new HkEngine.HkException(err);
        if (TextUtils.isEmpty(episodeUrl)) return new PlayOutcome();
        return playResolved(rule, episodeUrl.trim(), 0);
    }

    /** 一级懒解析：切分选集地址与懒规则，求值后把结果交给 dispatchResolved。 */
    private static PlayOutcome playResolved(HkRule rule, String raw, int depth) throws HkEngine.HkException {
        PlayOutcome empty = new PlayOutcome();
        int at = raw.indexOf("@lazyRule=");
        int markerLen = "@lazyRule=".length();
        if (at < 0) {
            at = raw.indexOf("@rule=");
            markerLen = "@rule=".length();
        }
        String pagePart = at >= 0 ? raw.substring(0, at).trim() : raw.trim();
        String lazyPart = at >= 0 ? raw.substring(at + markerLen).trim() : "";
        // URL 部分的 #...# 标记先剥掉（b() 在递归前对 URL 部分做的事）。
        pagePart = stripHashMarkers(pagePart);
        String pageUrl = HkParser.resolveUrl(rule.getUrl(), pagePart);
        if (TextUtils.isEmpty(pageUrl)) return empty;
        if (TextUtils.isEmpty(lazyPart)) {
            // 直链：归一化（;{headers} 后缀 + # 标记），Referer 默认补选集页地址。
            return normalizeDirect(pageUrl, pageUrl);
        }
        // 规则部分过 e() 转义，再剥尾部 ;now（“立即执行”标记，不是 JS）。
        String lazy = stripNowSuffix(unescapeLazy(lazyPart));
        String resolved;
        if (lazy.startsWith(".js:") || lazy.startsWith("js:")) {
            String js = lazy.startsWith(".js:") ? lazy.substring(4) : lazy.substring(3);
            if (TextUtils.isEmpty(js.trim())) return empty;
            HkEngine engine = new HkEngine();
            engine.prepare(rule, pageUrl, 1, "play", "");
            engine.evalPreRule();
            resolved = engine.evalExpression(js, pageUrl);
        } else {
            RequestSpec spec = buildDetailRequest(rule, pageUrl);
            String pageText = fetch(spec);
            HkEngine engine = new HkEngine();
            engine.prepare(rule, pageUrl, 1, "play", pageText);
            engine.evalPreRule();
            resolved = HkParser.extract(Jsoup.parse(pageText, pageUrl), lazy, true, pageUrl, engine);
        }
        return dispatchResolved(rule, resolved == null ? "" : resolved.trim(), pageUrl, depth);
    }

    /** 对懒求值的结果再分发（对应 f22.b）：递归懒链 / 多线路 JSON / 动作 URL / 直链。 */
    private static PlayOutcome dispatchResolved(HkRule rule, String s, String referer, int depth) throws HkEngine.HkException {
        PlayOutcome empty = new PlayOutcome();
        if (TextUtils.isEmpty(s)) return empty;
        String text = s.trim();
        if (text.contains("@lazyRule=") || text.contains("@rule=")) {
            if (depth >= 3) {
                Log.w(TAG, "lazy chain too deep rule=" + rule.getTitle());
                return empty;
            }
            return playResolved(rule, text, depth + 1);
        }
        if (text.startsWith("{")) {
            try {
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                if (obj.has("urls") && obj.get("urls").isJsonArray()) {
                    return outcomeFromMultiLine(obj, referer);
                }
            } catch (Exception ignored) {
            }
            // { 开头但不是多线路 JSON：是规则结果对象，不是可播地址，直接丢掉。
            Log.w(TAG, "lazy result is a JSON object without urls, dropped");
            return empty;
        }
        if (text.startsWith("video://")) {
            return normalizeDirect(text.substring("video://".length()), referer);
        }
        if (text.startsWith("pics://") || text.startsWith("webRule://")) {
            Log.w(TAG, "unsupported lazy result prefix: " + text.substring(0, Math.min(text.length(), 16)));
            return empty;
        }
        return normalizeDirect(text, referer);
    }

    /** 多线路 JSON（{urls, names, headers, ...}）：本步只取首条；names/subtitle/danmu/lyric 不接（第 7 步）。 */
    private static PlayOutcome outcomeFromMultiLine(JsonObject obj, String referer) {
        PlayOutcome out = new PlayOutcome();
        JsonArray urls = obj.getAsJsonArray("urls");
        if (urls == null || urls.size() == 0) return out;
        String first = urls.get(0).isJsonPrimitive() ? urls.get(0).getAsString() : "";
        Map<String, String> headers = new HashMap<>();
        if (obj.has("headers")) {
            JsonElement h = obj.get("headers");
            if (h.isJsonArray() && h.getAsJsonArray().size() == urls.size()) {
                // 与 urls 下标一一对应：取首条的头。
                mergeJsonHeaders(h.getAsJsonArray().get(0), headers);
            } else {
                // 数量对不上：合并全部（与报告 s() 的口径一致）。
                mergeJsonHeaders(h, headers);
            }
        }
        PlayOutcome direct = normalizeDirect(first, referer);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!hasHeaderIgnoreCase(direct.headers, e.getKey())) direct.headers.put(e.getKey(), e.getValue());
        }
        return direct;
    }

    /** 合并 JSON 形式的头：对象直接取；数组逐项取（对象项或 "Key@Value" 字符串项，值过 e() 转义）。 */
    private static void mergeJsonHeaders(JsonElement h, Map<String, String> out) {
        if (h == null) return;
        if (h.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : h.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
            }
        } else if (h.isJsonArray()) {
            for (JsonElement el : h.getAsJsonArray()) mergeJsonHeaders(el, out);
        } else if (h.isJsonPrimitive()) {
            String s = h.getAsString();
            int at = s.indexOf('@');
            if (at > 0) out.put(s.substring(0, at).trim(), unescapeLazy(s.substring(at + 1).trim()));
        }
    }

    /** 直链归一：剥 ;{headers} 后缀（头解析出来）与 # 标记；Referer 默认补 referer。 */
    private static PlayOutcome normalizeDirect(String directUrl, String referer) {
        PlayOutcome out = new PlayOutcome();
        String url = directUrl == null ? "" : directUrl.trim();
        Map<String, String> headers = new HashMap<>();
        int semi = url.indexOf(';');
        if (semi >= 0) {
            String suffix = url.substring(semi + 1).trim();
            url = url.substring(0, semi).trim();
            if (suffix.startsWith("{")) parseJsonHeaders(suffix, headers);
        }
        url = stripHashMarkers(url);
        out.url = url;
        if (!hasHeaderIgnoreCase(headers, "Referer") && !TextUtils.isEmpty(referer)
                && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            headers.put("Referer", referer);
        }
        out.headers = headers;
        return out;
    }

    /** 从第一个 # 起截断（#...# 是嗅探标记，不是地址的一部分）。 */
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

    /** 请求头里是否已有该键（大小写不敏感，Referer/referer 同算）。 */
    private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String key) {
        if (headers == null || TextUtils.isEmpty(key)) return false;
        for (String k : headers.keySet()) if (key.equalsIgnoreCase(k)) return true;
        return false;
    }

    /** 详情请求：URL 来自卡片（无 fy 占位符），请求头与首页同款（firstHeader + ua），编码默认 UTF-8。 */
    private static RequestSpec buildDetailRequest(HkRule rule, String url) {
        RequestSpec spec = new RequestSpec();
        spec.url = url;
        spec.charset = "UTF-8";
        Map<String, String> headers = new HashMap<>();
        parseFirstHeader(rule.getFirstHeader(), headers);
        if (!TextUtils.isEmpty(rule.getUa())) headers.put("User-Agent", rule.getUa());
        spec.headers = headers;
        return spec;
    }

    /** 去掉详情地址里的二级解析标记（@lazyRule= / @rule= 后的部分只在第 5 步求值，取数时只要干净的页面地址）。 */
    private static String stripLazyMarkers(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        int at = u.indexOf("@lazyRule=");
        if (at >= 0) u = u.substring(0, at);
        at = u.indexOf("@rule=");
        if (at >= 0) u = u.substring(0, at);
        return u.trim();
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

        // 1. 拆 ; 修饰符（与搜索路径共用 splitUrlModifiers，行为与原来一致）。
        UrlModifiers mods = splitUrlModifiers(template);
        if ("POST".equals(mods.method)) {
            throw new HkEngine.HkException("POST 请求暂不支持（第 1 步仅实现 GET）");
        }
        String url = mods.url;
        String charset = mods.charset;
        Map<String, String> urlHeaders = mods.headers;

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

        // 4. fyAll / fyclass / fyarea / fyyear / fysort。官方文档：用 fyAll 时，
        // 分类、年代和地区替换词都会去替换 fyAll，有 fyAll 就不能有别的替换词。
        // 所以含 fyAll 的 URL 只换 fyAll（分类值 + 地区/年份/排序首值拼接），
        // 跳过下面单独的 fyclass/fyarea/fyyear/fysort 替换。
        if (url.contains("fyAll")) {
            String fyAllValue;
            if (urlStyle) {
                fyAllValue = TextUtils.isEmpty(className) ? "" : className;
            } else {
                fyAllValue = (TextUtils.isEmpty(classValue) ? "" : classValue)
                        + firstId(rule.getAreaUrl()) + firstId(rule.getYearUrl()) + firstId(rule.getSortUrl());
            }
            url = url.replace("fyAll", fyAllValue);
        } else {
            if (urlStyle) {
                url = url.replace("fyclass", TextUtils.isEmpty(className) ? "" : className);
            } else {
                url = url.replace("fyclass", TextUtils.isEmpty(classValue) ? "" : classValue);
            }
            url = url.replace("fyarea", firstId(rule.getAreaUrl()));
            url = url.replace("fyyear", firstId(rule.getYearUrl()));
            url = url.replace("fysort", firstId(rule.getSortUrl()));
        }

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

    /** 只有 http/https 才走 OkHttp：hiker://empty 等规则内地址不抓页（见 docs/miniapp-hk-fixes.md）。 */
    static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 同步抓一页，按指定编码解码；失败抛 HkException（带 URL 与原因）。 */
    static String fetch(RequestSpec spec) throws HkEngine.HkException {
        OkHttpClient client = OkHttp.client();
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
