package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.utils.Path;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.utils.LocalSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 小程序规则仓库门面（移植阶段 2b 起）。
 * <p>
 * 存储/导入/列表/删除/启用等能力已由 {@link HkRuleManager}（按星辰移植）接管，
 * 本类只保留 public 方法签名不变、把调用委托过去，让现有调用方零改动编译通过。
 * HkRuleManager 没有的自有能力仍在本类：rc 缓存、kv/myvar、公共 KV、require 清单、
 * dataFile 读写、站源映射、importCacheDir/libsDir、lastError。
 * <p>
 * 布局与魔影一致：规则本体在 filesDir/plugins/hk/&lt;标题&gt;.json，配置在
 * plugins/hk/config/，每条规则的私有数据在 plugins/hk/data/&lt;标题&gt;/。
 * 文件名用 N(title) 归一化：非法字符 → _，超 80 字符截断，空标题 → unnamed。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。读写都在调用线程做，不要在主线程调 list()。
 */
public class HkStore {

    private static final String TAG = "HkStore";

    /** 规则目录 filesDir/plugins/hk/，不存在就建出来，对应 i22.w()。委托给 HkRuleManager。 */
    public static File dir() {
        return HkRuleManager.get().getDir();
    }

    /** 配置目录 filesDir/plugins/hk/config/，对应 i22.u()。委托给 HkRuleManager。 */
    public static File configDir() {
        return HkRuleManager.get().getConfigDir();
    }

    /** 某条规则的私有数据目录 filesDir/plugins/hk/data/&lt;标题&gt;/，对应 i22.v()。委托给 HkRuleManager。 */
    public static File dataDir(String title) {
        return HkRuleManager.get().getDataDir(title);
    }

    /**
     * 文件名归一化，对应 i22.N(String)：null → unnamed；[\\/:*?"&lt;&gt;|] → _；
     * 去首尾空白后为空 → unnamed；超 80 字符截断。委托给 HkRuleManager.safeFileName
     *（同一份实现，星辰为 private、这里为 public 供门面委托）。
     */
    public static String fileName(String title) {
        return HkRuleManager.safeFileName(title);
    }

    /** 某条规则对应的文件。 */
    public static File fileFor(String title) {
        return new File(dir(), fileName(title) + ".json");
    }

