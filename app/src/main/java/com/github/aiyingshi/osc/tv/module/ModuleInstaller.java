package com.github.aiyingshi.osc.tv.module;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模块安装：在线下载与本地导入共用的唯一安装路径。
 *
 * <p>输入一个 zip（分包或总包），输出每个模块的安装结果：
 * <ul>
 *   <li>分包：zip 根目录含 manifest.json → 校验（ABI、App 版本范围、逐文件大小+sha256）→
 *       通过后整体搬进私有目录 {@code modules/<id>/}，失败则删干净、不留半截文件。</li>
 *   <li>总包：zip 根目录含 total-manifest.json → 先对每个内层分包验总清单的 sha256，
 *       再逐个走分包安装；某个分包失败只影响它自己，不影响其他分包。</li>
 * </ul>
 * 纯 Java（java.util.zip + Gson），无 Android 依赖，可 JVM 单元测试。
 * 调用方保证在工作线程执行；安装过程不做网络访问。
 */
public final class ModuleInstaller {

    private static final Gson GSON = new Gson();
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String TOTAL_MANIFEST_NAME = "total-manifest.json";

    /** 单个模块的安装结果。 */
    public static final class InstallResult {
        /** 模块 id；zip 本身读不了时为 null。 */
        public final String moduleId;
        public final boolean success;
        /** 成功时为 null；失败时为中文原因。 */
        public final String error;

        InstallResult(String moduleId, boolean success, String error) {
            this.moduleId = moduleId;
            this.success = success;
            this.error = error;
        }

        public static InstallResult ok(String moduleId) {
            return new InstallResult(moduleId, true, null);
        }

        public static InstallResult fail(String moduleId, String error) {
            return new InstallResult(moduleId, false, error);
        }
    }

    /** 总包总清单：只列内层分包的文件名、大小、sha256（总包本身不重复放 .so）。 */
    static final class TotalManifest {
        @SerializedName("modules")
        List<TotalEntry> modules;

        static final class TotalEntry {
            @SerializedName("file")
            String file;
            @SerializedName("module")
            String module;
            @SerializedName("size")
            long size;
            @SerializedName("sha256")
            String sha256;
        }
    }

    private ModuleInstaller() {
    }

    /**
     * 安装一个 zip（分包或总包）。返回每个模块的结果；总包内某分包失败不影响其他分包。
     *
     * @param zipFile         待安装的 zip（调用方负责事后删除，如下载的临时文件）。
     * @param modulesRoot     私有目录根，如 {@code getDir("modules") }；安装目标为其下的 {@code <id>/}。
     * @param abi             设备 ABI，如 arm64-v8a。
     * @param appVersionCode  当前 App versionCode，用于版本绑定校验。
     */
    public static List<InstallResult> installZip(File zipFile, File modulesRoot,
                                                 String abi, int appVersionCode) {
        List<InstallResult> results = new ArrayList<>();
        if (zipFile == null || !zipFile.isFile()) {
            results.add(InstallResult.fail(null, "安装包不存在"));
            return results;
        }
        if (modulesRoot == null) {
            results.add(InstallResult.fail(null, "模块目录不可用"));
            return results;
        }
        try (ZipFile zip = new ZipFile(zipFile)) {
            if (zip.getEntry(TOTAL_MANIFEST_NAME) != null) {
                installTotalBundle(zip, zipFile, modulesRoot, abi, appVersionCode, results);
            } else if (zip.getEntry(MANIFEST_NAME) != null) {
                results.add(installSingleZip(zip, modulesRoot, abi, appVersionCode));
            } else {
                results.add(InstallResult.fail(null, "安装包里没有 manifest.json（不是模块压缩包）"));
            }
        } catch (IOException e) {
            results.add(InstallResult.fail(null, "安装包读取失败：" + message(e)));
        }
        return results;
    }

