package com.fongmi.android.tv.widget;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.TmdbConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.TmdbProxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页组件区编排（B5）：加载内置组件的 homeModule，先读缓存渲染、再后台刷新。
 *
 * <p>每个组件独立加载（4 线程并行），单个组件失败/超时只隐藏该行，不影响首页其它内容。
 * 会话内节流：同一组件 15 分钟内成功刷新过就不再发网络请求（只重贴缓存），
 * 避免每次 setAdapter 都重复拉取；TTL 到期由 WidgetCache 的缓存失效 + 下次加载时刷新覆盖。
 */
public class WidgetManager {

    public static class Builtin {
        public final String id;
        public final String asset;

        Builtin(String id, String asset) {
            this.id = id;
            this.asset = asset;
        }
    }

    public static final List<Builtin> BUILTINS = List.of(
            new Builtin("builtin.bangumi", "widgets/bangumi.js"),
            new Builtin("builtin.douban", "widgets/douban.js"),
            new Builtin("builtin.mikan", "widgets/mikan.js"),
            new Builtin("builtin.tmdb", "widgets/tmdb.js"));

    /** 内置组件的固定顺序（首页行按此排）；未知 id 排最后。 */
    public static int builtinOrder(String widgetId) {
        for (int i = 0; i < BUILTINS.size(); i++) {
            if (BUILTINS.get(i).id.equals(widgetId)) return i;
        }
        return Integer.MAX_VALUE;
    }

    /** 一行组件区：组件元数据 + 首页模块 + 条目。 */
    public static class Section {
        public final WidgetMetadata metadata;
        public final WidgetModule module;
        public final List<WidgetItem> items;
        public final boolean fromCache;

        public Section(WidgetMetadata metadata, WidgetModule module, List<WidgetItem> items, boolean fromCache) {
            this.metadata = metadata;
            this.module = module;
            this.items = items;
            this.fromCache = fromCache;
        }

        public String title() {
            return metadata.getTitle() + " · " + module.getTitle();
        }
    }

    /** 回调都在主线程（App.post）上触发。 */
    public interface Listener {
        void onSection(Section section);

        void onSectionFailed(String widgetId);
    }

    private static final long SESSION_THROTTLE_MS = 15 * 60 * 1000L;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, Long> lastFreshAt = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loading = new ConcurrentHashMap<>();

    /** 加载全部内置组件的首页行。开关关时调用方不应调这里。 */
    public void loadHome(Listener listener) {
        for (Builtin builtin : BUILTINS) executor.execute(() -> loadOne(builtin, listener));
    }

    public void destroy() {
        executor.shutdownNow();
    }

    private void loadOne(Builtin builtin, Listener listener) {
        if (Boolean.TRUE.equals(loading.putIfAbsent(builtin.id, true))) return;
        WidgetHost host = null;
        boolean postedCache = false;
        try {
            String script = Asset.read(builtin.asset);
            if (TextUtils.isEmpty(script)) throw new IllegalStateException("组件脚本为空: " + builtin.asset);
            host = new WidgetHost(builtin.id, script);
            WidgetMetadata metadata = host.load();
            if (!builtin.id.equals(metadata.getId())) throw new IllegalStateException("组件 id 不符: " + metadata.getId());
            WidgetModule module = metadata.getHomeModule();
            if (module == null) throw new IllegalStateException("无首页模块");
            Map<String, String> params = defaultParams(module);
            String paramsKey = stableParams(params);
            if (!module.noCache()) {
                String cached = WidgetCache.read(builtin.id, module.getFunctionName(), paramsKey, module.cacheTtlMs());
                List<WidgetItem> cachedItems = parseItems(cached);
                if (cachedItems != null && !cachedItems.isEmpty()) {
                    postedCache = true;
                    post(() -> listener.onSection(new Section(metadata, module, cachedItems, true)));
                }
            }
            Long last = lastFreshAt.get(builtin.id);
            if (last != null && System.currentTimeMillis() - last < SESSION_THROTTLE_MS) return;
            String json = host.invoke(module.getFunctionName(), params);
            // 调通了就记一次（即使返回空/解析失败也节流，15 分钟内不再发请求；抛错不记，下次重试）。
            lastFreshAt.put(builtin.id, System.currentTimeMillis());
            List<WidgetItem> items = parseItems(json);
            if (items == null || items.isEmpty()) {
                if (!postedCache) post(() -> listener.onSectionFailed(builtin.id));
                return;
            }
            if (!module.noCache()) WidgetCache.write(builtin.id, module.getFunctionName(), paramsKey, json);
            post(() -> listener.onSection(new Section(metadata, module, items, false)));
        } catch (Throwable e) {
            SpiderDebug.log("widget", "load failed %s: %s", builtin.id, e.getMessage());
            if (!postedCache) post(() -> listener.onSectionFailed(builtin.id));
        } finally {
            loading.remove(builtin.id);
            if (host != null) host.destroy();
        }
    }

