package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.moyingshi.osc.tv.App;
import com.google.gson.annotations.SerializedName;

/**
 * 海阔规则模型，对应魔影小程序的 g22（见逆向报告 §3 / §7.9）。
 * <p>
 * 字段名按报告里的 @SerializedName 原样保留；get/set 用可读名字。
 * version 字段在规则里可能是数字（3）也可能是字符串（"3"），Gson 的
 * JsonReader.nextString() 会把数字/布尔 token 转成字符串，所以直接用 String
 * 即可，不需要原版那个自定义 TypeAdapter。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkRule {

    @SerializedName("title")
    private String title;
    @SerializedName("author")
    private String author;
    @SerializedName("version")
    private String version;
    @SerializedName("type")
    private String type;
    @SerializedName("group")
    private String group;
    @SerializedName("ua")
    private String ua;
    @SerializedName("url")
    private String url;
    @SerializedName("class_name")
    private String className;
    @SerializedName("class_url")
    private String classUrl;
    @SerializedName("area_name")
    private String areaName;
    @SerializedName("area_url")
    private String areaUrl;
    @SerializedName("year_name")
    private String yearName;
    @SerializedName("year_url")
    private String yearUrl;
    @SerializedName("sort_name")
    private String sortName;
    @SerializedName("sort_url")
    private String sortUrl;
    @SerializedName("col_type")
    private String colType;
    @SerializedName("find_rule")
    private String findRule;
    @SerializedName("search_url")
    private String searchUrl;
    @SerializedName("searchFind")
    private String searchFind;
    @SerializedName("detail_find_rule")
    private String detailFindRule;
    @SerializedName("sdetail_find_rule")
    private String sdetailFindRule;
    @SerializedName("detail_col_type")
    private String detailColType;
    @SerializedName("sdetail_col_type")
    private String sdetailColType;
    @SerializedName("preRule")
    private String preRule;
    @SerializedName("pages")
    private String pages;
    @SerializedName("last_chapter_rule")
    private String lastChapterRule;
    @SerializedName("icon")
    private String icon;
    @SerializedName("chapter_find")
    private String chapterFind;
    @SerializedName("movie_find")
    private String movieFind;
    @SerializedName("firstHeader")
    private String firstHeader;
    @SerializedName("params")
    private String params;
    @SerializedName("pinYin")
    private String pinYin;
    @SerializedName("proxy")
    private String proxy;
    @SerializedName("titleColor")
    private String titleColor;
    @SerializedName("storage")
    private String storage;
    @SerializedName("displayName")
    private String displayName;
    @SerializedName("weight")
    private int weight;

    /** 解析出这条规则的原始 JSON（不序列化，运行期用：MY_RULE、重存）。 */
    private transient String sourceJson;

    public String getTitle() { return TextUtils.isEmpty(title) ? "" : title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return TextUtils.isEmpty(author) ? "" : author; }
    public String getVersion() { return TextUtils.isEmpty(version) ? "" : version; }
    public String getType() { return TextUtils.isEmpty(type) ? "" : type; }
    public String getGroup() { return TextUtils.isEmpty(group) ? "" : group; }
    public String getUa() { return TextUtils.isEmpty(ua) ? "" : ua; }
    public String getUrl() { return TextUtils.isEmpty(url) ? "" : url; }
    public void setUrl(String url) { this.url = url; }
    public String getClassName() { return TextUtils.isEmpty(className) ? "" : className; }
    public String getClassUrl() { return TextUtils.isEmpty(classUrl) ? "" : classUrl; }
    public String getAreaName() { return TextUtils.isEmpty(areaName) ? "" : areaName; }
    public String getAreaUrl() { return TextUtils.isEmpty(areaUrl) ? "" : areaUrl; }
    public String getYearName() { return TextUtils.isEmpty(yearName) ? "" : yearName; }
    public String getYearUrl() { return TextUtils.isEmpty(yearUrl) ? "" : yearUrl; }
    public String getSortName() { return TextUtils.isEmpty(sortName) ? "" : sortName; }
    public String getSortUrl() { return TextUtils.isEmpty(sortUrl) ? "" : sortUrl; }
    public String getColType() { return TextUtils.isEmpty(colType) ? "" : colType; }
    public String getFindRule() { return TextUtils.isEmpty(findRule) ? "" : findRule; }
    public String getSearchUrl() { return TextUtils.isEmpty(searchUrl) ? "" : searchUrl; }
    public String getSearchFind() { return TextUtils.isEmpty(searchFind) ? "" : searchFind; }
    public String getDetailFindRule() { return TextUtils.isEmpty(detailFindRule) ? "" : detailFindRule; }
    public String getSdetailFindRule() { return TextUtils.isEmpty(sdetailFindRule) ? "" : sdetailFindRule; }
    public String getDetailColType() { return TextUtils.isEmpty(detailColType) ? "" : detailColType; }
    public String getSdetailColType() { return TextUtils.isEmpty(sdetailColType) ? "" : sdetailColType; }
    public String getPreRule() { return TextUtils.isEmpty(preRule) ? "" : preRule; }
    public String getPages() { return TextUtils.isEmpty(pages) ? "" : pages; }
    public String getLastChapterRule() { return TextUtils.isEmpty(lastChapterRule) ? "" : lastChapterRule; }
    public String getIcon() { return TextUtils.isEmpty(icon) ? "" : icon; }
    public String getChapterFind() { return TextUtils.isEmpty(chapterFind) ? "" : chapterFind; }
    public String getMovieFind() { return TextUtils.isEmpty(movieFind) ? "" : movieFind; }
    public String getFirstHeader() { return TextUtils.isEmpty(firstHeader) ? "" : firstHeader; }
    public String getParams() { return TextUtils.isEmpty(params) ? "" : params; }
    public String getPinYin() { return TextUtils.isEmpty(pinYin) ? "" : pinYin; }
    public String getProxy() { return TextUtils.isEmpty(proxy) ? "" : proxy; }
    public String getTitleColor() { return TextUtils.isEmpty(titleColor) ? "" : titleColor; }
    public String getStorage() { return TextUtils.isEmpty(storage) ? "" : storage; }
    public String getDisplayName() { return TextUtils.isEmpty(displayName) ? "" : displayName; }
    public int getWeight() { return weight; }

    public String getSourceJson() { return sourceJson; }
    public void setSourceJson(String sourceJson) { this.sourceJson = sourceJson; }

    /** find_rule 是否整段 JS（以 js: 开头），对应 b32.f()。 */
    public boolean isJsFindRule() {
        return HkParser.isJsRule(findRule);
    }

    /**
     * 校验，对应 g22.a0() 的三条提示（只返回提示，不弹 toast，调用方决定怎么展示）：
     * 缺 title →「规则缺少 title」；缺 url 且 find_rule 不是 JS →「规则缺少 url」；
     * 缺 find_rule →「规则缺少 find_rule」。通过返回 null。
     */
    public String validate() {
        if (TextUtils.isEmpty(title)) return "规则缺少 title";
        if (TextUtils.isEmpty(url) && !isJsFindRule()) return "规则缺少 url";
        if (TextUtils.isEmpty(findRule)) return "规则缺少 find_rule";
        return null;
    }

    /** class_name 按 & 切分（与 class_url 数量一致）。 */
    public String[] classNames() {
        if (TextUtils.isEmpty(className)) return new String[0];
        return className.split("&");
    }

    /** class_url 按 & 切分（与 class_name 数量一致）。 */
    public String[] classUrls() {
        if (TextUtils.isEmpty(classUrl)) return new String[0];
        return classUrl.split("&");
    }

    /**
     * 取某个分类对应的列表 URL 模板。classId 为空取第一个；classId 是分类名时按
     * class_name 的下标取；classId 是数字时按下标取；找不到回落到第一个；都没有
     * 分类时回落到规则 url。
     */
    public String classUrlFor(String classId) {
        String[] names = classNames();
        String[] urls = classUrls();
        if (urls.length == 0) return getUrl();
        if (TextUtils.isEmpty(classId)) return urls[0];
        for (int i = 0; i < names.length && i < urls.length; i++) {
            if (classId.equals(names[i])) return urls[i];
        }
        try {
            int index = Integer.parseInt(classId);
            if (index >= 0 && index < urls.length) return urls[index];
        } catch (NumberFormatException ignored) {
        }
        return urls[0];
    }

    public String toJson() {
        return App.gson().toJson(this);
    }

    /**
     * 解析一条规则 JSON，对应 i22.I()：空内容、不是 { 开头（[ 开头是规则数组，
     * 走批量导入，不是这里）返回 null；解析失败返回 null。
     */
    public static HkRule parse(String json) {
        if (TextUtils.isEmpty(json)) return null;
        String text = json.trim();
        if (!text.startsWith("{")) return null;
        try {
            HkRule rule = App.gson().fromJson(text, HkRule.class);
            if (rule == null) return null;
            rule.setSourceJson(text);
            return rule;
        } catch (Exception e) {
            return null;
        }
    }
}
