package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.utils.Path;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.utils.LocalSource;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 小程序规则仓库基础，对应魔影小程序的 i22（见逆向报告 §7.11）。
 * <p>
 * 布局与魔影一致：规则本体在 filesDir/plugins/hk/&lt;标题&gt;.json，配置在
 * plugins/hk/config/，每条规则的私有数据在 plugins/hk/data/&lt;标题&gt;/。
 * 文件名用 N(title) 归一化：非法字符 → _，超 80 字符截断，空标题 → unnamed。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。读写都在调用线程做，不要在主线程调 list()。
 */
public class HkStore {

    private static final String TAG = "HkStore";
    private static final String HK_DIR = "plugins/hk";
    private static final int MAX_NAME_LEN = 80;

    /** 规则目录 filesDir/plugins/hk/，不存在就建出来，对应 i22.w()。 */
    public static File dir() {
        File file = new File(Path.files(), HK_DIR);
        if (!file.exists()) file.mkdirs();
        return file;
    }

    /** 配置目录 filesDir/plugins/hk/config/，对应 i22.u()。 */
    public static File configDir() {
        File file = new File(dir(), "config");
        if (!file.exists()) file.mkdirs();
        return file;
    }

    /** 某条规则的私有数据目录 filesDir/plugins/hk/data/&lt;标题&gt;/，对应 i22.v()。 */
    public static File dataDir(String title) {
        File file = new File(new File(dir(), "data"), fileName(title));
        if (!file.exists()) file.mkdirs();
        return file;
    }

    /**
     * 文件名归一化，对应 i22.N(String)：null → unnamed；[\\/:*?"&lt;&gt;|] → _；
     * 去首尾空白后为空 → unnamed；超 80 字符截断。
     */
    public static String fileName(String title) {
        if (title == null) return "unnamed";
        String name = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "unnamed";
        return name.length() > MAX_NAME_LEN ? name.substring(0, MAX_NAME_LEN) : name;
    }

    /** 某条规则对应的文件。 */
    public static File fileFor(String title) {
        return new File(dir(), fileName(title) + ".json");
    }

