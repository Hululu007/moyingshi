package com.github.moyingshi.osc.tv.widget;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件模块（WidgetMetadata.modules 里的一项）。
 * {@code params} 保留原始 JsonObject，由浏览页按 type 解释
 * （enumeration / string / page / constant）。
 */
public class WidgetModule {

    @SerializedName("title")
    private String title;
    @SerializedName("description")
    private String description;
    @SerializedName("type")
    private String type;
    @SerializedName("functionName")
    private String functionName;
    @SerializedName("cacheDuration")
    private Integer cacheDuration;
    @SerializedName("params")
    private List<JsonObject> params = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public String getFunctionName() {
        return functionName;
    }

    public List<JsonObject> getParams() {
        return params == null ? new ArrayList<>() : params;
    }

    public boolean isCategory() {
        return "category".equals(type);
    }

    /**
     * cacheDuration 为 null 表示脚本没写，默认 3600 秒；
     * 显式写 0 表示不缓存。
     */
    public boolean noCache() {
        return cacheDuration != null && cacheDuration == 0;
    }

    public long cacheTtlMs() {
        return cacheDuration == null ? 3600_000L : cacheDuration * 1000L;
    }
}