    /**
     * 保存一条规则，对应 i22.O() 的写盘部分：写到 plugins/hk/&lt;N(title)&gt;.json。
     * 同标题已存在于另一个文件名下时删掉旧文件（改标题后不留重复项）。
     * rawJson 为空时用规则对象重新序列化。委托给 HkRuleManager.saveRule。
     */
    public static boolean save(HkRule rule, String rawJson) {
        if (rule == null || TextUtils.isEmpty(rule.getTitle())) return false;
        try {
            HkRuleManager.get().saveRule(rule, rawJson);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + rule.getTitle(), e);
            return false;
        }
    }

    public static boolean save(HkRule rule) {
        return save(rule, null);
    }

    /** 按标题读一条规则，对应 i22.x()：文件不存在或解析失败返回 null。委托给 HkRuleManager.getRule。 */
    public static HkRule read(String title) {
        if (TextUtils.isEmpty(title)) return null;
        return HkRuleManager.get().getRule(title);
    }

    /** 按标题读规则原文，文件不存在返回 null。委托给 HkRuleManager.loadRuleJson。 */
    public static String readRaw(String title) {
        if (TextUtils.isEmpty(title)) return null;
        return HkRuleManager.get().loadRuleJson(title);
    }

    /** 规则是否存在。 */
    public static boolean exists(String title) {
        return !TextUtils.isEmpty(title) && fileFor(title).exists();
    }

    /** 某条规则文件的最后修改时间，不存在返回 -1，对应 i22.M()。委托给 HkRuleManager.ruleModified。 */
    public static long lastModified(String title) {
        if (TextUtils.isEmpty(title)) return -1;
        return HkRuleManager.get().ruleModified(title);
    }

    /**
     * 删一条规则，返回是否删掉。委托给 HkRuleManager.delete。
     * 2b 行为变化（星辰口径）：除规则文件外，还删规则数据目录 data/&lt;标题&gt;/ 与
     * 启用开关记录（旧版只删文件，坏缓存/旧启用状态会随重装复活）；文件本来就
     * 不存在时也回 true（旧版回 false），唯一调用方 HkMiniappActivity 不消费返回值。
     */
    public static boolean delete(String title) {
        if (TextUtils.isEmpty(title)) return false;
        return HkRuleManager.get().delete(title);
    }

    /**
     * 枚举全部规则，对应 i22.z()：解析 plugins/hk/ 下每个 .json；同标题重复时
     * 取最后修改时间更新的那个（时间相同取文件名与 N(title) 一致的那个）；
     * 规则里没 title 时用文件名（去 .json 后缀）补，文件名是 unnamed 的不补。
     * 委托给 HkRuleManager.getRules。
     * 2b 新增行为（星辰口径）：解析失败的文件写 files/hk_rule_error.log；
     * 每条规则按 SharedPreferences 写 enabled（默认启用）。
     */
    public static List<HkRule> list() {
        return HkRuleManager.get().getRules();
    }

    /** 写一条规则配置（plugins/hk/config/&lt;标题&gt;.json），对应 i22.Q()；空内容写 {}。委托给 HkRuleManager。 */
    public static void saveConfig(String title, String json) {
        if (TextUtils.isEmpty(title)) return;
        try {
            HkRuleManager.get().saveRuleConfig(title, json);
        } catch (Exception e) {
            Log.w(TAG, "saveConfig failed: " + title, e);
        }
    }

    /**
     * 读一条规则配置，文件不存在返回 null（旧版契约；HkRuleManager.loadRuleConfig
     * 缺失时回 "{}"，这里先判文件存在才委托，保持 null 语义）。
     */
    public static String readConfig(String title) {
        if (TextUtils.isEmpty(title)) return null;
        if (HkRuleManager.get().getRuleConfigFile(title) == null) return null;
        return HkRuleManager.get().loadRuleConfig(title);
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

    /**
     * rc/require 的远程库缓存文件（对齐第 1 项，照官方 require0 的命名）：
     * `plugins/hk/libs/&lt;md5(url)&gt;.js`。与 hkzip 解包的 basename 文件同目录，
     * 32 位 hex 名与真实库文件名基本不会撞；万一撞上，读到的就是那个同名文件
     * （与官方共用 libs/ 目录的做法一致）。
     * url 为空返回 null。
     */
    public static File rcCacheFile(String url) {
        if (TextUtils.isEmpty(url)) return null;
        String hex = md5Hex(url.trim());
        if (TextUtils.isEmpty(hex)) return null;
        return new File(libsDir(), hex + ".js");
    }

    /** rc/require 缓存的版本 meta 文件：`plugins/hk/libs/&lt;md5(url)&gt;.json`。 */
    public static File rcCacheMetaFile(String url) {
        if (TextUtils.isEmpty(url)) return null;
        String hex = md5Hex(url.trim());
        if (TextUtils.isEmpty(hex)) return null;
        return new File(libsDir(), hex + ".json");
    }

    /**
     * 写 rc/require 缓存：库代码写 `<md5>.js`，meta（url + fetchedAt）写
     * `<md5>.json`。写盘失败只记日志、不抛错——缓存是 best-effort，绝不能
     * 阻断本次 require（写失败时本次仍用刚抓到的代码 eval）。
     * 有效期按文件 lastModified 计（写文件即更新 mtime），与官方一致。
     */
    public static void saveRcCache(String url, String code) {
        if (TextUtils.isEmpty(url) || code == null) return;
        try {
            File js = rcCacheFile(url);
            File meta = rcCacheMetaFile(url);
            if (js == null || meta == null) return;
            writeFile(js, code.getBytes(StandardCharsets.UTF_8));
            JsonObject metaJson = new JsonObject();
            metaJson.addProperty("url", url.trim());
            metaJson.addProperty("fetchedAt", System.currentTimeMillis());
            writeFile(meta, metaJson.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "saveRcCache failed: " + url, e);
        }
    }

    /** md5 小写 hex（供缓存文件名用）。 */
    private static String md5Hex(String s) {
        if (s == null) return "";
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 某条规则的私有数据文件：plugins/hk/data/&lt;标题&gt;/&lt;name&gt;。
     * name 可带 hiker://files/ 前缀（剥掉）或子路径（如 libs/&lt;md5&gt;.js）；
     * 含 .. 越界时回落到 basename，根目录恒为该规则的 data 目录，不会写到外面。
     */
    public static File dataFile(String title, String name) {
        File root = dataDir(title);
        if (TextUtils.isEmpty(name)) return new File(root, "unnamed");
        String n = name.trim();
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hiker://files/")) n = n.substring("hiker://files/".length());
        else if (lower.startsWith("hiker://")) n = n.substring("hiker://".length());
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty()) return new File(root, "unnamed");
        File file = new File(root, n);
        try {
            String canonicalRoot = root.getCanonicalPath();
            String canonical = file.getCanonicalPath();
            if (!canonical.equals(canonicalRoot) && !canonical.startsWith(canonicalRoot + File.separator)) {
                file = new File(root, new File(n).getName());
            }
        } catch (Exception ignored) {
        }
        return file;
    }

    /** 读规则私有数据文件，缺失/是目录返回 ""（海阔 readFile 语义：缺失不抛错）。 */
    public static String readDataFile(String title, String name) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(name)) return "";
        File file = dataFile(title, name);
        if (!file.exists() || file.isDirectory()) return "";
        String text = readFile(file);
        return text == null ? "" : text;
    }

    /** 写规则私有数据文件（海阔 writeFile/saveFile 语义），返回是否写成功。 */
    public static boolean writeDataFile(String title, String name, String content) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(name)) return false;
        try {
            writeFile(dataFile(title, name), content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "writeDataFile failed: " + name, e);
            return false;
        }
    }

    /** 删规则私有数据文件（海阔 deleteFile 语义），返回是否删掉。 */
    public static boolean deleteDataFile(String title, String name) {
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(name)) return false;
        File file = dataFile(title, name);
        return file.exists() && file.delete();
    }

    /** 读任意文件成字符串，失败返回 null（供 require 读 lib 文件用）。 */
    public static String readTextFile(File file) {
        if (file == null) return null;
        return readFile(file);
    }

    /** 规则持久变量文件 data/&lt;标题&gt;/myvar.json 的整文件读（getMyVar/putMyVar 的后端）。 */
    public static String readMyVars(String title) {
        return readDataFile(title, "myvar.json");
    }

    /** 规则持久变量文件 data/&lt;标题&gt;/myvar.json 的整文件写。 */
    public static boolean saveMyVars(String title, String json) {
        return writeDataFile(title, "myvar.json", json);
    }

    /**
     * getItem/setItem 的后端：data/&lt;标题&gt;/kv.json（官方 kvFile()，对齐第 5 项）。
     * 旧版写的是 items.json：kv.json 为空且旧文件存在时读旧文件（一次性迁移读，
     * 下次 setItem 即写 kv.json；旧文件原地保留，删不删都不影响读取）。
     */
    public static String readItems(String title) {
        String json = readDataFile(title, "kv.json");
        if (!TextUtils.isEmpty(json)) return json;
        if (dataFile(title, "items.json").exists()) return readDataFile(title, "items.json");
        return json;
    }

    /** getItem/setItem 的后端：data/&lt;标题&gt;/kv.json 的整文件写（官方 kvFile()）。 */
    public static boolean saveItems(String title, String json) {
        return writeDataFile(title, "kv.json", json);
    }

    /**
     * 公共 KV 文件（getPublicItem/setPublicItem/clearPublicItem 的后端，全规则共享）：
     * filesDir/plugins/hk/rules/_item00.json。官方是外部 Documents 下的
     * rules/_item00.json（HkJsRuntime.publicKvFile 经 getFilePath）；这里用内部
     * 存储同名文件，规则看不到路径差异，也无外部存储不可用的边角。
     */
    public static File publicKvFile() {
        File file = new File(new File(dir(), "rules"), "_item00.json");
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        return file;
    }

    /** 公共 KV 的整文件读，缺失回 ""。 */
    public static String readPublicItems() {
        File file = publicKvFile();
        if (!file.exists() || file.isDirectory()) return "";
        String text = readFile(file);
        return text == null ? "" : text;
    }

    /** 公共 KV 的整文件写。 */
    public static boolean savePublicItems(String json) {
        try {
            writeFile(publicKvFile(), json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "savePublicItems failed", e);
            return false;
        }
    }

    /**
     * 读某条规则的 require.json 清单（导入时由 saveConfig 存到 config/&lt;标题&gt;.json，
     * file 已改写到 plugins/hk/libs/）。不是数组/解析失败/不存在返回 null。
     * 注意：2b 起 .hkzip 导入走 HkRuleManager.importHkZip，require.json 落到外部
     * 登记簿根（Documents/rules/files/&lt;标题&gt;/require.json）而不再写 config/，
     * 本方法对新导入的 hkzip 包回 null——require 读取改走外部登记簿根是阶段 7 的活，
     * 在此之前 hkzip 包内 require 走网络取回（HkEngine 既有回退）。
     */
    public static JsonArray readRequireManifest(String title) {
        if (TextUtils.isEmpty(title)) return null;
        String json = readConfig(title);
        if (TextUtils.isEmpty(json)) return null;
        try {
            JsonElement parsed = JsonParser.parseString(json.trim());
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        } catch (Exception e) {
            Log.w(TAG, "require manifest parse failed: " + title, e);
            return null;
        }
    }

    /**
     * 按 URL 在 require.json 清单里找对应的本地库文件：先取清单里 file 字段的
     * 绝对路径（存在即返回），否则按 URL 的 basename 在 plugins/hk/libs/ 里找。
     * 清单里没有这条 URL、或文件不存在，返回 null（调用方再决定是 fetch 还是报明确错）。
     */
    public static File resolveLibFile(String title, String url) {
        if (TextUtils.isEmpty(url)) return null;
        JsonArray manifest = readRequireManifest(title);
        if (manifest == null) return null;
        for (JsonElement element : manifest) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            JsonElement urlEl = obj.get("url");
            if (urlEl == null || !urlEl.isJsonPrimitive()) continue;
            if (!url.equals(urlEl.getAsString())) continue;
            JsonElement fileEl = obj.get("file");
            if (fileEl != null && fileEl.isJsonPrimitive()) {
                File file = new File(fileEl.getAsString());
                if (file.exists() && file.isFile()) return file;
            }
            String base = url.substring(url.lastIndexOf('/') + 1);
            if (!base.isEmpty()) {
                File inLibs = new File(libsDir(), base);
                if (inLibs.exists() && inLibs.isFile()) return inLibs;
            }
            return null;
        }
        return null;
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

    /** 去掉列表里的小程序站源（切站弹窗不显示 hk，见 docs/miniapp-hk-fixes.md）；返回新列表，原列表不动。 */
    public static List<Site> withoutMiniapps(List<Site> sites) {
        List<Site> result = new ArrayList<>();
        if (sites == null) return result;
        for (Site site : sites) {
            if (!isMiniapp(site)) result.add(site);
        }
        return result;
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
     * 批量导入，逐个调 importOne，返回成功登记的条数。
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
     * 导入单个文件：.zip/.hkzip → importZip，其余 → importJsonFile。
     * 失败时按报告里的 mirrorToCache 逻辑：复制到 cache/hk_import 再重试一次；还失败就抛，
     * 调用方记 lastError。成功返回登记的条数（单个文件通常是 1；JSON 数组文件可能多条）。
     */
    public static int importOne(File file) throws Exception {
        try {
            return importOneInternal(file);
        } catch (Exception e) {
            File cached = mirrorToCache(file);
            if (cached == null || cached.equals(file)) throw e;
            return importOneInternal(cached);
        }
    }

    /**
     * importOne 的单次分发（不重试）：.zip/.hkzip 按内容判 hkzip 包还是普通 zip；
     * 其余读文本，js: 文本走 importJsRule，否则走 JSON（单个规则或数组）。
     * 导入成功后登记站源（HkRuleManager 本身不登记站源，登记是门面的职责）。
     */
    private static int importOneInternal(File file) throws Exception {
        if (file == null || !file.exists()) throw new Exception("规则文件不存在");
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".hkzip")) return importZip(file);
        return importJsonFile(file);
    }

    /**
     * 导入单个 JSON 规则文件：读文本 → 校验 → 存盘 → 登记站源。
     * 内容是 JSON 数组时逐条解析：坏的跳过记日志，好的一条条登记，返回成功登记的条数。
     * js: 文本走 HkRuleManager.importJsRule（2b 新增，星辰口径；旧版会报“不是有效的规则 JSON”）。
     * 单个规则校验不过时抛 validate() 的提示（经 HkRuleManager.parseJson 转 IllegalArgumentException）。
     */
    public static int importJsonFile(File file) throws Exception {
        String text = readFile(file);
        if (text == null || text.trim().isEmpty()) throw new Exception("规则文件为空");
        String trimmed = text.trim();
        List<HkRule> imported;
        if (HkRuleManager.isJsRuleText(trimmed)) {
            imported = Collections.singletonList(HkRuleManager.get().importJsRule(trimmed, file.getName()));
        } else {
            imported = HkRuleManager.get().importJsonList(trimmed);
        }
        registerSites(imported);
        return imported.size();
    }

    /**
     * 导入 .zip/.hkzip 规则包：内容含 require.json/libs.zip/data.zip 或根 rule.json
     * 时按 hkzip 包走 HkRuleManager.importHkZip（require.json 落外部登记簿根、
     * libs 落外部 Documents/libs/、data.zip 落外部 Documents/data/&lt;规则名&gt;/，
     * 均为星辰口径；旧版是 require.json 写 config/、libs.zip 解到内部 libs/，
     * 详见 docs/miniapp-xingchen-2b-manager.md），否则按普通 zip 走
     * HkRuleManager.importZip。成功后登记站源，返回登记的条数。
     */
    public static int importZip(File file) throws Exception {
        List<HkRule> imported;
        if (HkRuleManager.isHkZipPackage(file)) {
            imported = HkRuleManager.get().importHkZip(file);
        } else {
            imported = HkRuleManager.get().importZip(file);
        }
        registerSites(imported);
        return imported.size();
    }

    /**
     * 粘贴 JSON 导入：先写 cache/hk_import/hk_paste_&lt;时间&gt;.json 再走 importFiles，
     * 与报告里粘贴导入的链路一致。内容为空记 lastError 返回 0。
     * 注意：6a 起云口令/分享包装文本也走这里（`HkRuleManager.parseJsonList` 的
     * 中央分发先识别云口令与 ￥ 包装再解析；旧 `HkCloudImport` 先取回 JSON 的
     * 路在 6b 删除，见 docs/miniapp-xingchen-6a-cloud-clip.md）。
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
     * 同步已装规则：枚举 plugins/hk/*.json，缺失的站源条目重新登记（已有的不动），
     * 返回新登记的条数。委托给 HkRuleManager.getRules + LocalSource.mergeSites。
     */
    public static int syncSites() {
        List<HkRule> rules = HkRuleManager.get().getRules();
        if (rules.isEmpty()) return 0;
        List<Site> sites = new ArrayList<>();
        for (HkRule rule : rules) sites.add(siteOf(rule));
        return LocalSource.mergeSites(sites);
    }

    /** 把导入成功的规则登记为站源（HkRuleManager.saveRule/import* 不登记站源）。 */
    private static void registerSites(List<HkRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        List<Site> sites = new ArrayList<>();
        for (HkRule rule : rules) {
            if (rule == null) continue;
            sites.add(siteOf(rule));
        }
        LocalSource.mergeSites(sites);
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