    /**
     * 保存一条规则，对应 i22.O() 的写盘部分：写到 plugins/hk/&lt;N(title)&gt;.json。
     * 同标题已存在于另一个文件名下时删掉旧文件（改标题后不留重复项）。
     * rawJson 为空时用规则对象重新序列化。
     */
    public static boolean save(HkRule rule, String rawJson) {
        if (rule == null || TextUtils.isEmpty(rule.getTitle())) return false;
        File target = fileFor(rule.getTitle());
        try {
            File[] files = dir().listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.getName().endsWith(".json") || file.equals(target)) continue;
                    HkRule other = parseFile(file);
                    if (other != null && rule.getTitle().equals(other.getTitle())) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
            String json = TextUtils.isEmpty(rawJson) ? rule.toJson() : rawJson;
            writeFile(target, json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + rule.getTitle(), e);
            return false;
        }
    }

    public static boolean save(HkRule rule) {
        return save(rule, null);
    }

    /** 按标题读一条规则，对应 i22.x()：文件不存在或解析失败返回 null。 */
    public static HkRule read(String title) {
        if (TextUtils.isEmpty(title)) return null;
        File file = fileFor(title);
        if (!file.exists()) return null;
        return parseFile(file);
    }

    /** 按标题读规则原文，文件不存在返回 null。 */
    public static String readRaw(String title) {
        if (TextUtils.isEmpty(title)) return null;
        File file = fileFor(title);
        if (!file.exists()) return null;
        return readFile(file);
    }

    /** 规则是否存在。 */
    public static boolean exists(String title) {
        return !TextUtils.isEmpty(title) && fileFor(title).exists();
    }

    /** 某条规则文件的最后修改时间，不存在返回 -1，对应 i22.M()。 */
    public static long lastModified(String title) {
        if (TextUtils.isEmpty(title)) return -1;
        File file = fileFor(title);
        return file.exists() ? file.lastModified() : -1;
    }

    /** 删一条规则（只删按标题算出的标准文件），返回是否删掉。 */
    public static boolean delete(String title) {
        if (TextUtils.isEmpty(title)) return false;
        File file = fileFor(title);
        return file.exists() && file.delete();
    }

    /**
     * 枚举全部规则，对应 i22.z()：解析 plugins/hk/ 下每个 .json；同标题重复时
     * 取最后修改时间更新的那个（时间相同取文件名与 N(title) 一致的那个）；
     * 规则里没 title 时用文件名（去 .json 后缀）补，文件名是 unnamed 的不补；
     * 解析失败的文件跳过并记一条日志（不写 hk_rule_error.log，那是魔影的
     * 调试文件，列表页阶段再决定要不要）。
     */
    public static List<HkRule> list() {
        List<HkRule> result = new ArrayList<>();
        File[] files = dir().listFiles();
        if (files == null) return result;
        Map<String, HkRule> byTitle = new LinkedHashMap<>();
        Map<String, Long> modified = new LinkedHashMap<>();
        Map<String, Boolean> exactName = new LinkedHashMap<>();
        for (File file : files) {
            if (!file.getName().endsWith(".json")) continue;
            HkRule rule = parseFile(file);
            if (rule == null) {
                Log.w(TAG, "parse failed, skipped: " + file.getName());
                continue;
            }
            if (TextUtils.isEmpty(rule.getTitle())) {
                String name = file.getName().substring(0, file.getName().length() - 5);
                if (!name.isEmpty() && !"unnamed".equals(name)) rule.setTitle(name);
            }
            String title = rule.getTitle();
            if (TextUtils.isEmpty(title)) continue;
            long last = file.lastModified();
            boolean exact = file.getName().equals(fileName(title) + ".json");
            HkRule prev = byTitle.get(title);
            if (prev == null) {
                byTitle.put(title, rule);
                modified.put(title, last);
                exactName.put(title, exact);
            } else {
                long prevLast = modified.get(title);
                boolean prevExact = Boolean.TRUE.equals(exactName.get(title));
                if (last > prevLast || (last == prevLast && exact && !prevExact)) {
                    byTitle.put(title, rule);
                    modified.put(title, last);
                    exactName.put(title, exact);
                }
            }
        }
        result.addAll(byTitle.values());
        return result;
    }

    /** 写一条规则配置（plugins/hk/config/&lt;标题&gt;.json），对应 i22.Q()；空内容写 {}。 */
    public static void saveConfig(String title, String json) {
        if (TextUtils.isEmpty(title)) return;
        try {
            String text = TextUtils.isEmpty(json) || json.trim().isEmpty() ? "{}" : json.trim();
            writeFile(new File(configDir(), fileName(title) + ".json"), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "saveConfig failed: " + title, e);
        }
    }

    /** 读一条规则配置，文件不存在返回 null。 */
    public static String readConfig(String title) {
        if (TextUtils.isEmpty(title)) return null;
        File file = new File(configDir(), fileName(title) + ".json");
        if (!file.exists()) return null;
        return readFile(file);
    }

    /** 小程序站源的 api/key 前缀，对应 i94.w(site) 的 hk:// 判定。 */
    public static final String SCHEME = "hk://";

    /** 最近一次导入失败的原因（importFiles/importOne/importJsonText 写，调用方读了展示）。 */
    private static volatile String lastError;

    /** 取最近一次导入失败的原因，没有失败返回 null。 */
    public static String lastError() {
        return lastError;
    }

    /** 导入缓存目录 cache/hk_import/（mirrorToCache、粘贴 JSON 的落盘位置）。 */
    public static File importCacheDir() {
        File file = new File(Path.cache(), "hk_import");
        if (!file.exists()) file.mkdirs();
        return file;
    }

    /** 规则依赖库目录 filesDir/plugins/hk/libs/（.hkzip 里 libs.zip 的解包位置）。 */
    public static File libsDir() {
        File file = new File(dir(), "libs");
        if (!file.exists()) file.mkdirs();
        return file;
    }

    /** 是不是小程序站源：api 或 key 以 hk:// 开头（与 i94.w(site) 的判定一致）。 */
    public static boolean isMiniapp(Site site) {
        if (site == null) return false;
        String api = site.getApi();
        if (!TextUtils.isEmpty(api) && api.startsWith(SCHEME)) return true;
        String key = site.getKey();
        return !TextUtils.isEmpty(key) && key.startsWith(SCHEME);
    }

    /** 从小程序站源取规则标题（api 去掉 hk:// 前缀）；不是小程序站源返回 ""。 */
    public static String titleOf(Site site) {
        if (site == null) return "";
        String api = site.getApi();
        if (!TextUtils.isEmpty(api) && api.startsWith(SCHEME)) return api.substring(SCHEME.length());
        String key = site.getKey();
        if (!TextUtils.isEmpty(key) && key.startsWith(SCHEME)) return key.substring(SCHEME.length());
        return "";
    }

    /** 规则 type 字段对应的站源名字里的类型：空或 other →「小程序」，其余原样。 */
    public static String typeNameOf(HkRule rule) {
        if (rule == null) return "小程序";
        String type = rule.getType();
        return TextUtils.isEmpty(type) || "other".equals(type) ? "小程序" : type;
    }

    /**
     * 一条规则对应的站源条目，对应 HkSiteAdapter.siteOf(g22)：key = api = hk://&lt;标题&gt;，
     * name = 标题⁽ʰᵏ⁾┃[类型]，type = 3，hide = 0，changeable = 1；searchable = 1 仅当
     * search_url 和 searchFind 都非空（g22.E()），否则 0。
     * 注意：setSearchable/setChangeable 必须用 Integer 重载——boolean 重载会在新 Site
     * 上拆箱 getSearchable()/getChangeable() 的 null 抛 NPE。
     */
    public static Site siteOf(HkRule rule) {
        Site site = new Site();
        site.setKey(SCHEME + rule.getTitle());
        site.setName(rule.getTitle() + "⁽ʰᵏ⁾┃[" + typeNameOf(rule) + "]");
        site.setApi(SCHEME + rule.getTitle());
        site.setType(3);
        site.setHide(0);
        site.setChangeable(1);
        site.setSearchable(!TextUtils.isEmpty(rule.getSearchUrl()) && !TextUtils.isEmpty(rule.getSearchFind()) ? 1 : 0);
        return site;
    }

    /**
     * 批量导入，对应 i22.importFiles：逐个调 importOne，返回成功登记的条数。
     * 空列表返回 0（不报错，调用方按“没有找到可导入的规则”处理）。
     */
    public static int importFiles(List<File> files) {
        lastError = null;
        if (files == null || files.isEmpty()) return 0;
        int added = 0;
        for (File file : files) {
            if (file == null || !file.exists()) continue;
            try {
                added += importOne(file);
            } catch (Exception e) {
                lastError = e.getMessage();
                Log.w(TAG, "import failed: " + file.getName(), e);
            }
        }
        return added;
    }

    /**
     * 导入单个文件，对应 i22.importOne：.zip/.hkzip → importZip，其余 → importJsonFile。
     * 失败时按报告里的 mirrorToCache 逻辑：复制到 cache/hk_import 再重试一次；还失败就抛，
     * 调用方记 lastError。成功返回登记的条数（单个文件通常是 1；JSON 数组文件可能多条）。
     */
    public static int importOne(File file) throws Exception {
        String name = file.getName().toLowerCase();
        try {
            if (name.endsWith(".zip") || name.endsWith(".hkzip")) return importZip(file);
            return importJsonFile(file);
        } catch (Exception e) {
            File cached = mirrorToCache(file);
            if (cached == null || cached.equals(file)) throw e;
            if (name.endsWith(".zip") || name.endsWith(".hkzip")) return importZip(cached);
            return importJsonFile(cached);
        }
    }

    /**
     * 导入单个 JSON 规则文件，对应 i22.C(file)：读文本 → 校验 → 存盘 → 登记站源。
     * 内容是 JSON 数组时按 i22.J(str) 逐条解析：坏的跳过记日志，好的一条条登记，
     * 返回成功登记的条数。单个规则校验不过时抛 validate() 的提示。
     */
    public static int importJsonFile(File file) throws Exception {
        String text = readFile(file);
        if (text == null || text.trim().isEmpty()) throw new Exception("规则文件为空");
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            int added = 0;
            JsonArray array = JsonParser.parseString(trimmed).getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                try {
                    HkRule rule = HkRule.parse(element.toString());
                    if (rule == null) continue;
                    String error = rule.validate();
                    if (error != null) {
                        Log.w(TAG, "skip invalid rule in array: " + error);
                        continue;
                    }
                    if (saveAndRegister(rule, element.toString())) added++;
                } catch (Exception e) {
                    Log.w(TAG, "skip bad rule in array", e);
                }
            }
            if (added == 0) throw new Exception("数组里没有可用的规则");
            return added;
        }
        HkRule rule = HkRule.parse(trimmed);
        if (rule == null) throw new Exception("不是有效的规则 JSON");
        String error = rule.validate();
        if (error != null) throw new Exception(error);
        if (!saveAndRegister(rule, trimmed)) throw new Exception("保存规则失败");
        return 1;
    }

    /**
     * 导入 .zip/.hkzip 规则包，对应 i22.A(file)：解包找 rule.json（根目录优先，其次任意
     * *&#47;rule.json，最后唯一的其它 .json，排除 require.json）→ 走单规则链路；
     * require.json 里的 file 路径改写到我们自己的 filesDir/plugins/hk/libs/ 再存配置
     * （原包里的路径是别家 App 的外部存储路径，不能直接用）；libs.zip 里的 .js 只取
     * 文件名解到 plugins/hk/libs/（zip-slip 只取 basename；解包失败不阻断主规则导入）。
     * require 的执行是第 7 步的事，这里只保证落盘位置 + 路径改写。
     */
    public static int importZip(File file) throws Exception {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            ZipEntry ruleEntry = findRuleEntry(zip);
            if (ruleEntry == null) throw new Exception("压缩包里没有找到 rule.json");
            String ruleJson = readZipEntry(zip, ruleEntry);
            if (ruleJson == null) throw new Exception("读取 rule.json 失败");
            HkRule rule = HkRule.parse(ruleJson);
            if (rule == null) throw new Exception("不是有效的规则 JSON");
            String error = rule.validate();
            if (error != null) throw new Exception(error);
            int added = saveAndRegister(rule, ruleJson) ? 1 : 0;
            if (added == 0) throw new Exception("保存规则失败");
            String title = rule.getTitle();
            ZipEntry requireEntry = zip.getEntry("require.json");
            if (requireEntry != null) {
                String requireJson = readZipEntry(zip, requireEntry);
                if (requireJson != null) saveConfig(title, rewriteRequirePaths(requireJson));
            }
            ZipEntry libsEntry = zip.getEntry("libs.zip");
            if (libsEntry != null) {
                // libs 解包失败不阻断主规则导入（第 7 步才用这些 .js）。
                try {
                    File temp = new File(importCacheDir(), "libs_" + System.nanoTime() + ".zip");
                    try (InputStream in = zip.getInputStream(libsEntry);
                         FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                    try {
                        extractLibs(temp);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        temp.delete();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "libs.zip extract failed", e);
                }
            }
            return added;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 粘贴 JSON 导入：先写 cache/hk_import/hk_paste_&lt;时间&gt;.json 再走 importFiles，
     * 与报告里粘贴导入的链路一致。内容为空记 lastError 返回 0。
     */
    public static int importJsonText(String text) {
        if (TextUtils.isEmpty(text)) {
            lastError = "规则内容为空";
            return 0;
        }
        try {
            File file = new File(importCacheDir(), "hk_paste_" + System.currentTimeMillis() + ".json");
            writeFile(file, text.getBytes(StandardCharsets.UTF_8));
            return importFiles(Collections.singletonList(file));
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.w(TAG, "importJsonText failed", e);
            return 0;
        }
    }

    /**
     * 同步已装规则，对应 i22.z()/syncSites()：枚举 plugins/hk/*.json，缺失的站源条目
     * 重新登记（已有的不动），返回新登记的条数。
     */
    public static int syncSites() {
        List<HkRule> rules = list();
        if (rules.isEmpty()) return 0;
        List<Site> sites = new ArrayList<>();
        for (HkRule rule : rules) sites.add(siteOf(rule));
        return LocalSource.mergeSites(sites);
    }

    /** 存一条规则 + 登记站源；存盘失败返回 false。 */
    private static boolean saveAndRegister(HkRule rule, String rawJson) {
        if (!save(rule, rawJson)) return false;
        List<Site> sites = new ArrayList<>();
        sites.add(siteOf(rule));
        LocalSource.mergeSites(sites);
        return true;
    }

    /**
     * 失败重试用的 mirrorToCache：把源文件复制到 cache/hk_import/imp_&lt;时间&gt;.&lt;后缀&gt;，
     * 复制失败或长度为 0 返回 null。对应报告 §7.1 的 mirrorToCache。
     */
    private static File mirrorToCache(File file) {
        try {
            String name = file.getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            File cached = new File(importCacheDir(), "imp_" + System.currentTimeMillis() + ext);
            try (FileInputStream in = new FileInputStream(file);
                 FileOutputStream out = new FileOutputStream(cached)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return cached.exists() && cached.length() > 0 ? cached : null;
        } catch (Exception e) {
            Log.w(TAG, "mirrorToCache failed: " + file.getName(), e);
            return null;
        }
    }

    /** 在 zip 里找规则入口：根目录 rule.json 优先，其次任意 rule.json，最后唯一的其它 .json（排除 require.json）。 */
    private static ZipEntry findRuleEntry(ZipFile zip) {
        ZipEntry root = zip.getEntry("rule.json");
        if (root != null) return root;
        ZipEntry single = null;
        int jsonCount = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (!name.toLowerCase().endsWith(".json")) continue;
            if (name.endsWith("/rule.json")) return entry;
            if ("require.json".equals(name) || name.endsWith("/require.json")) continue;
            jsonCount++;
            single = entry;
        }
        return jsonCount == 1 ? single : null;
    }

    /** 读 zip 里的一个条目成字符串，失败返回 null。 */
    private static String readZipEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "read zip entry failed: " + entry.getName(), e);
            return null;
        }
    }

    /**
     * require.json 里的 file 路径改写到我们自己的 filesDir：原包里的路径是别家 App
     * 的外部存储路径（如 /storage/emulated/0/Android/data/com.example.hikerview/...），
     * 在我们这台机器上不存在；只取文件名，拼到 plugins/hk/libs/ 下。
     * 解析失败返回原文（不阻断主规则导入）。
     */
    private static String rewriteRequirePaths(String requireJson) {
        try {
            JsonElement parsed = JsonParser.parseString(requireJson);
            if (!parsed.isJsonArray()) return requireJson;
            File libs = libsDir();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                JsonElement fileEl = obj.get("file");
                if (fileEl == null || !fileEl.isJsonPrimitive()) continue;
                String oldPath = fileEl.getAsString();
                String base = oldPath.substring(oldPath.lastIndexOf('/') + 1);
                if (base.isEmpty()) continue;
                obj.addProperty("file", new File(libs, base).getAbsolutePath());
            }
            return new Gson().toJson(parsed);
        } catch (Exception e) {
            Log.w(TAG, "rewrite require.json failed", e);
            return requireJson;
        }
    }

    /**
     * libs.zip 解包：只取 .js 条目，只取文件名（防 zip-slip：带 ../ 的路径只剩 basename），
     * 解到 plugins/hk/libs/。非 .js 的条目跳过。
     */
    private static void extractLibs(File zipFile) throws Exception {
        File target = libsDir();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.isEmpty() || !base.toLowerCase().endsWith(".js")) continue;
                File out = new File(target, base);
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) fos.write(buffer, 0, n);
                }
            }
        }
    }

    private static HkRule parseFile(File file) {
        String json = readFile(file);
        if (json == null) return null;
        return HkRule.parse(json);
    }

    private static String readFile(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "read failed: " + file.getName(), e);
            return null;
        }
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.flush();
        }
    }
}
