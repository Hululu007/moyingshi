package com.fongmi.android.tv.widget;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 组件模块调用结果的文件缓存。
 * 粒度：每个模块调用一次 = 一个文件；key = widgetId + functionName + 稳定序列化(params)。
 * 文件内容：{"widgetId": ..., "savedAt": ..., "data": <模块返回的条目数组 JSON>}。
 */
public class WidgetCache {

    private static File dir() {
        try {
            File root = Path.cache();
            if (root == null) return null;
            File dir = new File(root, "widget-cache");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String fileName(String widgetId, String functionName, String paramsKey) {
        return md5(widgetId + "|" + functionName + "|" + paramsKey) + ".json";
    }

    /** 读缓存：未过期返回 data 的 JSON 字符串；无缓存/过期/损坏返回 null。 */
    public static String read(String widgetId, String functionName, String paramsKey, long ttlMs) {
        try {
            File dir = dir();
            if (dir == null) return null;
            File file = new File(dir, fileName(widgetId, functionName, paramsKey));
            if (!file.exists()) return null;
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonObject object = App.gson().fromJson(content, JsonObject.class);
            if (object == null || !object.has("savedAt") || !object.has("data")) return null;
            long savedAt = object.get("savedAt").getAsLong();
            if (System.currentTimeMillis() - savedAt > ttlMs) return null;
            return App.gson().toJson(object.get("data"));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 写缓存：dataJson 是模块返回的条目数组 JSON 字符串。 */
    public static void write(String widgetId, String functionName, String paramsKey, String dataJson) {
        try {
            File dir = dir();
            if (dir == null || dataJson == null) return;
            JsonObject object = new JsonObject();
            object.addProperty("widgetId", widgetId);
            object.addProperty("savedAt", System.currentTimeMillis());
            object.add("data", App.gson().fromJson(dataJson, com.google.gson.JsonElement.class));
            File file = new File(dir, fileName(widgetId, functionName, paramsKey));
            Files.write(file.toPath(), App.gson().toJson(object).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            SpiderDebug.log("widget", "cache write failed: %s", e.getMessage());
        }
    }

    /** 清掉某组件的全部缓存（脚本更新时用）。 */
    public static void clear(String widgetId) {
        try {
            File dir = dir();
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject object = App.gson().fromJson(content, JsonObject.class);
                    if (object != null && widgetId.equals(object.has("widgetId") ? object.get("widgetId").getAsString() : null)) {
                        file.delete();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("widget", "cache clear failed: %s", e.getMessage());
        }
    }
}
