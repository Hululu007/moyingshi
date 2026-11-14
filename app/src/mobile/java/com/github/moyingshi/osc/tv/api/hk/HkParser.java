package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海阔选择器解析最小实现，对应魔影小程序的 b32（见逆向报告 §6：b32 无源码，
 * 按官方文档 + 社区 skill 的 selector_syntax.md / js_api.md 蒸馏重写）。
 * <p>
 * 支持的语法（字段格式：列表;标题;图片;描述;链接[;显示样式]）：
 * <ul>
 *   <li>{@code &&} 向下一层；{@code --} 排除第一个匹配；{@code ,n} 取第 n 个
 *       （0 起，负数倒数）；尾部 {@code Text}/{@code Html} 取文字/ inner HTML，
 *       其它尾部取属性（href/src/data-original 等 URL 属性自动补全）；</li>
 *   <li>{@code ||} 或（按顺序取第一个非空）；{@code +} 拼接（单引号包字面量，
 *       中文 ＋ 同样处理）；{@code .js:代码} 取到值后用 JS 再加工（input 是原值）；
 *       全段 {@code js:} 走 JS（见 {@link HkEngine}）；</li>
 *   <li>选择器本身是 jsoup 原生语法（#id/.class/tag/[attr=v] 等）；{@code *}
 *       表示“用本行自己的链接/文字”（二级列表链接继承）。</li>
 * </ul>
 * 已知的取舍（第 1 步先这样，待真实规则验证再收紧）：
 * <ul>
 *   <li>字段按 {@code ;} 切分（引号内保护），但 {@code .js:} 代码里的 {@code ;}
 *       语句分隔符切不开——.js: 请写表达式，别写多语句。</li>
 *   <li>尾部是属性名还是标签名，靠“元素有没有这个属性”判断（有属性取属性，
 *       没有当子标签取文字）。</li>
 *   <li>{@code +} 拼接只在“挨着引号”或“后一段含 {@code &&}”时切分，避免把
 *       CSS 的相邻兄弟选择器 {@code div+p} 误切。</li>
 *   <li>XPath 不支持（jsoup 没有 XPath 引擎，是后面的阶段）。</li>
 * </ul>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkParser {

    private static final String TAG = "HkParser";
    private static final Pattern INDEX_SUFFIX = Pattern.compile(",(-?\\d+)$");
    private static final String[] URL_ATTRS = {
            "href", "src", "data-original", "data-src", "data-url",
            "poster", "data-poster", "data-lazy-src", "data-echo"
    };

    /** find_rule 是否整段 JS（以 js: 开头），对应 b32.f()。 */
    public static boolean isJsRule(String rule) {
        return !TextUtils.isEmpty(rule) && rule.trim().startsWith("js:");
    }

    /** 去掉 js: 前缀，返回 JS 程序体。 */
    public static String jsBody(String rule) {
        if (TextUtils.isEmpty(rule)) return "";
        String text = rule.trim();
        return text.startsWith("js:") ? text.substring(3) : text;
    }

    /**
     * 跑一条规则的首页 find_rule（选择器模式），返回卡片列表。
     * 字段顺序：列表;标题;图片;描述;链接[;显示样式]。标题和链接双空的卡片丢掉。
     * baseUrl 用于补全相对链接/图片；engine 用于 .js: 后处理（可为 null，
     * 为 null 时 .js: 不跑、返回原值）。
     */
    public static List<HkCard> parseHomeList(String html, String findRule, String baseUrl, HkEngine engine) {
        List<HkCard> cards = new ArrayList<>();
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(findRule)) return cards;
        List<String> fields = splitOutsideQuotes(findRule, ";");
        if (fields.size() < 5) {
            Log.w(TAG, "find_rule 字段不足 5 个: " + findRule);
            return cards;
        }
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        Elements items = selectElements(doc, fields.get(0).trim());
        for (Element item : items) {
            String title = extract(item, fields.get(1), false, baseUrl, engine);
            String pic = extract(item, fields.get(2), true, baseUrl, engine);
            String desc = extract(item, fields.get(3), false, baseUrl, engine);
            String url = extract(item, fields.get(4), true, baseUrl, engine);
            HkCard card = new HkCard(title, url, pic, desc);
            if (fields.size() >= 6) {
                card.setColType(extract(item, fields.get(5), false, baseUrl, engine));
            }
            if (card.usable()) cards.add(card);
        }
        return cards;
    }

    /** pd：取单个值，URL 类属性默认自动补全。 */
    public static String parseDomSingle(String html, String sel, String baseUrl) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(sel)) return "";
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        return extract(doc, sel, true, baseUrl, null);
    }

    /** pdfh：取一个块，不自动补全；尾部 Text/Html 时取文字/inner HTML，否则取 outer HTML。 */
    public static String parseDomForHtml(String html, String sel) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(sel)) return "";
        Document doc = Jsoup.parse(html);
        String last = lastSegment(sel);
        if ("Text".equals(last) || "Html".equals(last)) {
            return extract(doc, sel, false, null, null);
        }
        Elements els = selectElements(doc, sel);
        return els.isEmpty() ? "" : els.first().outerHtml();
    }

    /** pdfa：取列表，返回字符串数组。尾部是不是“提取规格”按第一个匹配到的元素运行时判断（有该属性才按属性提，否则整条链当元素选、取文字），避免把 li/div 这类标签名误当成属性。 */
    public static String[] parseDomForArray(String html, String sel, String baseUrl) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(sel)) return new String[0];
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        List<String> segments = nonEmpty(splitOutsideQuotes(sel, "&&"));
        if (segments.isEmpty()) return new String[0];
        String last = segments.get(segments.size() - 1).trim();
        String lastCore = stripIndex(last);
        boolean tailCandidate = "Text".equals(lastCore) || "Html".equals(lastCore)
                || !containsSelectorChar(lastCore);
        List<String> values = new ArrayList<>();
        if (tailCandidate && segments.size() > 1) {
            String listSel = join(segments.subList(0, segments.size() - 1), "&&");
            Elements items = selectElements(doc, listSel);
            if (!items.isEmpty()) {
                Element first = items.first();
                if ("Text".equals(lastCore) || "Html".equals(lastCore) || first.hasAttr(lastCore)) {
                    for (Element el : items) values.add(extractOne(el, last, true, baseUrl));
                    return values.toArray(new String[0]);
                }
            }
            // 回落：最后一段是标签名（如 ul&&li），整条链当元素选，取每项文字。
            Elements all = selectElements(doc, sel);
            for (Element el : all) values.add(el.text());
            return values.toArray(new String[0]);
        }
        if (tailCandidate) {
            // 只有一个段且是 Text/Html：整篇文档算一项。
            values.add(extractOne(doc, last, true, baseUrl));
            return values.toArray(new String[0]);
        }
        Elements items = selectElements(doc, sel);
        for (Element el : items) values.add(el.text());
        return values.toArray(new String[0]);
    }

    /**
     * 对一个元素提一个字段的值：先剥 .js: 尾巴，再按 || 拆候选（第一个非空胜出），
     * 候选内按 + 拼接（引号包的是字面量，其余是选择器链）。
     */
    public static String extract(Element item, String field, boolean completeUrl, String baseUrl, HkEngine engine) {
        if (item == null || TextUtils.isEmpty(field)) return "";
        String trimmed = field.trim();
        if (trimmed.startsWith("js:")) {
            // 字段级 js:（整段 js: 的同款语义外推，待真实规则验证）：input 是本行 outer HTML。
            if (engine == null) return "";
            try {
                return engine.evalExpression(trimmed.substring(3), item.outerHtml());
            } catch (HkEngine.HkException e) {
                Log.w(TAG, "js: field failed: " + e.getMessage());
                return "";
            }
        }
        String jsTail = null;
        String head = trimmed;
        int jsAt = trimmed.indexOf(".js:");
        if (jsAt >= 0) {
            head = trimmed.substring(0, jsAt);
            jsTail = trimmed.substring(jsAt + 4);
        }
        String value = "";
        for (String alt : splitOutsideQuotes(head, "||")) {
            if (TextUtils.isEmpty(alt.trim())) continue;
            StringBuilder sb = new StringBuilder();
            for (String part : splitConcat(alt)) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (isQuoted(p)) sb.append(unquote(p));
                else sb.append(extractOne(item, p, completeUrl, baseUrl));
            }
            value = sb.toString().trim();
            if (!value.isEmpty()) break;
        }
        if (jsTail != null && !jsTail.trim().isEmpty()) {
            if (engine != null) {
                try {
                    value = engine.evalExpression(jsTail, value);
                } catch (HkEngine.HkException e) {
                    Log.w(TAG, ".js: failed: " + e.getMessage());
                }
            } else {
                Log.w(TAG, ".js: skipped (no engine)");
            }
        }
        return value == null ? "" : value;
    }

    /**
     * 单条选择器链提值（不含 || / + / .js:，那些在 extract() 层拆）。
     * 最后一段是提取规格（Text/Html/属性名），其余段是选择链；最后一段本身是
     * 选择器时，整条链都是选择，取第一项的文字。
     */
    static String extractOne(Element item, String sel, boolean completeUrl, String baseUrl) {
        if (item == null || TextUtils.isEmpty(sel)) return "";
        String text = sel.trim();
        if ("*".equals(text)) return inheritValue(item, completeUrl, baseUrl);
        List<String> segments = nonEmpty(splitOutsideQuotes(text, "&&"));
        if (segments.isEmpty()) return "";
        String lastRaw = segments.get(segments.size() - 1).trim();
        String lastCore = stripIndex(lastRaw);
        if ("Text".equals(lastCore) || "Html".equals(lastCore) || !containsSelectorChar(lastCore)) {
            Elements selected = selectSegments(item, segments.subList(0, segments.size() - 1));
            if (selected.isEmpty()) return "";
            Element el = selected.first();
            if ("Text".equals(lastCore)) return el.text();
            if ("Html".equals(lastCore)) return el.html();
            if (el.hasAttr(lastCore)) {
                String value = el.attr(lastCore);
                if (completeUrl && isUrlAttr(lastCore)) value = resolveUrl(baseUrl, value);
                return value;
            }
            // 没有这个属性：把最后一段当子标签再选一次取文字（如 div&&span）。
            Elements all = selectSegments(item, segments);
            return all.isEmpty() ? "" : all.first().text();
        }
        Elements selected = selectSegments(item, segments);
        return selected.isEmpty() ? "" : selected.first().text();
    }

    /** 选元素集合：|| 按顺序取第一个非空的候选。 */
    public static Elements selectElements(Element root, String chain) {
        if (root == null || TextUtils.isEmpty(chain)) return new Elements();
        for (String alt : splitOutsideQuotes(chain, "||")) {
            if (TextUtils.isEmpty(alt.trim())) continue;
            Elements found = selectSegments(root, nonEmpty(splitOutsideQuotes(alt, "&&")));
            if (!found.isEmpty()) return found;
        }
        return new Elements();
    }

    /** 按 && 链一段段往下选；每段支持 --（排除第一个）与 ,n（取第 n 个）。 */
    private static Elements selectSegments(Element root, List<String> segments) {
        Elements current = new Elements();
        current.add(root);
        for (String rawSeg : segments) {
            String seg = rawSeg.trim();
            if (seg.isEmpty()) continue;
            if ("Text".equals(seg) || "Html".equals(seg)) continue;
            boolean excludeFirst = false;
            if (seg.startsWith("--")) {
                excludeFirst = true;
                seg = seg.substring(2).trim();
            }
            int index = Integer.MIN_VALUE;
            Matcher im = INDEX_SUFFIX.matcher(seg);
            if (im.find()) {
                try {
                    index = Integer.parseInt(im.group(1));
                } catch (NumberFormatException ignored) {
                    index = Integer.MIN_VALUE;
                }
                seg = seg.substring(0, im.start()).trim();
            }
            if (seg.isEmpty()) continue;
            Elements next = new Elements();
            Set<Element> seen = new LinkedHashSet<>();
            for (Element el : current) {
                for (Element found : el.select(seg)) {
                    if (seen.add(found)) next.add(found);
                }
            }
            if (next.isEmpty()) {
                // 自身兜底：后代里没有，但当前元素自己就是这个选择器（如列表项本身就是 <a>）。
                for (Element el : current) {
                    if (el.is(seg) && seen.add(el)) next.add(el);
                }
            }
            if (excludeFirst && !next.isEmpty()) next.remove(0);
            if (index != Integer.MIN_VALUE) {
                int i = index < 0 ? next.size() + index : index;
                Elements one = new Elements();
                if (i >= 0 && i < next.size()) one.add(next.get(i));
                next = one;
            }
            current = next;
            if (current.isEmpty()) break;
        }
        return current;
    }

    /** *：链接字段取本行自己的 href（没有则取第一个后代 <a> 的 href），其它取文字。 */
    private static String inheritValue(Element item, boolean completeUrl, String baseUrl) {
        if (completeUrl) {
            if (item.hasAttr("href")) return resolveUrl(baseUrl, item.attr("href"));
            Elements a = item.select("a[href]");
            if (!a.isEmpty()) return resolveUrl(baseUrl, a.first().attr("href"));
            return "";
        }
        return item.text();
    }

    /** 相对地址按 baseUrl 补全；绝对地址、data:/blob:/file:/javascript: 原样返回。 */
    public static String resolveUrl(String baseUrl, String value) {
        if (TextUtils.isEmpty(value)) return "";
        String v = value.trim();
        if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("data:")
                || v.startsWith("blob:") || v.startsWith("file://") || v.startsWith("javascript:")) {
            return v;
        }
        if (v.startsWith("//") && !TextUtils.isEmpty(baseUrl)) {
            int schemeEnd = baseUrl.indexOf("://");
            if (schemeEnd > 0) return baseUrl.substring(0, schemeEnd) + ":" + v;
            return "https:" + v;
        }
        if (TextUtils.isEmpty(baseUrl)) return v;
        try {
            return new java.net.URL(new java.net.URL(baseUrl), v).toString();
        } catch (Exception e) {
            return v;
        }
    }

    private static boolean isUrlAttr(String name) {
        for (String attr : URL_ATTRS) if (attr.equals(name)) return true;
        return false;
    }

    /** 是否含选择器字符（# . [ ] 空格 > : * ~ + = (），含则最后一段是选择器不是属性名。 */
    private static boolean containsSelectorChar(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '#' || c == '.' || c == '[' || c == ']' || c == ' ' || c == '>'
                    || c == ':' || c == '*' || c == '~' || c == '+' || c == '=' || c == '(') {
                return true;
            }
        }
        return false;
    }

    private static String stripIndex(String seg) {
        Matcher m = INDEX_SUFFIX.matcher(seg.trim());
        return m.find() ? seg.trim().substring(0, m.start()).trim() : seg.trim();
    }

    private static String lastSegment(String sel) {
        List<String> segments = nonEmpty(splitOutsideQuotes(sel, "&&"));
        if (segments.isEmpty()) return "";
        return stripIndex(segments.get(segments.size() - 1).trim());
    }

    private static List<String> nonEmpty(List<String> parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (!TextUtils.isEmpty(p.trim())) out.add(p);
        return out;
    }

    private static String join(List<String> parts, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static boolean isQuoted(String p) {
        return p.length() >= 2 && ((p.startsWith("'") && p.endsWith("'"))
                || (p.startsWith("\"") && p.endsWith("\"")));
    }

    private static String unquote(String p) {
        return isQuoted(p) ? p.substring(1, p.length() - 1) : p;
    }

    /**
     * 引号外按分隔符切分（支持单/双引号与反斜杠转义；空段保留，字段位置不能丢）。
     * 中文 ＋ 在分隔符是 + 时同样认（只在引号外）。
     */
    static List<String> splitOutsideQuotes(String text, String delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && (inSingle || inDouble)) {
                cur.append(c).append(text.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                cur.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                cur.append(c);
                continue;
            }
            if (!inSingle && !inDouble && (text.startsWith(delimiter, i)
                    || ("+".equals(delimiter) && c == '＋'))) {
                parts.add(cur.toString());
                cur.setLength(0);
                if (text.startsWith(delimiter, i)) i += delimiter.length() - 1;
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    /**
     * + 拼接切分：只在“挨着引号”或“后一段含 &&”时切，避免把 CSS 的相邻兄弟
     * 选择器（div+p）误切。中文 ＋ 同样处理。
     */
    private static List<String> splitConcat(String alt) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < alt.length(); i++) {
            char c = alt.charAt(i);
            if (c == '\\' && i + 1 < alt.length() && (inSingle || inDouble)) {
                cur.append(c).append(alt.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                cur.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                cur.append(c);
                continue;
            }
            if (!inSingle && !inDouble && (c == '+' || c == '＋')) {
                int j = i + 1;
                while (j < alt.length() && alt.charAt(j) == ' ') j++;
                boolean nextIsQuote = j < alt.length() && (alt.charAt(j) == '\'' || alt.charAt(j) == '"');
                boolean restHasChain = false;
                if (!nextIsQuote) {
                    String rest = alt.substring(j);
                    int nextPlus = indexOfPlusOutsideQuotes(rest);
                    String chunk = nextPlus >= 0 ? rest.substring(0, nextPlus) : rest;
                    restHasChain = chunk.contains("&&");
                }
                if (nextIsQuote || restHasChain) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    private static int indexOfPlusOutsideQuotes(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (!inSingle && !inDouble && (c == '+' || c == '＋')) return i;
        }
        return -1;
    }
}
