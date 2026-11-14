package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.utils.Path;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
