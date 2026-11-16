package com.github.moyingshi.osc.tv.api.hk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.github.moyingshi.osc.tv.App;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 海阔规则的存取管（设计文档 §4.1）。
 * 存储：filesDir/plugins/hk/&lt;规则名&gt;.json，与 plugins/py、plugins/js 并列。
 * 规则 JS 数据：filesDir/plugins/hk/data/&lt;规则名&gt;/（setItem/getItem 持久化，M2 用）。
 * <p>
 * 移植阶段 2b：按星辰 HkRuleManager.java（2058 行）移植核心（存储/导入）——
 * 星辰行号 48–1160 与 1888–2058；1166–1888（云口令取件与导出）留待阶段 6，
 * 见文件内「阶段 6」标记。包名由星辰侧改为墨影视侧；
 * 规则引擎/落盘视频清理引用已按阶段 3/7 剔除或本地等价实现，详见
 * docs/miniapp-xingchen-2b-manager.md。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public class HkRuleManager {

    private static final String TAG = "HkRuleManager";

    private static final String DIR = "plugins/hk";
    private static final String DATA_DIR = "plugins/hk/data";
    private static final String PREFS = "hk_rule";
    private static final String KEY_ENABLED_PREFIX = "enabled_";

    private static volatile HkRuleManager instance;

    public static HkRuleManager get() {
        if (instance == null) {
            synchronized (HkRuleManager.class) {
                if (instance == null) {
                    instance = new HkRuleManager();
                    // 2b 跳过星辰 get() 里的落盘视频清理：落盘视频清理是阶段 7
                    // 按需、规则 JS 引擎是阶段 3；两者都不在本步引用。
                    // 环13 存量迁移（批1 复核订正1）：同一收口点后台跑一次，
                    // 把批1 前导入包留在内部旧落点的 libs/require.json 迁到官根。
                    try {
                        new Thread(instance::migrateLegacyHkZipAssets, "hk-migrate").start();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return instance;
    }

    private Context appContext() {
        return App.get();
    }

    /** 规则目录，不存在则创建。 */
    public File getDir() {
        Context ctx = appContext();
        File dir = ctx == null ? new File(DIR) : new File(ctx.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 读规则原始 JSON 文本（用于规则编辑）。不存在返回 null。
     */
    public String loadRuleJson(String title) {
        if (title == null || title.isEmpty()) return null;
        File f = new File(getDir(), safeFileName(title) + ".json");
        try {
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            // 文件存在却读失败与「不存在」同回 null 是接口契约（调用方全按
            // null 分流、无既有失败通道可接），留痕必须带标题＋完整路径，
            // 与「文件不存在」（无此日志）可区分（批 fix349＋residual）。
            Log.e(TAG, "规则文件读取失败：" + title + " path=" + f.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 按标题取已安装的规则（用于版本更新比对）。不存在返回 null。
     */
    public HkRule getInstalledRule(String title) {
        if (title == null || title.isEmpty()) return null;
        File f = new File(getDir(), safeFileName(title) + ".json");
        try {
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return parseJson(json);
        } catch (Throwable e) {
            // 同 loadRuleJson 口径：契约回 null 不变，留痕带标题＋完整路径，
            // 与「未安装」（无此日志）可区分（批 residual）。
            Log.e(TAG, "已装规则读取/解析失败：" + title + " path=" + f.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 环13 存量一次性迁移（批1 复核订正1）：批1 之前导入的 .hkzip 包把
     * libs 与 require.json 落在内部数据目录 data/&lt;标题&gt;/（libs/ 子目录与
     * require.json），批1 落点改官根（全局 libs/＋登记簿根 rules/files/）后
     * 旧文件无人再读、存量包内库集体失明。此处在管理器进程级首建时后台跑
     * 一次：按已安装规则标题逐个迁——libs 逐个移入全局 libs/（同名覆盖，
     * 与新导入同语义，具名与 md5 名同迁）、require.json 移入登记簿根
     * （已存在先删再覆盖，与新导入同语义），迁完删旧；旧目录读法
     * （loadLibLocal 的 per-rule 支）随之退役，三路（fileExist/$.require/
     * require 入口）同读全局一根。
     */
    private void migrateLegacyHkZipAssets() {
        try {
            for (HkRule rule : getRules()) {
                String title = rule == null ? null : rule.getTitle();
                if (title == null || title.isEmpty()) continue;
                File dataDir = getDataDir(title);
                File oldLibs = new File(dataDir, "libs");
                if (oldLibs.isDirectory()) {
                    File[] files = oldLibs.listFiles();
                    if (files != null) {
                        File global = getGlobalLibsDir();
                        for (File f : files) {
                            if (f == null || !f.isFile()) continue;
                            try {
                                File target = new File(global, f.getName());
                                if (target.exists()) target.delete();
                                Files.copy(f.toPath(), target.toPath());
                                f.delete();
                            } catch (Throwable t) {
                                // 拷贝失败源文件保留（delete 在 copy 之后），记日志待下次启动重试（批 fix349）。
                                Log.e(TAG, "旧版规则库文件迁移失败：" + f.getName(), t);
                            }
                        }
                    }
                    try {
                        oldLibs.delete();
                    } catch (Throwable ignored) {
                    }
                }
                File oldRequire = new File(dataDir, "require.json");
                if (oldRequire.isFile()) {
                    try {
                        File target = new File(getRequireFilesDir(title), "require.json");
                        if (target.exists()) target.delete();
                        Files.copy(oldRequire.toPath(), target.toPath());
                        oldRequire.delete();
                    } catch (Throwable t) {
                        Log.e(TAG, "旧版 require.json 迁移失败：" + title, t);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 某规则的持久化数据目录（setItem/getItem 用，M2）。 */
    public File getDataDir(String title) {
        File dir = new File(getDir(), "data/" + safeFileName(title));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static final String CONFIG_DIR = "plugins/hk/config";

    /** 规则配置目录（海阔"长按规则→设置"的 key-value，如 config.host），不存在则创建。 */
    public File getConfigDir() {
        Context ctx = appContext();
        File dir = ctx == null ? new File(CONFIG_DIR) : new File(ctx.getFilesDir(), CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 某规则的配置文件（JSON 对象），不存在返回 null。 */
    public File getRuleConfigFile(String title) {
        File f = new File(getConfigDir(), safeFileName(title) + ".json");
        return f.exists() ? f : null;
    }

    /** 读某规则的配置 JSON 文本，不存在/读失败返回 "{}"。 */
    public String loadRuleConfig(String title) {
        try {
            File f = getRuleConfigFile(title);
            if (f == null) return "{}";
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(bytes, java.nio.charset.Charset.forName("UTF-8")).trim();
            return s.isEmpty() ? "{}" : s;
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    /** 写某规则的配置 JSON 文本（供后续"规则设置"页用）。失败回 false 并记日志，不许静默（批 fix349）。 */
    public boolean saveRuleConfig(String title, String json) {
        try {
            File f = new File(getConfigDir(), safeFileName(title) + ".json");
            java.nio.file.Files.write(f.toPath(),
                    (json == null || json.trim().isEmpty() ? "{}" : json.trim()).getBytes("UTF-8"));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "规则配置写入失败：" + title, t);
            return false;
        }
    }

    /**
     * 解析后尚未落盘的规则（含原始 JSON 文本，落盘时原样写入，不丢失未知字段）。
     * 一键导入先全部解析 → 弹窗让用户勾选 → 只把选中的 saveRule 落盘。
     */
    public static class ParsedRule {
        public final HkRule rule;
        public final String json;

        public ParsedRule(HkRule rule, String json) {
            this.rule = rule;
            this.json = json;
        }
    }

    /** .hkzip 包的解析结果：规则（未落盘）+ 附带资源（require.json / libs / data.zip 内条目）。 */
    public static class HkZipData {
        public final List<ParsedRule> rules = new ArrayList<>();
        public byte[] requireJson;
        public final Map<String, byte[]> libs = new LinkedHashMap<>();
        /** data.zip 内条目：key 为包内相对路径（如 plugins/globalmap.js），原样带出 */
        public final Map<String, byte[]> dataFiles = new LinkedHashMap<>();
        /** data2.zip 内条目（help_api 小程序包约定：只参与分享/导入、不参与备份恢复）。 */
        public final Map<String, byte[]> data2Files = new LinkedHashMap<>();
    }

    /**
     * 某规则在外部 Documents 下的数据目录（hiker://files/data/&lt;规则名&gt;/ 的落盘根，
     * 与 hiker://files/ 外部映射同根；8.83 往返语义）。
     */
    public File getExternalDataDir(String title) {
        Context ctx = appContext();
        File docs = ctx == null ? null : ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File dir = new File(docs == null ? new File("Documents") : docs, "data/" + safeFileName(title));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * hiker://files/ 路径归一（星辰规则引擎 getFilePath 的 2b 等价实现；规则引擎是
     * 阶段 3，本步不得引用它）：hiker://files/ 剥前缀后拼到外部 Documents 根；
     * file:// 只剥前缀；其余原样返回。另加星辰没有的防 .. 越界：解析后逃出根目录时
     * 回落到 basename（合集解压与 .hkshell COPY 的目标都走这里，用户确认后的导入
     * 链路也不应写到包外）。
     */
    private static String resolveHikerFilesPath(String spec) {
        if (spec == null) return "";
        String path = spec;
        String root = null;
        if (path.startsWith("hiker://files/")) {
            path = path.substring("hiker://files/".length());
            Context ctx = App.get();
            File docs = ctx == null ? null : ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            root = docs == null ? "Documents" : docs.getAbsolutePath();
        } else if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        } else {
            return path;
        }
        File out = new File(root, path);
        try {
            String canonicalRoot = new File(root).getCanonicalPath();
            String canonical = out.getCanonicalPath();
            if (!canonical.equals(canonicalRoot) && !canonical.startsWith(canonicalRoot + File.separator)) {
                out = new File(root, new File(path).getName());
            }
        } catch (Exception ignored) {
        }
        return out.getAbsolutePath();
    }

    /** data2 对应根（hiker://files/data2/<规则名>/ 的落盘根，help_api 小程序包约定）。 */
    public File getExternalData2Dir(String title) {
        Context ctx = appContext();
        File docs = ctx == null ? null : ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File dir = new File(docs == null ? new File("Documents") : docs, "data2/" + safeFileName(title));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ── 规则本体 fastjson parseString 收敛（面②#3，136 批 W2） ──────────────
    // 官入仓走 fastjson parseObject（ArticleListRuleModel.smali:430），其 String
    // 字段反序列化（DefaultJSONParser.parseString）遇非串 token 一律宽容收敛、
    // 永不抛 Expected-a-string；咱家 Gson 遇 BEGIN_OBJECT/BEGIN_ARRAY 直接整单
    // 失败。本区与规则 JS 侧的同源收敛层同形（该处
    // 只服务 MY_RULE scope 一路），抽成全仓可复用的静态入口：凡裸 Gson 解
    // HkRule 的入口（本类 parseJson/同名清理/getRules/getRule、订阅合并、
    // mobile 三处资产/JS 规则入口）一律先经此收敛再交 Gson。收敛只碰 HkRule
    // 的 String 字段（按 @SerializedName 匹配、反射读声明，新增 String 字段
    // 自动纳入；@JsonAdapter 字段如 version 自有宽松适配器跳过）；纯串原样、
    // null 原样、非 String 字段（weight 等）一字不动，末步走回 Gson 串路以
    // 保非 String 字段的旧抛错语义（树路会静默截断）。

    /** 规则 JSON 串 → HkRule（先收敛 String 字段再 Gson 串路解）。 */
    public static HkRule parseRuleJson(String json) {
        if (json == null) return null;
        JsonElement el = JsonParser.parseString(json);
        if (el != null && el.isJsonObject()) {
            coerceRuleStringFields(el.getAsJsonObject());
        }
        if (el == null) return null;
        return new Gson().fromJson(el.toString(), HkRule.class);
    }

    /** 规则 JSON 对象 → HkRule（同上；先深拷贝再收敛，不改调用方的对象——
     * 订阅合并路的 obj 还要作落盘原文与 group 写回载体，原地收敛会把原文
     * 里的复合值一并改写）。 */
    public static HkRule parseRuleJson(JsonObject obj) {
        if (obj == null) return null;
        JsonObject copy = obj.deepCopy();
        coerceRuleStringFields(copy);
        return new Gson().fromJson(copy.toString(), HkRule.class);
    }

    /** 把 JSON 对象里 HkRule 的 String 型字段的非串值收敛成串：对象/数组→
     * 官形 JSON 文本（fastJsonText），数字→官 parseString 词形
     * （fastJsonScalarText），布尔→"true"/"false"；字符串与 null 原样不动。 */
    public static void coerceRuleStringFields(JsonObject obj) {
        for (java.lang.reflect.Field f : HkRule.class.getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            if (f.isAnnotationPresent(com.google.gson.annotations.JsonAdapter.class)) continue;
            com.google.gson.annotations.SerializedName sn =
                    f.getAnnotation(com.google.gson.annotations.SerializedName.class);
            String name = sn != null ? sn.value() : f.getName();
            if (!obj.has(name)) continue;
            JsonElement v = obj.get(name);
            if (v == null || v.isJsonNull()) continue;
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) continue;
            if (v.isJsonObject() || v.isJsonArray()) obj.addProperty(name, fastJsonText(v));
            else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber())
                obj.addProperty(name, fastJsonScalarText(v.getAsString()));
            else obj.addProperty(name, v.getAsString());
        }
    }

    /** 官 parseString 顶层口径的单值转串（面②各面共用）：串原样、数字→
     * fastJsonScalarText 词形、布尔→"true"/"false"、对象/数组→fastJsonText
     * JSON 文本；null/JsonNull 回 null。 */
    public static String fastJsonValueText(JsonElement v) {
        if (v == null || v.isJsonNull()) return null;
        if (v.isJsonPrimitive()) {
            if (v.getAsJsonPrimitive().isString()) return v.getAsString();
            if (v.getAsJsonPrimitive().isNumber()) return fastJsonScalarText(v.getAsString());
            return String.valueOf(v.getAsJsonPrimitive().getAsBoolean());
        }
        return fastJsonText(v);
    }

    /** 官 parseString 的数字支（官 fastJsonScalarText 同文）：
     * 整数词形逐字不变；浮点词形经 Double 归一（1.50→"1.5"、1e+21→"1.0E21"）。 */
    private static String fastJsonScalarText(String raw) {
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) return raw;
        return Double.toString(Double.parseDouble(raw));
    }

    /** 官 parseString 对对象/数组 token 的产出（官 fastJsonText
     * 同文）：先读成 HashMap/ArrayList 承载再按官序列化写出（键哈希序、
     * 嵌套浮点经 NumberCodec 剥尾 ".0"、非有限数写 null）。 */
    private static String fastJsonText(JsonElement el) {
        StringBuilder sb = new StringBuilder();
        writeFastJsonValue(toFastJsonValue(el), sb);
        return sb.toString();
    }

    private static Object toFastJsonValue(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) {
            Map<String, Object> map = new java.util.HashMap<>(16);
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), toFastJsonValue(e.getValue()));
            }
            return map;
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement c : el.getAsJsonArray()) list.add(toFastJsonValue(c));
            return list;
        }
        com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isString()) return p.getAsString();
        if (p.isBoolean()) return p.getAsBoolean();
        String raw = p.getAsString();
        if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
            return Double.parseDouble(raw);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return new java.math.BigInteger(raw);
        }
    }

    private static void writeFastJsonValue(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeFastJsonString((String) v, sb);
        } else if (v instanceof Double) {
            double d = (Double) v;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                String s = Double.toString(d);
                if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
                sb.append(s);
            }
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeFastJsonString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeFastJsonValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object c : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeFastJsonValue(c, sb);
            }
            sb.append(']');
        }
    }

    /** 官 SerializeWriter 双引号串转义（官 writeFastJsonString 同文）。 */
    private static void writeFastJsonString(String s, StringBuilder sb) {
        final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7',
                '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\f': sb.append("\\f"); break;
                case '\r': sb.append("\\r"); break;
                case '\u2028': sb.append("\\u2028"); break;
                default:
                    if (c < 0x20 || (c >= 0x7F && c < 0xA0)) {
                        sb.append("\\u");
                        sb.append(DIGITS[(c >> 12) & 0xF]);
                        sb.append(DIGITS[(c >> 8) & 0xF]);
                        sb.append(DIGITS[(c >> 4) & 0xF]);
                        sb.append(DIGITS[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * 从 JSON 文本解析规则：解析 → 校验，不落盘。
     *
     * @return 解析通过的规则
     * @throws Exception 解析失败或校验不通过时抛出，message 可直接提示用户
     */
    public HkRule parseJson(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("规则内容为空");
        String t = json.trim();
        // 形态预检：非对象开头的直接给人话错误，不把 Gson 原文（Expected BEGIN_OBJECT but was STRING…）抛给用户
        if (!t.startsWith("{")) {
            if (t.startsWith("[")) throw new IllegalArgumentException("内容是规则数组，请用批量导入");
            String head = t.length() > 30 ? t.substring(0, 30) + "…" : t;
            throw new IllegalArgumentException("内容不是规则 JSON（开头为：" + head + "），可能是口令已失效或云端内容已更换");
        }
        HkRule rule;
        try {
            rule = parseRuleJson(t);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则 JSON 解析失败：" + e.getMessage());
        }
        if (rule == null) throw new IllegalArgumentException("规则 JSON 解析失败");
        String error = rule.validate();
        if (error != null) throw new IllegalArgumentException(error);
        return rule;
    }

    /**
     * 规则落盘戳：每次 saveRule/delete 成功后 +1。
     * 供持有规则列表快照的页面（HkPageActivity）判断「我这份列表是不是已经过期」，
     * 变了才整表重读；没变不重读，避免回页重建列表丢滚动位置。
     */
    private static final java.util.concurrent.atomic.AtomicLong SAVE_STAMP =
            new java.util.concurrent.atomic.AtomicLong();

    public static long saveStamp() {
        return SAVE_STAMP.get();
    }

    /** 把解析好的规则落盘（保留原始 JSON 文本，不丢失未知字段）。 */
    public void saveRule(HkRule rule, String rawJson) throws Exception {
        if (rule == null) throw new IllegalArgumentException("规则为空");
        if (rawJson == null || rawJson.isEmpty()) rawJson = new Gson().toJson(rule);
        File target = new File(getDir(), safeFileName(rule.getTitle()) + ".json");
        // 同标题只许一份：先清掉其它文件名下标题相同的旧副本（历史遗留/改名残留），
        // 否则 getRules 去重按文件顺序取舍，导入新版后仍可能读到旧的那份。
        deleteSameTitleFiles(rule.getTitle(), target.getName());
        Files.write(target.toPath(), rawJson.getBytes(StandardCharsets.UTF_8));
        SAVE_STAMP.incrementAndGet();
        HkSettingConfig.nowLoadRulesTime = System.currentTimeMillis();
        rule.setEnabled(isEnabled(rule.getTitle()));
    }

    /** 删除规则目录里「JSON 标题与 title 相同、但文件名不是 keepName」的文件（逐个解析比对，坏文件跳过）。 */
    private void deleteSameTitleFiles(String title, String keepName) {
        if (title == null || title.isEmpty()) return;
        try {
            File[] files = getDir().listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) return;
            for (File f : files) {
                if (f.getName().equals(keepName)) continue;
                try {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    HkRule other = parseRuleJson(new String(bytes, StandardCharsets.UTF_8));
                    if (other != null && title.equals(other.getTitle())) f.delete();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 JSON 文本导入规则：解析 → 校验 → 落盘（保留原始 JSON 文本，不丢失未知字段）。
     *
     * @return 导入成功的规则
     * @throws Exception 解析失败或校验不通过时抛出，message 可直接提示用户
     */
    public HkRule importJson(String json) throws Exception {
        HkRule rule = parseJson(json);
        saveRule(rule, json);
        return rule;
    }

    /** 从文件导入规则。 */
    public HkRule importFile(File src) throws Exception {
        if (src == null || !src.exists()) throw new IllegalArgumentException("规则文件不存在");
        byte[] bytes = Files.readAllBytes(src.toPath());
        return importJson(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * 从 zip 包解析规则（不落盘）：解压后找出所有 .json（根目录或子目录均可），逐个解析。
     * 一个 zip 可包含多个规则；zip 内条目也可能是规则数组；单个损坏的条目只跳过、不影响其它。
     *
     * @return 解析成功的规则列表
     * @throws Exception zip 损坏 / 其中没有合法规则时抛出，message 可直接提示用户
     */
    public List<ParsedRule> parseZip(File zipFile) throws Exception {
        if (zipFile == null || !zipFile.exists()) throw new IllegalArgumentException("zip 文件不存在");
        List<ParsedRule> parsed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 跳过 macOS 垃圾与隐藏文件
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith(".")) continue;
                if (name.startsWith("__MACOSX/")) continue;
                // 只认 .json
                if (!base.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                byte[] bytes = readStream(zip.getInputStream(entry));
                try {
                    // 条目可能是单个规则，也可能是规则数组
                    parsed.addAll(parseJsonList(new String(bytes, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    failed.add(base + "（" + e.getMessage() + "）");
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("不是有效的 zip 包或文件已损坏");
        }
        if (parsed.isEmpty()) {
            if (!failed.isEmpty()) throw new IllegalArgumentException("zip 中没有合法规则：" + failed.get(0));
            throw new IllegalArgumentException("zip 中没有找到 rule.json");
        }
        return parsed;
    }

    /**
     * 从 zip 包导入规则：解压后找出所有 rule.json（根目录或子目录均可），逐个导入。
     * 一个 zip 可包含多个规则；zip 内条目也可能是规则数组；单个损坏的条目只跳过、不影响其它。
     *
     * @return 导入成功的规则列表
     * @throws Exception zip 损坏 / 其中没有合法规则时抛出，message 可直接提示用户
     */
    public List<HkRule> importZip(File zipFile) throws Exception {
        List<ParsedRule> parsed = parseZip(zipFile);
        List<HkRule> imported = new ArrayList<>();
        for (ParsedRule p : parsed) {
            saveRule(p.rule, p.json);
            imported.add(p.rule);
        }
        return imported;
    }

    /**
     * 从 .hkzip 包解析：内容为 rule.json + require.json + libs.zip。
     * rule.json 只解析不落盘；require.json 与 libs.zip 缓存在返回对象中，
     * 用户勾选后只把选中规则的附带资源落盘到 data/&lt;规则名&gt;/。
     *
     * @return 解析结果
     * @throws Exception 包损坏 / 其中没有合法规则时抛出
     */
    public HkZipData parseHkZip(File hkzipFile) throws Exception {
        if (hkzipFile == null || !hkzipFile.exists()) throw new IllegalArgumentException("hkzip 文件不存在");
        HkZipData data = new HkZipData();
        List<String> failed = new ArrayList<>();
        try (ZipFile zip = new ZipFile(hkzipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith(".") || name.startsWith("__MACOSX/")) continue;
                String lower = base.toLowerCase(Locale.ROOT);
                byte[] bytes = readStream(zip.getInputStream(entry));
                if (lower.equals("require.json")) {
                    data.requireJson = bytes;
                } else if (lower.equals("libs.zip")) {
                    // 内层 zip：解出所有 js 库
                    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory()) {
                                String en = e.getName();
                                String eb = en.substring(en.lastIndexOf('/') + 1);
                                if (!eb.startsWith(".") && eb.toLowerCase(Locale.ROOT).endsWith(".js")) {
                                    data.libs.put(eb, readStream(zin));
                                }
                            }
                            zin.closeEntry();
                        }
                    } catch (Exception e) {
                        // 内层 libs.zip 坏包不许静默缺库：整包判失败回调用方（批 fix349）。
                        throw new Exception("libs.zip 解包失败", e);
                    }
                } else if (lower.equals("data.zip") || lower.equals("data2.zip")) {
                    // 内层 zip：data/ 下的规则数据文件（plugins/、template/ 等），相对路径原样带出；
                    // data2.zip 同构（help_api：只参与分享/导入、不参与备份恢复，落 data2 根）。
                    Map<String, byte[]> sink = lower.equals("data2.zip") ? data.data2Files : data.dataFiles;
                    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory()) {
                                String en = e.getName().replace('\\', '/');
                                String eb = en.substring(en.lastIndexOf('/') + 1);
                                // 防路径穿越：跳过绝对路径、含 .. 与隐藏文件条目
                                if (!en.startsWith("/") && !en.contains("..") && !eb.startsWith(".")) {
                                    sink.put(en, readStream(zin));
                                }
                            }
                            zin.closeEntry();
                        }
                    } catch (Exception e) {
                        // 内层 data.zip 坏包同样不许静默缺文件：整包判失败（与 libs.zip 同口径）。
                        throw new Exception(lower + " 解包失败", e);
                    }
                } else if (lower.endsWith(".json")) {
                    try {
                        data.rules.addAll(parseJsonList(new String(bytes, StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        failed.add(base + "（" + e.getMessage() + "）");
                    }
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("不是有效的 hkzip 包或文件已损坏");
        }
        if (data.rules.isEmpty()) {
            if (!failed.isEmpty()) throw new IllegalArgumentException("hkzip 中没有合法规则：" + failed.get(0));
            throw new IllegalArgumentException("hkzip 中没有找到 rule.json");
        }
        return data;
    }

    /**
     * 某规则的登记簿目录（8.83 ShareRuleUtil 导入落点／RequireUtils 读路径同根）：
     * 外部 Documents 根下 rules/files/&lt;规则名&gt;/，与 RequireUtils.getFilesDir()
     * （getExternalFilesDir(DOCUMENTS)＋"/rules/files/"）逐字同根；标题照
     * RequireUtils 原样拼入（其读路径不做文件名净化）。
     */
    public File getRequireFilesDir(String title) {
        Context ctx = appContext();
        File docs = ctx == null ? null : ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File dir = new File(docs == null ? new File("Documents") : docs,
                "rules/files/" + (title == null ? "" : title));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 全局库目录（8.83 ShareRuleUtil 导入 libs 落点／require0 的 md5 直算目录同根）：外部 Documents 根下 libs/。 */
    public File getGlobalLibsDir() {
        Context ctx = appContext();
        File docs = ctx == null ? null : ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File dir = new File(docs == null ? new File("Documents") : docs, "libs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 把 .hkzip 的附带资源（require.json / libs / data.zip）落盘，供 require() 本地加载与规则读文件。 */
    public boolean saveHkZipAssets(HkZipData data, List<HkRule> rules) {
        boolean ok = true;
        if (data == null || rules == null) return false;
        for (HkRule rule : rules) {
            if (data.requireJson != null) {
                // 环13 对官（8.83 ShareRuleUtil.lambda$importRuleByZip$3）：包内
                // require.json 落登记簿根 rules/files/<title>/require.json——目标
                // 已存在先删、再 copy 覆盖（导入即整表替换该规则的登记簿）；
                // 旧形落内部 data/<title>/，RequireUtils 读外部根那份、恒看不见。
                try {
                    File target = new File(getRequireFilesDir(rule.getTitle()), "require.json");
                    if (target.exists()) target.delete();
                    Files.write(target.toPath(), data.requireJson);
                } catch (Exception e) {
                    Log.e(TAG, "require.json 落盘失败：" + rule.getTitle(), e);
                    ok = false;
                }
            }
            if (!data.libs.isEmpty()) {
                // 环13 对官：包内 libs 逐个 copy（覆盖）到全局 libs/（外部根
                // libs/），require0/fc/$.require 按 md5 直算即命中；旧形落规则
                // 数据目录 libs/，全局直算恒 miss、靠自造回退层补偿（已随本环摘除）。
                File libsDir = getGlobalLibsDir();
                for (Map.Entry<String, byte[]> e : data.libs.entrySet()) {
                    try {
                        Files.write(new File(libsDir, e.getKey()).toPath(), e.getValue());
                    } catch (Exception ex) {
                        Log.e(TAG, "包内库文件落盘失败：" + e.getKey(), ex);
                        ok = false;
                    }
                }
            }
            if (!data.dataFiles.isEmpty()) {
                // 8.83 往返：data.zip 内容按包内相对路径落到外部 Documents/data/<规则名>/，
                // 与 hiker://files/data/<规则名>/ 的外部映射同根
                File extDataDir = getExternalDataDir(rule.getTitle());
                for (Map.Entry<String, byte[]> e : data.dataFiles.entrySet()) {
                    try {
                        String rel = e.getKey().replace('\\', '/');
                        if (rel.contains("..") || rel.startsWith("/")) continue;
                        File out = new File(extDataDir, rel);
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        Files.write(out.toPath(), e.getValue());
                    } catch (Exception ex) {
                        Log.e(TAG, "包内资源文件落盘失败：" + e.getKey(), ex);
                        ok = false;
                    }
                }
            }
            if (!data.data2Files.isEmpty()) {
                // data2.zip 同 data.zip 落法，根换 Documents/data2/<规则名>/
                // （hiker://files/data2/<规则名>/ 的外部映射同根）。
                File extData2Dir = getExternalData2Dir(rule.getTitle());
                for (Map.Entry<String, byte[]> e : data.data2Files.entrySet()) {
                    try {
                        String rel = e.getKey().replace('\\', '/');
                        if (rel.contains("..") || rel.startsWith("/")) continue;
                        File out = new File(extData2Dir, rel);
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        Files.write(out.toPath(), e.getValue());
                    } catch (Exception ex) {
                        Log.e(TAG, "包内资源文件落盘失败：" + e.getKey(), ex);
                        ok = false;
                    }
                }
            }
        }
        return ok;
    }

    /**
     * 从 .hkzip 包导入：内容为 rule.json + require.json + libs.zip + data.zip。
     * rule.json 走导入；require.json 落登记簿根（外部 rules/files/&lt;规则名&gt;/）、
     * libs.zip 解压到全局 libs/（环13 对官落点），供 require() 本地优先加载（无网络时也能用）；data.zip 按包内相对路径解到
     * 外部 Documents/data/&lt;规则名&gt;/（8.83 往返：hiker://files/data/&lt;规则名&gt;/ 可读回）。
     *
     * @return 导入成功的规则列表
     */
    public List<HkRule> importHkZip(File hkzipFile) throws Exception {
        HkZipData data = parseHkZip(hkzipFile);
        List<HkRule> imported = new ArrayList<>();
        for (ParsedRule p : data.rules) {
            saveRule(p.rule, p.json);
            imported.add(p.rule);
        }
        // 落盘 require.json 与 libs，供 require() 本地加载
        saveHkZipAssets(data, imported);
        return imported;
    }

    /**
     * hkzip 小程序包内容判：zip 内含 require.json／libs.zip／data.zip／data2.zip
     * （任意层级、按基名认，与 parseHkZip 的条目分发同口径）或根目录 rule.json，
     * 即按 hkzip 包走 parseHkZip。包型认内容不认文件名——递送方给不出带后缀
     * 显示名时旧形按名判假落 parseZip，require.json 与 libs.zip 被静默丢弃
     * （账 hk-hkzip-home-missing-dig-20261111 §一.4）。非 zip／损坏一律判假，
     * 由调用方按普通 zip 路（parseZip）继续分发并在其处报错。
     */
    public static boolean isHkZipPackage(File f) {
        if (f == null || !f.exists()) return false;
        try (ZipFile zip = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (base.equals("require.json") || base.equals("libs.zip")
                        || base.equals("data.zip") || base.equals("data2.zip")) return true;
                if (name.equalsIgnoreCase("rule.json")) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 合集包判（D#12，复核 M7 订正）：.hkpkg 后缀直判；名含「合集」须是 zip 且包内
     * 无根 rule.json 才算合集——带根 rule.json 的是单个小程序包，名里有「合集」
     * 二字也不能拆条分发（否则其 data.zip/libs.zip 附带资源会被当独立条目喂错路
     * 全丢）；非 zip 的同名文件（.txt 云口令等）永不入此路。
     */
    public static boolean isCollectionPackage(File f, String lowerName) {
        if (f == null || lowerName == null) return false;
        if (lowerName.endsWith(".hkpkg")) return true;
        if (!lowerName.contains("合集") || !lowerName.endsWith(".zip")) return false;
        try (ZipFile zip = new ZipFile(f)) {
            return zip.getEntry("rule.json") == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 合集包文件名清洗（8.83 ShareRuleUtil.importPackageByZip 本体，smali .line
     * 406–424）：依次 replace 掉 "(1)"…"(9)"、".hk合集"、".hkpkg"、".zip"、
     * "_fileSelect_" 得 cleanName，解压目录名＝cleanName + ".hk合集"。
     */
    public static String collectionCleanName(String fileName) {
        String clean = fileName == null ? "" : fileName;
        for (int i = 1; i <= 9; i++) clean = clean.replace("(" + i + ")", "");
        clean = clean.replace(".hk合集", "").replace(".hkpkg", "")
                .replace(".zip", "").replace("_fileSelect_", "");
        return clean;
    }

    /**
     * 小程序合集包导入（8.83 ShareRuleUtil.importPackageByZip＋lambda$7/$6，
     * dig2-entry-formats §5.1 定谳）：官形＝只解压落盘＋开文件浏览面让用户逐个
     * 点选，本步对包内一切条目（规则包/.hkshell/图片/文本/未知）零自动处置——
     * 旧形「逐条目自动导入＋导入即自动执行 .hkshell COPY」是自家分叉，批C3 摘除。
     * 本体：文件不存在静默回 null；目标目录＝<根>/_cache/<cleanName>.hk合集，
     * 已存在先整清；mkdirs 失败抛「创建目录<路径>失败」；解压异常抛「出错：<msg>」
     * （已解出的半成品留目录不清理、不导入，官同形）。返回值＝解压目录，
     * 点选分发在 HkPageActivity 的合集浏览面（官 FileBrowserActivity 同位）。
     */
    public File extractCollectionPackage(File zipFile) throws Exception {
        if (zipFile == null || !zipFile.exists()) return null;
        File target = new File(resolveHikerFilesPath(
                "hiker://files/_cache/" + collectionCleanName(zipFile.getName()) + ".hk合集"));
        if (target.exists()) deleteRecursive(target);
        if (!target.exists() && !target.mkdirs()) {
            throw new IllegalArgumentException("创建目录" + target.getAbsolutePath() + "失败");
        }
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..")) continue;
                File out = new File(target, name);
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.write(out.toPath(), readStream(zip.getInputStream(entry)));
            }
        } catch (Exception e) {
            // 官 lambda$7 整体 catch Exception → toast「出错：<msg>」（半成品留目录不清理）
            throw new IllegalArgumentException("出错：" + e.getMessage());
        }
        return target;
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    /**
     * .hkshell 的 js: 形判（8.83 FileBrowserActivity$1.lambda$onClick$3）：确认面
     * 输入（全文，可经用户编辑）startsWith "js:" 走 executeShellByJs、否则走
     * executeShell（COPY 形）。判据对原文不 trim（官同形）。
     */
    public static boolean isJsShell(String text) {
        return text != null && text.startsWith("js:");
    }

    /**
     * .hkshell COPY 形执行（8.83 FileBrowserActivity.executeShell，smali .line
     * 690–724）：只在用户点中 .hkshell 并过确认面后由浏览面调用（旧形「合集导入
     * 时自动执行」已随 importPackageByZip 改官形摘除）。按 \n 切行，只处理
     * toUpperCase 后 startsWith "COPY" 的行：按空格切段，源＝new File(baseDir,
     * 源段)（不存在静默跳过），目标经本类 resolveHikerFilesPath 归一；源为文件时
     * 目标以 / 结尾视为目录保留原名、否则为目标全路径；源为文件夹时目标为目录
     * （以 / 结尾时再拼源文件夹名）保留内部结构。逐行异常只记不中断（官 Timber.e
     * 同形，此处静默续行）。
     *
     * @return 实际拷贝的文件数（官终局 toast「执行完成，已拷贝<N>个文件」）
     */
    public static int executeHkShellText(String text, File baseDir) {
        if (text == null || baseDir == null) return 0;
        int count = 0;
        for (String rawLine : text.split("\n")) {
            if (!rawLine.toUpperCase(Locale.ROOT).startsWith("COPY")) continue;
            String[] parts = rawLine.split(" ");
            // 官 .line 695 if-eq 3：切段数恰为 3 才处理，非 3 整行不理（复核订正）
            if (parts.length != 3) continue;
            try {
                count += copyShellEntry(new File(baseDir, parts[1]), parts[2]);
            } catch (Exception ignored) {
                // 逐行异常不中断（官同形）
            }
        }
        return count;
    }

    private static int copyShellEntry(File src, String dstSpec) throws Exception {
        if (src == null || !src.exists() || dstSpec == null || dstSpec.isEmpty()) return 0;
        String dstPath = resolveHikerFilesPath(dstSpec);
        boolean dstIsDir = dstSpec.endsWith("/") || dstSpec.endsWith("\\");
        if (src.isFile()) {
            File target = dstIsDir ? new File(dstPath, src.getName()) : new File(dstPath);
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(target.toPath(), Files.readAllBytes(src.toPath()));
            return 1;
        }
        File base = dstIsDir ? new File(dstPath, src.getName()) : new File(dstPath);
        List<File> files = new ArrayList<>();
        collectFiles(src, files);
        int count = 0;
        for (File f : files) {
            String rel = src.toPath().relativize(f.toPath()).toString();
            File target = new File(base, rel);
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(target.toPath(), Files.readAllBytes(f.toPath()));
            count++;
        }
        return count;
    }

    /** 经典 IO 读流（API 24 可用，不依赖 java.nio）。 */
    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /**
     * 解析 JSON 文本（不落盘）：兼容单个规则对象与规则数组（一个文件多个小程序）。
     * 数组中单个损坏/不合规的条目只跳过、不影响其它。
     *
     * @return 解析成功的规则列表（单个对象时为 1 个元素的列表）
     * @throws Exception 没有任何合法规则时抛出，message 可直接提示用户
     */
    public List<ParsedRule> parseJsonList(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("规则内容为空");
        String t = json.trim();
        // 阶段 6：非 JSON 文本的云口令/分享包装分发（isCloudCode/parseByCloudCode/
        // unwrapShareText）随云口令区一起补；本步非 {、[ 开头的文本由下行 parseJson
        // 按「内容不是规则 JSON」报错。
        if (!t.startsWith("[")) {
            List<ParsedRule> single = new ArrayList<>();
            HkRule rule = parseJson(t);
            single.add(new ParsedRule(rule, t));
            return single;
        }
        JsonArray arr;
        try {
            arr = JsonParser.parseString(t).getAsJsonArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("规则 JSON 数组解析失败：" + e.getMessage());
        }
        List<ParsedRule> parsed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Gson gson = new Gson();
        int idx = 0;
        for (JsonElement el : arr) {
            idx++;
            if (!el.isJsonObject()) {
                failed.add("第" + idx + "项不是对象");
                continue;
            }
            String title = "";
            try {
                if (el.getAsJsonObject().has("title")) title = el.getAsJsonObject().get("title").getAsString();
            } catch (Exception ignored) {
            }
            String label = title.isEmpty() ? "第" + idx + "项" : "「" + title + "」";
            try {
                String raw = gson.toJson(el);
                HkRule rule = parseJson(raw);
                parsed.add(new ParsedRule(rule, raw));
            } catch (Exception e) {
                failed.add(label + "：" + e.getMessage());
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("没有合法规则" + (failed.isEmpty() ? "" : "，" + failed.get(0)));
        }
        return parsed;
    }

    /**
     * 导入 JSON 文本：兼容单个规则对象与规则数组（一个文件多个小程序）。
     * 数组中单个损坏/不合规的条目只跳过、不影响其它。
     *
     * @return 导入成功的规则列表（单个对象时为 1 个元素的列表）
     * @throws Exception 没有任何合法规则时抛出，message 可直接提示用户
     */
    public List<HkRule> importJsonList(String json) throws Exception {
        List<ParsedRule> parsed = parseJsonList(json);
        List<HkRule> imported = new ArrayList<>();
        for (ParsedRule p : parsed) {
            saveRule(p.rule, p.json);
            imported.add(p.rule);
        }
        return imported;
    }

    /** 是否为 js: 规则文本（以 "js:" 开头，后面是 JS 代码）。 */
    public static boolean isJsRuleText(String text) {
        return text != null && text.trim().startsWith("js:");
    }

    /**
     * 从 JS 规则文本解析：内容以 "js:" 开头，后面是 JS 代码。
     * 包装成 rule.json（type=video，find_rule=js 内容），标题取自文件名。不落盘。
     *
     * @param jsContent js: 开头的规则文本
     * @param fileName  来源文件名（可为 null），用于取标题
     * @return 解析通过的规则
     * @throws Exception 格式错误或校验不通过时抛出
     */
    public ParsedRule parseJsRule(String jsContent, String fileName) throws Exception {
        if (jsContent == null || jsContent.trim().isEmpty()) throw new IllegalArgumentException("JS 规则内容为空");
        String t = jsContent.trim();
        if (!t.startsWith("js:")) throw new IllegalArgumentException("不是 js: 格式的规则");
        String title = fileName;
        if (title != null) {
            int slash = Math.max(title.lastIndexOf('/'), title.lastIndexOf('\\'));
            if (slash >= 0) title = title.substring(slash + 1);
            if (title.toLowerCase(Locale.ROOT).endsWith(".js")) title = title.substring(0, title.length() - 3);
            title = title.trim();
        }
        if (title == null || title.isEmpty()) title = "JS规则";
        JsonObject obj = new JsonObject();
        obj.addProperty("title", title);
        obj.addProperty("type", "video");
        obj.addProperty("url", "");
        obj.addProperty("find_rule", t);
        String json = new Gson().toJson(obj);
        return new ParsedRule(parseJson(json), json);
    }

    // 阶段 6：云口令取件与导出（HkRuleManager.java:1166-1888）在此补

    /**
     * 从 JS 规则文本导入：内容以 "js:" 开头，后面是 JS 代码。
     * 包装成 rule.json（type=video，find_rule=js 内容），标题取自文件名。
     *
     * @param jsContent js: 开头的规则文本
     * @param fileName  来源文件名（可为 null），用于取标题
     * @return 导入成功的规则
     */
    public HkRule importJsRule(String jsContent, String fileName) throws Exception {
        ParsedRule p = parseJsRule(jsContent, fileName);
        saveRule(p.rule, p.json);
        return p.rule;
    }

    /** 列出所有已导入规则（损坏的文件跳过，不影响整体；异常写入文件日志方便定位）。 */
    public List<HkRule> getRules() {
        List<HkRule> list = new ArrayList<>();
        File dir = getDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return list;
        // 同标题去重：保留最后落盘（mtime 最新）的那份，平局时标准文件名优先。
        // 旧口径「保留 listFiles 第一个」顺序不确定：目录里只要有同标题旧副本，
        // 就可能一直读旧的——导入新版看着没效果。此处只定取舍，物理清理由 saveRule 做。
        java.util.Map<String, HkRule> best = new LinkedHashMap<>();
        java.util.Map<String, Long> bestMtime = new java.util.HashMap<>();
        java.util.Map<String, Boolean> bestCanonical = new java.util.HashMap<>();
        for (File f : files) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                HkRule rule = parseRuleJson(new String(bytes, StandardCharsets.UTF_8));
                if (rule == null) continue;
                // 标题脏数据（空/"undefined"/"null"）时用文件名回退，保证 tab 有名可显示
                if (rule.getTitle().isEmpty()) {
                    String fn = f.getName();
                    if (fn.endsWith(".json")) fn = fn.substring(0, fn.length() - 5);
                    if (!fn.isEmpty() && !"unnamed".equals(fn)) rule.setTitle(fn);
                }
                String title = rule.getTitle();
                boolean canonical = f.getName().equals(safeFileName(title) + ".json");
                long mtime = f.lastModified();
                HkRule cur = best.get(title);
                if (cur != null) {
                    long curMtime = bestMtime.get(title);
                    boolean curCanonical = Boolean.TRUE.equals(bestCanonical.get(title));
                    boolean newer = mtime > curMtime
                            || (mtime == curMtime && canonical && !curCanonical);
                    if (!newer) continue;
                }
                rule.setEnabled(isEnabled(title));
                best.put(title, rule);
                bestMtime.put(title, mtime);
                bestCanonical.put(title, canonical);
            } catch (Exception e) {
                e.printStackTrace();
                logRuleError(f.getName(), e);
            }
        }
        list.addAll(best.values());
        return list;
    }

    /** 规则解析失败写入文件日志（/files/hk_rule_error.log），真机可查看定位。 */
    private void logRuleError(String fileName, Exception e) {
        try {
            Context ctx = appContext();
            if (ctx == null) return;
            File log = new File(ctx.getFilesDir(), "hk_rule_error.log");
            String msg = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date())
                    + " parse failed: " + fileName + " | " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
            java.nio.file.Files.write(log.toPath(), msg.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** 按标题取规则（标题即文件名）。 */
    public HkRule getRule(String title) {
        if (title == null) return null;
        File f = new File(getDir(), safeFileName(title) + ".json");
        if (!f.exists()) return null;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            HkRule rule = parseRuleJson(new String(bytes, StandardCharsets.UTF_8));
            if (rule != null) rule.setEnabled(isEnabled(rule.getTitle()));
            return rule;
        } catch (Exception e) {
            // 读/解析失败与「不存在」同回 null 是契约，但不许只剩 printStackTrace：
            // 走本类既有规则错误文件日志（hk_rule_error.log）＋Log.e 带标题/路径，
            // 与「文件不存在」（静默回 null）可区分（批 residual）。
            Log.e(TAG, "规则读取/解析失败：" + title + " path=" + f.getAbsolutePath(), e);
            logRuleError(f.getName(), e);
            return null;
        }
    }

    /** 规则文件最后修改时间（播放路由缓存失效判断用），文件不存在返回 -1。 */
    public long ruleModified(String title) {
        if (title == null) return -1;
        File f = new File(getDir(), safeFileName(title) + ".json");
        return f.exists() ? f.lastModified() : -1;
    }

    /** 删除规则（含启用开关记录与规则数据目录：kv/自带库都在里面，
     *  不清的话坏缓存删规则重装也甩不掉，UU视频实证）。 */
    public boolean delete(String title) {
        if (title == null) return false;
        File f = new File(getDir(), safeFileName(title) + ".json");
        boolean ok = !f.exists() || f.delete();
        if (ok) {
            SAVE_STAMP.incrementAndGet();
            HkSettingConfig.nowLoadRulesTime = System.currentTimeMillis();
        }
        try {
            deleteRecursive(getDataDir(title));
        } catch (Throwable ignored) {
        }
        // 8.83 规则管理面删规则的两口（clearVarByRule＋clearModuleCache）：
        // 文件删了，桥上该规则的内存变量与 require 模块缓存也须同删，
        // 否则装回同名规则会读到已删规则的旧账（账 hk-engine-singleton-land #10）。
        // 阶段 3：桥上该规则的内存变量与 require 模块缓存清理
        //（星辰规则引擎的 onRuleDeleted(title)）随规则引擎一起补。
        try {
            Context ctx = appContext();
            if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_ENABLED_PREFIX + title).apply();
        } catch (Exception e) {
            // 启用标记没摘掉不许报删净：装回同名规则会复活旧启用状态。
            // delete 本就回 boolean（HkErrorUtil 等调用方按成败消费），
            // 此失败并入 ok=false 报出去（批 residual）。
            Log.e(TAG, "删除规则时启用标记移除失败：" + title, e);
            ok = false;
        }
        return ok;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /** @return 是否真存上；失败回 false 并留痕，不许开关静默没拨动（批 residual）。 */
    public boolean setEnabled(String title, boolean enabled) {
        if (title == null) return false;
        try {
            Context ctx = appContext();
            if (ctx == null) return false;
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putBoolean(KEY_ENABLED_PREFIX + title, enabled).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "规则启用状态保存失败：" + title + " -> " + enabled, e);
            return false;
        }
    }

    /** 默认启用；只有用户手动关闭后才为 false。 */
    public boolean isEnabled(String title) {
        try {
            Context ctx = appContext();
            if (ctx == null) return true;
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED_PREFIX + title, true);
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }

    /** 只返回启用中的规则。 */
    public List<HkRule> getEnabledRules() {
        List<HkRule> all = getRules();
        List<HkRule> enabled = new ArrayList<>();
        for (HkRule r : all) if (r.isEnabled()) enabled.add(r);
        return enabled;
    }

    /** 文件名安全化：去非法字符，防路径穿越。public 供 HkStore.fileName() 委托，
     *  保持一份实现（星辰为 private）。 */
    public static String safeFileName(String title) {
        if (title == null) return "unnamed";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.isEmpty()) s = "unnamed";
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }
}
