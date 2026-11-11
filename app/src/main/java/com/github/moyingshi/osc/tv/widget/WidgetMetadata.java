package com.github.moyingshi.osc.tv.widget;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件元数据（CapyPlayer 规范的 WidgetMetadata）。
 * 由组件脚本顶层的 {@code var WidgetMetadata} 解析而来。
 */
public class WidgetMetadata {

    @SerializedName("id")
    private String id;
    @SerializedName("title")
    private String title;
    @SerializedName("description")
    private String description;
    @SerializedName("author")
    private String author;
    @SerializedName("site")
    private String site;
    @SerializedName("version")
    private String version;
    @SerializedName("homeModule")
    private String homeModule;
    @SerializedName("modules")
    private List<WidgetModule> modules = new ArrayList<>();

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public String getSite() {
        return site;
    }

    public String getVersion() {
        return version;
    }

    public List<WidgetModule> getModules() {
        return modules == null ? new ArrayList<>() : modules;
    }

    /**
     * 首页默认模块：脚本显式写了 homeModule 且能对应上就用它；
     * 否则取第一个非 category 模块；再没有就取 modules[0]。
     */
    public WidgetModule getHomeModule() {
        List<WidgetModule> list = getModules();
        if (list.isEmpty()) return null;
        if (homeModule != null && !homeModule.isEmpty()) {
            for (WidgetModule module : list) {
                if (homeModule.equals(module.getFunctionName())) return module;
            }
        }
        for (WidgetModule module : list) {
            if (!module.isCategory()) return module;
        }
        return list.get(0);
    }

    public WidgetModule findModule(String functionName) {
        if (functionName == null) return null;
        for (WidgetModule module : getModules()) {
            if (functionName.equals(module.getFunctionName())) return module;
        }
        return null;
    }
}
