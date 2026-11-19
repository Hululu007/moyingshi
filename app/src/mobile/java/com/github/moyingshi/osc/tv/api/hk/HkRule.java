package com.github.moyingshi.osc.tv.api.hk;

import com.github.moyingshi.osc.tv.App;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 海阔 rule.json 的 Java 数据模型（Gson 反序列化）。
 * <p>
 * 以星辰 {@code com.fongmi.android.tv.api.hk.HkRule}（656 行）为准移植，字段、
 * {@code @SerializedName} 与方法均与之一致；另保留墨影视自有的 parse/toJson/
 * sourceJson/isSearchable/classNames/classUrls/classUrlFor 能力。
 * <p>
 * 与星辰的两处有意偏差（不要“顺手”改回）：
 * <ol>
 * <li>{@link #validate()} 保留墨影视的 {@code String validate()} 签名与三条中文提示，
 * 通过返回 null；星辰同语义版本是抛 {@code IllegalArgumentException}。
 * 调用方 HkStore（importJsonFile 等三处）与 HkExecutor（六处）都用这个签名。</li>
 * <li>{@link #getFindRule()} 保留 null→"" 归一；星辰是直返 null。原因是我们现有
 * 调用方 HkExecutor（evalFindRule 与首页选择器解析）都不判 null，
 * 改直返 null 会把 NPE 风险带进现版执行链。阶段 4 已复核：新调用点对 "" 与 null
 * 同为空结果（HkSelector.parseList 空规则回空表），归一保留，不跟进星辰。</li>
 * </ol>
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkRule {

    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 15; 2407FRK8EC Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @SerializedName("title")
    private String title;
    @SerializedName("author")
    private String author;
    @JsonAdapter(VersionAdapter.class)
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
    // ---- 8.83 ArticleListRule / MovieRule 对齐（rule.json 原生字段） ----
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
    /** 8.83 MovieRule.weight（排序权重，整数）。 */
    @SerializedName("weight")
    private int weight;

    /**
     * 8.83 ArticleListRuleJO.adBlockUrls（G48）：规则附带的广告拦截 URL 串
     * （多条以 "##" 分隔）。导入时非空须先过询问弹窗、确认附带才并入全局
     * 拦截表（HkAdUrlBlocker）；旧形无此字段，附带拦截串在 Gson 解析时无处
     * 落、随导入静默丢失。
     */
    @SerializedName("adBlockUrls")
    private String adBlockUrls;

    /** 运行时启用开关，不序列化进 rule.json，由 HkRuleManager 持久化。 */
    private transient boolean enabled = true;

    /**
     * 8.83 MovieRule.baseUrl / chapterUrl（CommonParser.getUrl* 相对路径拼接用）。
     * 不序列化，运行时设置。
     */
    private transient String baseUrl;
    private transient String chapterUrl;

    /** 解析出这条规则的原始 JSON（不序列化，运行期用：MY_RULE、重存）。墨影视自有字段。 */
    private transient String sourceJson;

    /**
     * 校验，对应 g22.a0() 的三条提示（只返回提示，不弹 toast，调用方决定怎么展示）：
     * 缺 title →「规则缺少 title」；缺 url 且 find_rule 不是 JS →「规则缺少 url」；
     * 缺 find_rule →「规则缺少 find_rule」。通过返回 null。
     * <p>
     * 有意偏差：星辰同语义版本是抛 {@code IllegalArgumentException}，这里保留
     * 墨影视的 {@code String} 签名，因为 HkStore 与 HkExecutor 的调用方都用这个签名。
     */
    public String validate() {
        if (isEmpty(title)) return "规则缺少 title";
        if (isEmpty(url) && !isJsFindRule()) return "规则缺少 url";
        if (isEmpty(findRule)) return "规则缺少 find_rule";
        return null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 解析 mobile/pc/自定义 UA。 */
    public String resolvedUa() {
        if ("mobile".equalsIgnoreCase(ua)) return MOBILE_UA;
        if ("pc".equalsIgnoreCase(ua)) return PC_UA;
        return isEmpty(ua) ? MOBILE_UA : ua.trim();
    }

    /** find_rule 是否整段 JS（以 js: 开头），对应 b32.f()。委托给 HkSelector（阶段 4 已搬）。 */
    public boolean isJsFindRule() {
        return HkSelector.isJsRule(findRule);
    }

    public boolean isJsSearchRule() {
        return searchFind != null && searchFind.trim().startsWith("js:");
    }

    public boolean hasSearch() {
        return !isEmpty(searchUrl) && !isEmpty(searchFind);
    }

    /**
     * 是否可搜索，对应 g22.E()：search_url 和 searchFind 都非空。
     * 为假时搜索返回空列表（不要假数据），见 docs/miniapp-search.md。
     * 墨影视自有方法，与 {@link #hasSearch()} 同语义，调用方是 HkExecutor/HkListActivity。
     */
    public boolean isSearchable() {
        return !isEmpty(searchUrl) && !isEmpty(searchFind);
    }

    /** class_name/class_url 按 & 配对，返回 [name, url] 数组列表。 */
    public List<String[]> getClassPairs() {
        return pairList(className, classUrl);
    }

    public List<String[]> getAreaPairs() {
        return pairList(areaName, areaUrl);
    }

    public List<String[]> getYearPairs() {
        return pairList(yearName, yearUrl);
    }

    public List<String[]> getSortPairs() {
        return pairList(sortName, sortUrl);
    }

    private static List<String[]> pairList(String names, String urls) {
        List<String[]> list = new ArrayList<>();
        // 8.83 原文（ArticleListFragment .line 1272-1290）：header 行注入只要求 class_name 非空，
        // url 允许为空（按 "&" 切分后缺省 ""），不因 class_url 为空整行消失。
        if (isEmpty(names)) return list;
        String[] ns = names.split("&", -1);
        String[] us = isEmpty(urls) ? new String[0] : urls.split("&", -1);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < ns.length; i++) {
            String n = ns[i].trim();
            if (n.isEmpty() || !seen.add(n)) continue; // 去重：跳过空名和重复名
            String u = i < us.length ? us[i].trim() : "";
            list.add(new String[]{n, u});
        }
        return list;
    }

    public String getTitle() {
        if (title == null) return "";
        String t = title.trim();
        // 脏数据：JS undefined/null 被字符串化后存入 title，直接显示会出 "undefined" tab
        if (t.isEmpty() || "undefined".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t)) return "";
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author == null ? "" : author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public int getVersion() {
        if (version == null) return 0;
        try {
            return Integer.parseInt(version.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 版本比较（分段数值比，与 LabEnvCatalog.compareVersions 同口径）：去 v 前缀、
     * 按 "."／"-" 分段逐段比数值。getVersion() 只认纯整数，带 v 前缀或小数的版本号
     * 恒判 0——远端 v18 对本地 v17 比成 0&gt;0、手动/自动检查更新与导入更新判定整体失效，
     * 所有版本新旧判定一律走本方法比原始串；纯数字版本与 getVersion() 整数比同结果。
     */
    public static int compareVersions(String a, String b) {
        String[] pa = versionSegments(a);
        String[] pb = versionSegments(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? segmentNumber(pa[i]) : 0;
            int vb = i < pb.length ? segmentNumber(pb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    private static String[] versionSegments(String v) {
        if (v == null) return new String[0];
        String s = v.trim().toLowerCase();
        if (s.startsWith("v")) s = s.substring(1);
        return s.isEmpty() ? new String[0] : s.split("[.\\-]");
    }

    private static int segmentNumber(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * version 字段宽松反序列化：规则 JSON 里 version 可能是整数（"version": 1）、
     * 字符串（"version": "v17"）、布尔甚至空值，这里统一收敛为 String。
     * 数字按原文转字符串（1 → "1"，1.5 → "1.5"），非法结构直接跳过取 null。
     */
    public static class VersionAdapter extends TypeAdapter<String> {
        @Override
        public String read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            if (t == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (t == JsonToken.BOOLEAN) {
                return Boolean.toString(in.nextBoolean());
            }
            if (t == JsonToken.NUMBER || t == JsonToken.STRING) {
                return in.nextString();
            }
            in.skipValue();
            return null;
        }

        @Override
        public void write(JsonWriter out, String value) throws IOException {
            out.value(value);
        }
    }

    public void setVersion(int version) {
        this.version = String.valueOf(version);
    }

    /** 原始版本号字符串（Gson 直接映射，兼容数字/字符串两种写法）。 */
    public String getVersionRaw() {
        return version == null ? "" : version;
    }

    /** 展示用版本号：原始串去掉自带的 v 前缀（界面统一拼 "v"＋本值，不会出现 vv17） */
    public String getVersionLabel() {
        String s = getVersionRaw().trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s.isEmpty() ? "0" : s;
    }

    public void setVersionRaw(String version) {
        this.version = version;
    }

    public String getType() {
        return type == null ? "" : type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getGroup() {
        return group == null ? "" : group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getUa() {
        return ua == null ? "" : ua;
    }

    public void setUa(String ua) {
        this.ua = ua;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getClassName() {
        return className == null ? "" : className;
    }

    public String getClassUrl() {
        return classUrl == null ? "" : classUrl;
    }

    public String getAreaName() {
        return areaName == null ? "" : areaName;
    }

    public String getAreaUrl() {
        return areaUrl == null ? "" : areaUrl;
    }

    public String getYearName() {
        return yearName == null ? "" : yearName;
    }

    public String getYearUrl() {
        return yearUrl == null ? "" : yearUrl;
    }

    public String getSortName() {
        return sortName == null ? "" : sortName;
    }

    public String getSortUrl() {
        return sortUrl == null ? "" : sortUrl;
    }

    /**
     * 有意偏差：星辰直返字段（null 可达），这里保留墨影视旧形的 null→"" 归一。
     * 原因是我们现有调用方 HkExecutor（evalFindRule 与首页选择器解析）都不判 null；
     * 改直返 null 会把 NPE 风险带进现版执行链。阶段 4 已复核，归一保留（见类注释）。
     */
    public String getFindRule() {
        return isEmpty(findRule) ? "" : findRule;
    }

    public void setFindRule(String findRule) {
        this.findRule = findRule;
    }

    /** 规则级 col_type（官方 dealRule：@rule= 条目无 6 段样式时的默认；空则回退 MOVIE_3）。 */
    public String getColType() {
        return colType == null ? "" : colType;
    }

    public void setColType(String colType) {
        this.colType = colType;
    }

    /** 清空分类/地区/年份/类型导航（子列表派生用：官方 dealRule 的新规则不带这些字段）。 */
    public void clearNav() {
        className = "";
        classUrl = "";
        areaName = "";
        areaUrl = "";
        yearName = "";
        yearUrl = "";
        sortName = "";
        sortUrl = "";
    }

    /**
     * 派生子列表规则（官方 ArticleListFragment.dealRule 语义）：
     * 复制当前规则全部字段，仅覆盖 url / find_rule / col_type；
     * 其余（ua/group/preRule/title/last_chapter_rule/pages 等）继承父规则。
     * sourceJson 不继承：它是父规则的原始 JSON，派生规则的 url/find_rule/colType
     * 已不同，沿用会是过期内容。
     */
    public HkRule deriveSubRule(String url, String findRule, String colType) {
        HkRule r = new HkRule();
        r.title = this.title;
        r.author = this.author;
        r.version = this.version;
        r.type = this.type;
        r.group = this.group;
        r.ua = this.ua;
        r.url = url;
        r.className = this.className;
        r.classUrl = this.classUrl;
        r.areaName = this.areaName;
        r.areaUrl = this.areaUrl;
        r.yearName = this.yearName;
        r.yearUrl = this.yearUrl;
        r.sortName = this.sortName;
        r.sortUrl = this.sortUrl;
        r.colType = colType;
        r.findRule = findRule;
        r.searchUrl = this.searchUrl;
        r.searchFind = this.searchFind;
        r.detailFindRule = this.detailFindRule;
        r.sdetailFindRule = this.sdetailFindRule;
        r.detailColType = this.detailColType;
        r.sdetailColType = this.sdetailColType;
        r.preRule = this.preRule;
        r.pages = this.pages;
        r.lastChapterRule = this.lastChapterRule;
        r.icon = this.icon;
        r.chapterFind = this.chapterFind;
        r.movieFind = this.movieFind;
        r.firstHeader = this.firstHeader;
        r.params = this.params;
        r.pinYin = this.pinYin;
        r.proxy = this.proxy;
        r.titleColor = this.titleColor;
        r.storage = this.storage;
        r.displayName = this.displayName;
        r.weight = this.weight;
        r.adBlockUrls = this.adBlockUrls;
        r.enabled = this.enabled;
        return r;
    }

    /** 全字段克隆（对应原版 ArticleListRule.clone()）：字段复制与 deriveSubRule 共用同一份清单。 */
    public HkRule copyRule() {
        return deriveSubRule(this.url, this.findRule, this.colType);
    }

    public void setClassUrl(String classUrl) {
        this.classUrl = classUrl;
    }

    public void setAreaUrl(String areaUrl) {
        this.areaUrl = areaUrl;
    }

    public void setYearUrl(String yearUrl) {
        this.yearUrl = yearUrl;
    }

    public void setSortUrl(String sortUrl) {
        this.sortUrl = sortUrl;
    }

    public String getSearchUrl() {
        return searchUrl == null ? "" : searchUrl;
    }

    public void setSearchUrl(String searchUrl) {
        this.searchUrl = searchUrl;
    }

    public String getSearchFind() {
        return searchFind == null ? "" : searchFind;
    }

    public void setSearchFind(String searchFind) {
        this.searchFind = searchFind;
    }

    public String getDetailFindRule() {
        return detailFindRule == null ? "" : detailFindRule;
    }

    public String getSdetailFindRule() {
        return sdetailFindRule == null ? "" : sdetailFindRule;
    }

    public String getDetailColType() {
        return detailColType == null ? "" : detailColType;
    }

    public String getSdetailColType() {
        return sdetailColType == null ? "" : sdetailColType;
    }

    public String getPreRule() {
        return preRule == null ? "" : preRule;
    }

    public void setPreRule(String preRule) {
        this.preRule = preRule;
    }

    public String getLastChapterRule() {
        return lastChapterRule == null ? "" : lastChapterRule;
    }

    public void setLastChapterRule(String lastChapterRule) {
        this.lastChapterRule = lastChapterRule;
    }

    /** G48：规则附带的广告拦截 URL 串（"##" 分隔），无则 ""。 */
    public String getAdBlockUrls() {
        return adBlockUrls == null ? "" : adBlockUrls;
    }

    public void setAdBlockUrls(String adBlockUrls) {
        this.adBlockUrls = adBlockUrls;
    }

    public String getPages() {
        return pages == null ? "" : pages;
    }

    public void setPages(String pages) {
        this.pages = pages;
    }

    public String getIcon() {
        return icon == null ? "" : icon;
    }

    // ---- 8.83 ArticleListRule / MovieRule 字段（rule.json 原生） ----

    /** 小说/漫画类规则的章节解析规则。 */
    public String getChapterFind() {
        return chapterFind == null ? "" : chapterFind;
    }

    public void setChapterFind(String chapterFind) {
        this.chapterFind = chapterFind;
    }

    /** 影视类规则的影片解析规则。 */
    public String getMovieFind() {
        return movieFind == null ? "" : movieFind;
    }

    public void setMovieFind(String movieFind) {
        this.movieFind = movieFind;
    }

    public String getFirstHeader() {
        return firstHeader == null ? "" : firstHeader;
    }

    public void setFirstHeader(String firstHeader) {
        this.firstHeader = firstHeader;
    }

    /**
     * 规则级 params（8.83 ArticleListRule 唯一 params 字段，smali_classes2 bean 字段表实证）：
     * MY_PARAMS 的唯一来源——规则自带值，或子页场景下点击条目的 extra JSON
     * （由 PageParser.getNextPage / dealRule 写入本字段，随规则序列化跨页传递）。
     */
    public String getParams() {
        return params == null ? "" : params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    /** 标题拼音（排序/搜索用）。 */
    public String getPinYin() {
        return pinYin == null ? "" : pinYin;
    }

    public void setPinYin(String pinYin) {
        this.pinYin = pinYin;
    }

    /** 代理配置。 */
    public String getProxy() {
        return proxy == null ? "" : proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public String getTitleColor() {
        return titleColor == null ? "" : titleColor;
    }

    public void setTitleColor(String titleColor) {
        this.titleColor = titleColor;
    }

    public String getStorage() {
        return storage == null ? "" : storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public String getDisplayName() {
        return displayName == null ? "" : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** 8.83 MovieRule.weight：排序权重。 */
    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChapterUrl() {
        return chapterUrl;
    }

    public void setChapterUrl(String chapterUrl) {
        this.chapterUrl = chapterUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ---- 墨影视自有能力（星辰没有，保留） ----

    public String getSourceJson() { return sourceJson; }
    public void setSourceJson(String sourceJson) { this.sourceJson = sourceJson; }

    /** class_name 按 & 切分（与 class_url 数量一致）。 */
    public String[] classNames() {
        if (isEmpty(className)) return new String[0];
        return className.split("&");
    }

    /** class_url 按 & 切分（与 class_name 数量一致）。 */
    public String[] classUrls() {
        if (isEmpty(classUrl)) return new String[0];
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
        if (isEmpty(classId)) return urls[0];
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
        if (isEmpty(json)) return null;
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

    @Override
    public String toString() {
        return "HkRule{title='" + title + "', author='" + author + "', version=" + version + ", type='" + type + "'}";
    }
}
