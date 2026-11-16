package com.github.moyingshi.osc.tv.api.hk.util;
/** 移植自星辰 com/fongmi/android/tv/api/hk/util/HkHikerRuleUtil.java（331 行）：除包名外与原文一致；异步版与 historyJson 一族未搬，collection/history 两支先回空形。详见 docs/miniapp-xingchen-3e-facade.md。 */

import android.text.TextUtils;
import android.util.Log;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkRuleManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 移植自 8.83 {@code com.example.hikerview.service.http.HikerRuleUtil}（smali 1042 行）。
 * <p>
 * 8.83 原文：
 * <ul>
 *   <li>{@code getAssetsFileByHiker(url)}：{@code hiker://assets/} 开头时，按后缀返回内置 asset 文本
 *      （home.js / beautify.js / crypto-java.js / wasm.js / translate/ / localmanager.txt），
 *       非 hiker://assets/ 或空返回 ""。</li>
 *   <li>{@code getRulesByHiker(url)}：{@code hiker://home@<标题>} → 按标题查规则库（LitePal）返回规则 JSON；
     *       另有异步版本（listener 回调，3e 未搬：需 HkCodeUtil，见阶段 6/7）。星辰用 {@link HkRuleManager#loadRuleJson(String)} 同款语义。</li>
 * </ul>
 */
public final class HkHikerRuleUtil {

    private HkHikerRuleUtil() {
    }

    /**
     * 8.83 HikerRuleUtil.getAssetsFileByHiker(String) 语义移植（smali 16-173）。
     *
     * @param url 形如 hiker://assets/home.js
     * @return asset 文本内容；非 hiker://assets/ 或空返回 ""
     */
    public static String getAssetsFileByHiker(String url) {
        if (TextUtils.isEmpty(url)) return "";
        final String prefix = "hiker://assets/";
        if (!url.startsWith(prefix)) return "";
        String p0 = url.replace(prefix, "");
        // 8.83 白名单：home.js / beautify.js / crypto-java.js / wasm.js / translate/ 前缀 / localmanager.txt
        boolean allowed = "home.js".equals(p0)
                || "beautify.js".equals(p0)
                || "crypto-java.js".equals(p0)
                || "wasm.js".equals(p0)
                || p0.startsWith("translate/")
                || "localmanager.txt".equals(p0);
        if (!allowed) return "";
        String code = loadAsset(p0);
        return code == null ? "" : code;
    }

    /**
     * 环 17-1 剔除键集（复核订正裁定）：官 shareFilter 15 名（storage/pageList/id/
     * order/gmtCreate/gmtModified/lastUseTime/displayName 及 LitePal 基类内部位
     * associatedModelsMapForJoinTable/WithFK/WithoutFK、fieldsToSetToDefault、
     * listToClearAssociatedFK/listToClearSelfFK、saved）∪ 咱实体比官实体多出的
     * weight、adBlockUrls 两名（官 ArticleListRule 实体无此二字段、经其实体序列化
     * 恒不出；adBlockUrls 只在 JO 面）。共 17 名。
     */
    private static final java.util.Set<String> SHARE_EXCLUDED = new java.util.HashSet<>(
            java.util.Arrays.asList("storage", "pageList", "id", "order", "gmtCreate",
                    "gmtModified", "lastUseTime", "displayName",
                    "associatedModelsMapForJoinTable", "associatedModelsMapWithFK",
                    "associatedModelsMapWithoutFK", "fieldsToSetToDefault",
                    "listToClearAssociatedFK", "listToClearSelfFK", "saved",
                    "weight", "adBlockUrls"));

    /**
     * home@ 序列化口（同步/异步共用，复核订正）：以<b>存量规则 JSON 为源</b>，解析为
     * JsonObject 后剔除 {@link #SHARE_EXCLUDED} 键集、其余键原样保留再序列化——不经
     * HkRule 实体回环。批2 首落的实体回环三处偏官：无值也恒出 weight:0、有值多出
     * adBlockUrls；存量 JSON 里实体没有的键（orderIndex/batchDeleting/selected 等）
     * 被回环吞掉；version 数值经实体 String 字段回环变字符串。现形键集恒＝源 JSON
     * 键集减剔除集、值形态原样（version 保持数值形）。未命中（loadRuleJson 回
     * null）、畸形/非对象 JSON → 回字符串 "null"（四字符）——官查不到时序列化
     * null 字面量，catch→"null" 吞异常形保持现状（记名 15，不扩大）。
     */
    private static String shareSerializeRule(String rawJson) {
        if (rawJson == null) return "null";
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(rawJson);
            if (parsed == null || !parsed.isJsonObject()) return "null";
            com.google.gson.JsonObject obj = parsed.getAsJsonObject();
            for (String key : SHARE_EXCLUDED) obj.remove(key);
            String out = new com.google.gson.Gson().toJson(obj);
            return out == null ? "null" : out;
        } catch (Exception e) {
            return "null";
        }
    }

    /**
     * 8.83 HikerRuleUtil.getRulesByHiker(String) 同步版语义移植（smali 174-590）。
     *
     * @param url 形如 hiker://home@&lt;规则标题&gt;
     * @return 规则 JSON；查不到回 "null"（环 17-1），格式不对返回 ""
     */
    public static String getRulesByHiker(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("hiker://")) return "";
        String p0 = url.replace("hiker://", "");
        if (p0.startsWith("home")) {
            // 8.83：split("@") 取 parts[1] 为标题查库；无标题返回全部规则（已排序）
            String[] parts = p0.split("@");
            if (parts.length > 1) {
                return shareSerializeRule(HkRuleManager.get().loadRuleJson(parts[1]));
            }
            return allRulesJson();
        }
        if (p0.startsWith("collection")) {
            // 3e 空位：原文返回 HkFavoriteStore.toJson()（收藏库记录 JSON）；
            // 我方暂无 HkFavoriteStore（阶段 7），按本文件 bookmark/download 两支
            // “无库恒回空”的同款口径先回 "[]"，阶段 7 搬 HkFavoriteStore 后恢复原文行。
            return "[]";
        }
        if (p0.startsWith("bookmark") || p0.startsWith("download")) {
            // 环 17-5/17-6 记名关账：官 Bookmark / DownloadRecord 两库在咱家
            // hk 侧无存储、无写入方（全树已核：只有页面路由消费、无 fetch
            // 取数消费），等价于空库，恒回 "[]"，不硬造库。
            return "[]";
        }
        if (p0.startsWith("history")) {
            // 3e 空位：原文走 historyJson()（History 表 hk 条目逐字段映射，
            // 见原文 historyJson/toViewHistoryRow/extractPageUrl/HkViewHistoryRow）；
            // 它依赖 History.isHk → HkDetailBridge.isHkKey（阶段 5）与
            // HkParserKit.getHomeUrl（阶段 4），且站 key 命名空间待定（星辰 hk_ 前缀，
            // 我方 hk:// scheme），本步不硬编谓词，先回 "[]"（与 bookmark/download
            // 两支“无库恒回空”同口径），阶段 5/7 把 historyJson 一族搬回后恢复原文行。
            return "[]";
        }
        return "";
    }

    /** 全部已安装规则 JSON 数组（8.83 home 无标题：findAll + sort + 逐条 share 过滤序列化）。 */
    private static String allRulesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            for (HkRule r : HkRuleManager.get().getRules()) {
                String json = HkRuleManager.get().loadRuleJson(r.getTitle());
                if (json == null) continue;
                if (!first) sb.append(",");
                sb.append(shareSerializeRule(json));
                first = false;
            }
        } catch (Exception ignored) {
        }
        return sb.append("]").toString();
    }

    private static String loadAsset(String assetPath) {
        try {
            InputStream is = App.get().getAssets().open(assetPath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String code = bos.toString("UTF-8");
            return TextUtils.isEmpty(code) ? null : code;
        } catch (Throwable t) {
            // 内置资源读失败与「资源为空」要可区分：回 null 契约（官形）不变，
            // 留痕除 Log.e 外再走本域 hk 文件诊断通道（SpiderDebug）——只进
            // logcat 的话用户发来的日志里看不见、定不了案（批 fix349＋residual）。
            Log.e("HkHikerRuleUtil", "内置规则资源读取失败：" + assetPath, t);
            com.github.catvod.crawler.SpiderDebug.log("hk", "asset read failed path=%s err=%s",
                    assetPath, String.valueOf(t));
            return null;
        }
    }
}
