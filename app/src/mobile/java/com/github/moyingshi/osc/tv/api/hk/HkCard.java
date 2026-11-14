package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

/**
 * 列表卡片模型，对应魔影小程序的 uy1（见逆向报告 §7.12）。
 * <p>
 * 字段名按报告里的 @SerializedName 原样保留；get/set 用可读名字。
 * pic 在 JS 规则里也写作 img（setResult 时两者都认，见 HkEngine.Bridge）。
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
}
