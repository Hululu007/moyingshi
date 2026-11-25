package com.github.moyingshi.osc.tv.module;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

/**
 * 模块 zip 内的 manifest.json。
 * 格式（与 {@code docs/app-modularization.md} 分发设计一致）：
 * <pre>
 * {
 *   "module": "mpv",
 *   "moduleVersion": "560",
 *   "appVersionCodeMin": 560,
 *   "appVersionCodeMax": 560,
 *   "abi": "arm64-v8a",
 *   "upstreamVersion": "mpv=... ffmpeg=... libplacebo=... framework=...",
 *   "files": [{"name": "libmpv.so", "size": 123, "sha256": "..."}]
 * }
 * </pre>
 * 纯 Java + Gson，无 Android 依赖，可 JVM 单元测试。
 */
public final class ModuleManifest {

    private static final Gson GSON = new Gson();

    @SerializedName("module")
    public String module;

    @SerializedName("moduleVersion")
    public String moduleVersion;

    @SerializedName("appVersionCodeMin")
    public int appVersionCodeMin;

    @SerializedName("appVersionCodeMax")
    public int appVersionCodeMax;

    @SerializedName("abi")
    public String abi;

    @SerializedName("upstreamVersion")
    public String upstreamVersion;

    @SerializedName("files")
    public List<ModuleFile> files;

    public static final class ModuleFile {
        @SerializedName("name")
        public String name;

        @SerializedName("size")
        public long size;

        @SerializedName("sha256")
        public String sha256;
    }

    public static ModuleManifest parse(String json) throws JsonSyntaxException {
        ModuleManifest manifest = GSON.fromJson(json, ModuleManifest.class);
        if (manifest == null) throw new JsonSyntaxException("empty manifest");
        return manifest;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * 校验 manifest 自身是否合法且适用于当前设备/App。
     * @return null 表示通过，否则为中文原因（直接展示给用户）。
     */
    public String validate(String expectedModuleId, String abi, int appVersionCode) {
        if (module == null || module.isEmpty()) return "清单缺少模块名";
        if (expectedModuleId != null && !expectedModuleId.equals(module)) {
            return "清单模块名不符（" + module + "）";
        }
        if (ModuleDefinition.byId(module) == null) return "未知模块：" + module;
        if (abi == null || abi.isEmpty()) return "清单缺少 ABI";
        if (!abi.equals(this.abi)) return "模块 ABI 不符（" + this.abi + "，本机 " + abi + "）";
        if (appVersionCodeMin <= 0 || appVersionCodeMax < appVersionCodeMin) {
            return "清单版本范围无效";
        }
        if (appVersionCode < appVersionCodeMin || appVersionCode > appVersionCodeMax) {
            return "模块仅适用于 App 版本 " + appVersionCodeMin + "–" + appVersionCodeMax
                    + "，当前为 " + appVersionCode + "，请更新 App 后重试";
        }
        if (files == null || files.isEmpty()) return "清单文件列表为空";
        for (ModuleFile file : files) {
            if (file == null || file.name == null || file.name.isEmpty()) return "清单文件项无效";
            if (file.name.contains("..") || file.name.startsWith("/") || file.name.contains("\\")) {
                return "清单文件名不合法：" + file.name;
            }
            if (file.size < 0) return "清单文件大小无效：" + file.name;
            if (!isValidSha256(file.sha256)) return "清单 sha256 无效：" + file.name;
        }
        return null;
    }

    public static boolean isValidSha256(String sha256) {
        if (sha256 == null || sha256.length() != 64) return false;
        String normalized = sha256.toLowerCase(Locale.US);
        for (int i = 0; i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (value < '0' || value > '9' && value < 'a' || value > 'f') return false;
        }
        return true;
    }
}
