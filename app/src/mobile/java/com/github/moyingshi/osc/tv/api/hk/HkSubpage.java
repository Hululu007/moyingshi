package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * hiker://page/ 子页面展开，对应魔影小程序的
 * HkSiteAdapter.expandPage（见逆向报告 §7.1：本体反编译失败，527 条指令被跳过，
 * 只有子页面编解码逻辑 subPageId/subPageRealUrl/subPageTitle 是可读的）。
 * <p>
 * 按公开规则语义 + 真实规则反推的重写，不声称复刻魔影：
 * <ul>
 *   <li>子页面定义在规则 JSON 的 {@code pages} 字段里（JSON 字符串数组，每页
 *       {@code {name, path, rule, col_type}}）；{@code hiker://page/<名>} 按
 *       {@code name} 或 {@code path} 匹配。</li>
 *   <li>JS 里 {@code fetch('hiker://page/<名>')} 返回页面对象的 JSON 字符串
 *       （含 {@code rule} 字段），所以 {@code JSON.parse(...).rule} 能 eval
 *       （西瓜影院的 find_rule 就是这么写的；本类的 {@code pageJson} 已随 5e 删除，
 *       JS 侧 fetch 由 {@code HkJsRuntime.handlePageRequest} 承接）。</li>
 *   <li>JS 里 {@code require('hiker://page/<名>')} 由 {@code HkJsRuntime} 内部处理，
 *       不走本类（5e 删除本类的 {@code require}/{@code requireOne}：唯一消费方是已死的
 *       require 链；{@code getExports}/{@code jsValueFromCards} 已随 HkEngine 适配段瘦身删除）。</li>
 *   <li>子页面卡片点开（url 是 {@code hiker://page/...}）走 {@link #expand}，
 *       经 {@link com.github.moyingshi.osc.tv.ui.hk.HkListActivity#openSubpage} 开新列表页（5e）；
 *       选集地址是子页面时 {@link HkRouter#play} 的子页面支先展开再找可播地址（5e）。</li>
 * </ul>
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。调用方必须在后台线程跑（网络同步 +
 * Rhino 执行）。子页面不存在、规则为空时抛 {@link HkEngine.HkException}（带页名），
 * 不崩、不进空白页。
 */
public final class HkSubpage {

    private static final String TAG = "HkSubpage";

    /** 子页面地址前缀。 */
    public static final String PREFIX = "hiker://page/";

    private HkSubpage() {
    }

    /** 是不是子页面地址（hiker://page/ 开头，大小写不敏感）。 */
    public static boolean isSubpageUrl(String url) {
        return !TextUtils.isEmpty(url) && url.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /** 拆出来的子页面地址：页名列表（| 分隔）+ query 参数。不是子页面地址返回 null。 */
    public static class Parsed {
        public List<String> names = new ArrayList<>();
        public Map<String, String> query = new HashMap<>();
    }

    /**
     * 拆子页面地址：{@code hiker://page/<名>[|...][?query][#...]}。
     * {@code ？？} 是海阔转义的 {@code ?}，先还原再拆；query 值按 UTF-8 解码。
     */
    public static Parsed parse(String pageUrl) {
        if (!isSubpageUrl(pageUrl)) return null;
        String s = pageUrl.trim().replace("？？", "?");
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        String rest = s.substring(PREFIX.length());
        Parsed parsed = new Parsed();
        int q = rest.indexOf('?');
        String namesPart = q >= 0 ? rest.substring(0, q) : rest;
        if (q >= 0) parsed.query = parseQuery(rest.substring(q + 1));
        for (String name : namesPart.split("\\|")) {
            String n = name.trim();
            if (!n.isEmpty()) parsed.names.add(n);
        }
        return parsed;
    }

    /** 取子页面地址的第一页名（| 多页时取第一页）；不是子页面地址返回 ""。 */
    public static String subpageName(String pageUrl) {
        Parsed parsed = parse(pageUrl);
        return parsed == null || parsed.names.isEmpty() ? "" : parsed.names.get(0);
    }

    /** 解析 query 字符串（k=v，& 分隔，首个 = 拆分，UTF-8 解码，解码失败留原文）。 */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (TextUtils.isEmpty(query)) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } else {
                    out.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {
                if (eq >= 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
                else out.put(pair, "");
            }
        }
        return out;
    }

    /** 规则 pages 全表（JSON 数组字符串；空/非法 → 空表，不抛错）。 */
    static List<JsonObject> pagesOf(HkRule rule) {
        List<JsonObject> out = new ArrayList<>();
        if (rule == null) return out;
        String pages = rule.getPages();
        if (TextUtils.isEmpty(pages)) return out;
        try {
            JsonElement parsed = JsonParser.parseString(pages.trim());
            if (!parsed.isJsonArray()) return out;
            for (JsonElement el : parsed.getAsJsonArray()) {
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
            }
        } catch (Exception e) {
            Log.w(TAG, "rule pages parse failed: " + rule.getTitle(), e);
        }
        return out;
    }

    /** 在规则 pages 里找一页：先 name/path 精确匹配，再大小写不敏感；找不到返回 null。 */
    public static JsonObject findPage(HkRule rule, String name) {
        if (rule == null || TextUtils.isEmpty(name)) return null;
        List<JsonObject> pages = pagesOf(rule);
        String target = name.trim();
        for (JsonObject page : pages) {
            if (target.equals(pageString(page, "name")) || target.equals(pageString(page, "path"))) return page;
        }
        for (JsonObject page : pages) {
            if (target.equalsIgnoreCase(pageString(page, "name")) || target.equalsIgnoreCase(pageString(page, "path"))) return page;
        }
        return null;
    }

    /** 取页面对象里的字符串字段（缺失/非字符串返回 ""）。 */
    private static String pageString(JsonObject page, String key) {
        if (page == null || !page.has(key)) return "";
        try {
            JsonElement el = page.get(key);
            return el.isJsonPrimitive() ? el.getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String titleOf(HkRule rule) {
        return rule == null ? "" : rule.getTitle();
    }

    /**
     * 子页面列表页定样式用（5e）：读页面对象的 {@code col_type} 字段，空则回落规则 {@code col_type}。
     * 页不存在时回落规则 col_type（expand 会抛错，样式只在 expand 成功后用）。
     */
    public static String pageColType(HkRule rule, String pageUrl) {
        Parsed parsed = parse(pageUrl);
        String name = parsed == null || parsed.names.isEmpty() ? "" : parsed.names.get(0);
        JsonObject page = TextUtils.isEmpty(name) ? null : findPage(rule, name);
        String colType = page == null ? "" : pageString(page, "col_type");
        if (TextUtils.isEmpty(colType)) colType = rule == null ? "" : rule.getColType();
        return colType == null ? "" : colType;
    }

    /**
     * 卡片点开 / 详情：展开一页子页面，执行它的 rule，返回卡片列表。
     * 子引擎里跑（pageUrl 当 MY_URL，pageType "page"，preRule 先跑——子页面
     * 要读 preRule 置的持久变量，如阅动漫的 myCollection）；importParam 取
     * 地址里的 query 参数。
     */
    public static List<HkCard> expand(HkRule rule, String pageUrl) throws HkEngine.HkException {
        Parsed parsed = parse(pageUrl);
        if (parsed == null || parsed.names.isEmpty()) {
            throw new HkEngine.HkException("不是子页面地址：" + pageUrl);
        }
        String name = parsed.names.get(0);
        JsonObject page = findPage(rule, name);
        if (page == null) {
            throw new HkEngine.HkException("子页面不存在：" + name + "（规则 " + titleOf(rule) + " 的 pages 里没有这一页）");
        }
        String pageRule = pageString(page, "rule");
        if (TextUtils.isEmpty(pageRule)) {
            throw new HkEngine.HkException("子页面没有可执行的 rule：" + name);
        }
        HkEngine engine = new HkEngine();
        engine.prepare(rule, pageUrl, 1, "page", "");
        engine.setImportParams(parsed.query);
        engine.evalPreRule();
        List<HkCard> cards = engine.evalFindRule(pageRule);
        Log.i(TAG, "expand page=" + name + " cards=" + cards.size());
        return cards;
    }

    /**
     * 在展开卡片里找第一条可播地址（http(s) / video:// / 含 @lazyRule=/@rule= 的
     * 懒加载地址），找不到返回 ""（调用方报明确错）。
     */
    public static String findPlayableUrl(List<HkCard> cards) {
        if (cards == null) return "";
        for (HkCard card : cards) {
            if (card == null) continue;
            String url = card.getUrl() == null ? "" : card.getUrl().trim();
            if (url.isEmpty()) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")
                    || lower.startsWith("video://")
                    || url.contains("@lazyRule=") || url.contains("@rule=")) {
                return url;
            }
        }
        return "";
    }
}