    private static void installTotalBundle(ZipFile zip, File zipFile, File modulesRoot,
                                           String abi, int appVersionCode,
                                           List<InstallResult> results) throws IOException {
        TotalManifest total = readJson(zip, TOTAL_MANIFEST_NAME, TotalManifest.class);
        if (total == null || total.modules == null || total.modules.isEmpty()) {
            results.add(InstallResult.fail(null, "总包清单为空或无效"));
            return;
        }
        File staging = stagingDir(modulesRoot);
        try {
            for (TotalManifest.TotalEntry entry : total.modules) {
                String moduleId = entry.module;
                if (entry.file == null || entry.file.isEmpty()
                        || entry.file.contains("..") || entry.file.contains("/")) {
                    results.add(InstallResult.fail(moduleId, "总包清单文件名不合法"));
                    continue;
                }
                ZipEntry inner = zip.getEntry(entry.file);
                if (inner == null) {
                    results.add(InstallResult.fail(moduleId, "总包里缺少分包：" + entry.file));
                    continue;
                }
                File innerZip = new File(staging, entry.file);
                try (InputStream in = zip.getInputStream(inner);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(innerZip))) {
                    byte[] buffer = new byte[64 * 1024];
                    int length;
                    while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                }
                // 先按总清单验内层分包的 sha256（总包 hash 随构建变化，以清单内各分包 sha256 为准）。
                String error = ModuleVerifier.verifyFile(innerZip, entry.size, entry.sha256);
                if (error != null) {
                    results.add(InstallResult.fail(moduleId, "分包校验失败：" + error));
                    continue;
                }
                results.addAll(installZip(innerZip, modulesRoot, abi, appVersionCode));
            }
        } finally {
            deleteRecursive(staging);
        }
    }

    /** 分包安装：解到暂存目录、逐文件校验、通过后整体搬进 modules/<id>/。 */
    private static InstallResult installSingleZip(ZipFile zip, File modulesRoot,
                                                  String abi, int appVersionCode) {
        ModuleManifest manifest;
        try {
            manifest = readJson(zip, MANIFEST_NAME, ModuleManifest.class);
        } catch (IOException e) {
            return InstallResult.fail(null, "清单读取失败：" + message(e));
        }
        if (manifest == null) return InstallResult.fail(null, "清单为空或无效");
        String moduleId = manifest.module;
        String error = manifest.validate(null, abi, appVersionCode);
        if (error != null) return InstallResult.fail(moduleId, error);
        ModuleDefinition definition = ModuleDefinition.byId(moduleId);
        if (definition == null) return InstallResult.fail(moduleId, "未知模块：" + moduleId);

        File staging = stagingDir(modulesRoot);
        try {
            // manifest 自身也落盘到安装目录，供状态查询与版本绑定读取。
            extractEntry(zip, MANIFEST_NAME, new File(staging, MANIFEST_NAME));
            for (ModuleManifest.ModuleFile file : manifest.files) {
                ZipEntry entry = zip.getEntry(file.name);
                if (entry == null) return InstallResult.fail(moduleId, "安装包里缺少文件：" + file.name);
                File target = new File(staging, file.name);
                assertInside(staging, target);
                extractEntry(zip, file.name, target);
                String verifyError = ModuleVerifier.verifyFile(target, file.size, file.sha256);
                if (verifyError != null) {
                    return InstallResult.fail(moduleId, file.name + "：" + verifyError);
                }
            }
            // 多余文件不拒绝（向前兼容），但只安装清单列出的文件：其余条目不解压。
            File target = new File(modulesRoot, moduleId);
            deleteRecursive(target);
            if (!staging.renameTo(target)) {
                // 跨文件系统时 rename 失败则复制兜底。
                copyDir(staging, target);
            }
            return InstallResult.ok(moduleId);
        } catch (IOException e) {
            return InstallResult.fail(moduleId, "安装失败：" + message(e));
        } finally {
            deleteRecursive(staging);
        }
    }

    private static <T> T readJson(ZipFile zip, String name, Class<T> type) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readAllBytes();
            return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (RuntimeException e) {
            throw new IOException("JSON 解析失败：" + message(e));
        }
    }

    private static void extractEntry(ZipFile zip, String name, File target) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("缺少条目：" + name);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
        }
    }

    /** 防 zip-slip：解压目标必须在暂存目录内。 */
    private static void assertInside(File dir, File target) throws IOException {
        String root = dir.getCanonicalPath();
        String path = target.getCanonicalPath();
        if (!path.startsWith(root + File.separator)) {
            throw new IOException("非法文件路径：" + target.getName());
        }
    }

    private static File stagingDir(File modulesRoot) {
        File staging = new File(new File(modulesRoot, ".staging"),
                "install-" + System.nanoTime());
        staging.mkdirs();
        return staging;
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static void copyDir(File source, File target) throws IOException {
        if (source.isDirectory()) {
            target.mkdirs();
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) copyDir(child, new File(target, child.getName()));
            }
            return;
        }
        Files.copy(source.toPath(), target.toPath());
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