    private static void post(Runnable runnable) {
        App.post(runnable);
    }

    private static List<WidgetItem> parseItems(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            return App.gson().fromJson(json, new TypeToken<List<WidgetItem>>() {
            }.getType());
        } catch (Throwable e) {
            return null;
        }
    }

    /** 模块默认参数：取各 param 的 value（page 缺省为 1），再按 belongTo 条件过滤。 */
    private static Map<String, String> defaultParams(WidgetModule module) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (JsonObject param : module.getParams()) {
            String name = Json.safeString(param, "name");
            if (TextUtils.isEmpty(name)) continue;
            String type = Json.safeString(param, "type");
            String value = param.has("value") && !param.get("value").isJsonNull() ? param.get("value").getAsString() : "";
            if ("page".equals(type) && TextUtils.isEmpty(value)) value = "1";
            raw.put(name, value);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (JsonObject param : module.getParams()) {
            String name = Json.safeString(param, "name");
            if (TextUtils.isEmpty(name) || !raw.containsKey(name)) continue;
            if (!param.has("belongTo") || param.get("belongTo").isJsonNull()) {
                params.put(name, raw.get(name));
                continue;
            }
            JsonObject belongTo = param.getAsJsonObject("belongTo");
            String refValue = raw.get(Json.safeString(belongTo, "paramName"));
            boolean ok = false;
            if (belongTo.has("value") && belongTo.get("value").isJsonArray()) {
                for (JsonElement element : belongTo.getAsJsonArray("value")) {
                    if (refValue != null && refValue.equals(element.getAsString())) {
                        ok = true;
                        break;
                    }
                }
            }
            if (ok) params.put(name, raw.get(name));
        }
        return params;
    }

    /** 稳定序列化 params（按 key 排序），用作缓存 key 的一部分。 */
    private static String stableParams(Map<String, String> params) {
        return App.gson().toJson(new TreeMap<>(params));
    }

    /**
     * 条目海报地址：posterUrl（绝对地址）优先；TMDB 的 posterPath（相对路径）拼 imageBase。
     * 返回 "" 表示无海报（调用方显示文字占位）。
     */
    public static String posterUrl(WidgetItem item) {
        if (item == null) return "";
        String poster = item.getPoster();
        if (TextUtils.isEmpty(poster)) return "";
        if (poster.startsWith("http")) return poster;
        if (poster.startsWith("/")) {
            try {
                String base = TmdbConfig.objectFrom(Setting.getTmdbConfig()).getImageBase();
                return TmdbProxy.imageUrl(base, poster);
            } catch (Throwable e) {
                return "";
            }
        }
        return poster;
    }

    /**
     * 带请求头的海报地址：customHeaders（豆瓣图片要带 Referer）用 ImgUtil 的
     * {@code @Headers=<json>@} 后缀机制透传；无 customHeaders 时返回原地址。
     */
    public static String posterUrlWithHeaders(WidgetItem item) {
        String url = posterUrl(item);
        if (TextUtils.isEmpty(url)) return "";
        Map<String, String> headers = item.getCustomHeaders();
        if (headers == null || headers.isEmpty()) return url;
        return url + "@Headers=" + App.gson().toJson(headers) + "@";
    }
}
