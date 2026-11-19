package com.github.moyingshi.osc.tv.api.hk;

import android.text.Html;
import android.text.TextUtils;

import com.github.moyingshi.osc.tv.bean.Style;
import com.github.moyingshi.osc.tv.bean.Vod;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列表卡片模型，对应魔影小程序的 uy1（见逆向报告 §7.12）。
 * <p>
 * 字段名按报告里的 @SerializedName 原样保留；get/set 用可读名字。
 * pic 在 JS 规则里也写作 img（setResult 时两者都认）。
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkCard {

    @SerializedName("title")
    private String title;
    @SerializedName("url")
    private String url;
    @SerializedName("pic")
    private String pic;
    @SerializedName("desc")
    private String desc;
    @SerializedName("sel")
    private boolean sel;
    @SerializedName("colType")
    private String colType = "";
    @SerializedName("extra")
    private String extra = "";
    @SerializedName("content")
    private String content = "";
    @SerializedName("line")
    private String line = "";
    @SerializedName("findRule")
    private String findRule = "";
    @SerializedName("group")
    private String group = "";
    @SerializedName("pages")
    private String pages = "";
    @SerializedName("preRule")
    private String preRule = "";
    @SerializedName("rule")
    private String rule = "";

    public HkCard() {
    }

    public HkCard(String title, String url, String pic, String desc) {
        this.title = title;
        this.url = url;
        this.pic = pic;
        this.desc = desc;
    }

    public String getTitle() { return title == null ? "" : title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrl() { return url == null ? "" : url; }
    public void setUrl(String url) { this.url = url; }
    public String getPic() { return pic == null ? "" : pic; }
    public void setPic(String pic) { this.pic = pic; }
    public String getDesc() { return desc == null ? "" : desc; }
    public void setDesc(String desc) { this.desc = desc; }
    public boolean isSelected() { return sel; }
    public void setSelected(boolean sel) { this.sel = sel; }
    public String getColType() { return colType == null ? "" : colType; }
    public void setColType(String colType) { this.colType = colType; }
    public String getExtra() { return extra == null ? "" : extra; }
    public void setExtra(String extra) { this.extra = extra == null ? "" : extra; }
    public String getContent() { return content == null ? "" : content; }
    public void setContent(String content) { this.content = content; }
    public String getLine() { return line == null ? "" : line; }
    public void setLine(String line) { this.line = line; }
    public String getFindRule() { return findRule == null ? "" : findRule; }
    public void setFindRule(String findRule) { this.findRule = findRule; }
    public String getGroup() { return group == null ? "" : group; }
    public void setGroup(String group) { this.group = group; }
    public String getPages() { return pages == null ? "" : pages; }
    public void setPages(String pages) { this.pages = pages; }
    public String getPreRule() { return preRule == null ? "" : preRule; }
    public void setPreRule(String preRule) { this.preRule = preRule; }
    public String getRule() { return rule == null ? "" : rule; }
    public void setRule(String rule) { this.rule = rule; }

    /**
     * HkItem → HkCard 逐字段转换（5c：自 HkExecutor.toCards 逐字移入；HkExecutor
     * 内的私有副本留待 5e 随 HkExecutor 一并删除）。
     */
    public static java.util.List<HkCard> fromItems(java.util.List<HkItem> items) {
        java.util.List<HkCard> out = new java.util.ArrayList<>();
        if (items == null) return out;
        for (HkItem item : items) {
            if (item == null) continue;
            HkCard card = new HkCard(item.getTitle(), item.getUrl(), item.getPic(), item.getDesc());
            card.setColType(item.getColType());
            out.add(card);
        }
        return out;
    }

    /**
     * SearchResult → HkCard 逐字段转换（5c：自 HkExecutor.searchResultsToCards
     * 逐字移入；HkExecutor 内的私有副本留待 5e 随 HkExecutor 一并删除）。
     */
    public static java.util.List<HkCard> fromSearchResults(java.util.List<SearchResult> results) {
        java.util.List<HkCard> out = new java.util.ArrayList<>();
        if (results == null) return out;
        for (SearchResult sr : results) {
            if (sr == null) continue;
            HkCard card = new HkCard(sr.getTitle(), sr.getUrl(), sr.getImg(), sr.getDesc());
            out.add(card);
        }
        return out;
    }

    /**
     * HkDetailItem → HkCard 逐字段转换（5d：自 HkExecutor.detailItemsToCards
     * 逐字移入；1:1 保序，不筛不改，调用方按下标/同一性回找原条目）。
     */
    public static java.util.List<HkCard> fromDetailItems(java.util.List<HkDetailItem> items) {
        java.util.List<HkCard> out = new java.util.ArrayList<>();
        if (items == null) return out;
        for (HkDetailItem item : items) {
            if (item == null) continue;
            HkCard card = new HkCard(item.getTitle(), item.getUrl(), item.getPic(), item.getDesc());
            card.setColType(item.getColType());
            out.add(card);
        }
        return out;
    }

    /** 是否可用卡片：标题和链接都不为空（解析时双空的卡片直接丢掉）。 */
    public boolean usable() {
        return !TextUtils.isEmpty(title) && !TextUtils.isEmpty(url);
    }

    /**
     * 从 extra（JSON 字符串）里取一个键，对应 uy1.e(String)：
     * 基本类型返回字符串值，对象/数组返回 JSON 文本，取不到或解析失败返回 ""。
     */
    public String extra(String key) {
        if (TextUtils.isEmpty(extra)) return "";
        try {
            JsonElement element = JsonParser.parseString(extra).getAsJsonObject().get(key);
            if (element == null || element.isJsonNull()) return "";
            return element.isJsonPrimitive() ? element.getAsString() : element.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "HkItem{title='" + getTitle() + "', url='" + getUrl()
                + "', pic='" + getPic() + "', desc='" + getDesc() + "'}";
    }
    /**
     * 卡片筛选（直译报告 §7.1 pickCards）：第一遍只留 colType 与规则 col_type
     * 一致的可用卡片；空了第二遍留可用且非动作 URL 的；还空第三遍留全部可用卡片。
     * 第四遍（第 7 步之三，西瓜影院）：前三遍都空时，留 URL 非空但标题为空的
     * {@code x5_webview_single} 卡片（站内 WebView 页，5c 已定点开进 CatWebActivity），当一张可点卡片；
     * 点开进站内 WebView（5c 已定，CatWebActivity 顶替，不做列表页内嵌 WebView）。
     */
    public static List<HkCard> pickCards(List<HkCard> list, HkRule rule) {
        List<HkCard> picked = new ArrayList<>();
        if (list == null || list.isEmpty()) return picked;
        String colType = rule == null ? "" : rule.getColType().trim();
        if (!colType.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && colType.equalsIgnoreCase(card.getColType().trim()) && card.usable()) {
                    picked.add(card);
                }
            }
        }
        if (picked.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && card.usable() && !HkRouter.isActionUrl(card.getUrl())) {
                    picked.add(card);
                }
            }
        }
        if (picked.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && card.usable()) {
                    picked.add(card);
                }
            }
        }
        if (picked.isEmpty()) {
            for (HkCard card : list) {
                if (card != null && "x5_webview_single".equalsIgnoreCase(card.getColType().trim())
                        && !TextUtils.isEmpty(card.getUrl().trim())) {
                    picked.add(card);
                }
            }
        }
        return picked;
    }

    /** 卡片转 Vod（直译报告 §7.1 toVods，去掉了魔影的 CACHE——详情页才按 URL 取卡片）。 */
    public static List<Vod> toVods(List<HkCard> list, String typeName) {
        List<Vod> vods = new ArrayList<>();
        if (list == null) return vods;
        for (HkCard card : list) {
            if (card == null) continue;
            String url = card.getUrl().trim();
            String title = card.getTitle().trim();
            if (url.isEmpty()) continue;
            if (title.isEmpty()) {
                // 只有 x5_webview_single 卡片允许标题为空（第 7 步之三第四遍筛出来的），
                // 回落标题“网页”；其他无标题卡片仍丢掉。
                if (!"x5_webview_single".equalsIgnoreCase(card.getColType().trim())) continue;
                title = "网页";
            }
            // rich_text / long_text 卡片的标题常带 HTML 标签，去掉再显示；其余类型原样。
            if (isRichText(card)) title = stripHtml(title);
            Vod vod = new Vod();
            vod.setId(url);
            vod.setName(title);
            vod.setPic(card.getPic());
            // rich_text / long_text 卡片的描述常带 HTML 标签，去掉再显示；其余类型原样
            //（remarks 与 content 取同一份 desc）。
            String desc = card.getDesc();
            if (isRichText(card)) desc = stripHtml(desc);
            vod.setRemarks(desc);
            vod.setContent(desc);
            vod.setTypeName(typeName == null ? "" : typeName);
            if ("x5_webview_single".equalsIgnoreCase(card.getColType().trim())) {
                if (X5_URLS.size() > MAX_X5_URLS) X5_URLS.clear();
                X5_URLS.add(url);
            }
            vods.add(vod);
        }
        return vods;
    }

    /** x5_webview_single 卡片的 URL 记表：点卡时要知道 colType，但 Vod 上没有该字段。 */
    private static final Set<String> X5_URLS = ConcurrentHashMap.newKeySet();
    private static final int MAX_X5_URLS = 500;

    /** 这条 URL 是不是 x5 网页卡（toVods 记表，点卡分支读）。 */
    public static boolean isX5Url(String url) {
        return url != null && X5_URLS.contains(url);
    }

    /**
     * 同一列表内相同 URL 的卡只留一张：去掉已在列表里的与页内重复的。
     * shown 为 null 按空处理；id 为空的不过滤（toVods 已滤掉空 URL）。
     */
    public static List<Vod> dedupeByUrl(List<Vod> shown, List<Vod> incoming) {
        if (incoming == null) return new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (shown != null) {
            for (Vod v : shown) {
                if (v != null && v.getId() != null) seen.add(v.getId());
            }
        }
        List<Vod> out = new ArrayList<>();
        for (Vod v : incoming) {
            if (v == null) continue;
            String id = v.getId();
            if (id == null || seen.add(id)) out.add(v);
        }
        return out;
    }

    /** 这张卡片是不是富文本/长文本行（标题与描述要去 HTML 标签再显示）。 */
    private static boolean isRichText(HkCard card) {
        String t = card.getColType().trim().toLowerCase(Locale.ROOT);
        return "rich_text".equals(t) || "long_text".equals(t);
    }

    /** 去掉文本里的 HTML 标签（与 HkDetailHelper.stripHtml 同款写法）。 */
    private static String stripHtml(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String out = Html.fromHtml(text.trim(), Html.FROM_HTML_MODE_LEGACY).toString();
        return out == null ? "" : out.trim();
    }
}