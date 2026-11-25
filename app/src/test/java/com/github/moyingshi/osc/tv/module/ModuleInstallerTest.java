package com.github.moyingshi.osc.tv.module;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModuleInstallerTest {

    private File tempRoot;
    private File modulesRoot;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("module-installer-test-").toFile();
        modulesRoot = new File(tempRoot, "modules");
        modulesRoot.mkdirs();
    }

    @After
    public void tearDown() {
        ModuleVerifierTest.deleteRecursive(tempRoot);
    }

    @Test
    public void installValidSingleZip() throws Exception {
        File zip = moduleZip("mpv", "arm64-v8a", 560, 560,
                Map.of("libmpv.so", "fake-so-bytes".getBytes(StandardCharsets.UTF_8)));
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(zip, modulesRoot, "arm64-v8a", 560);
        assertEquals(1, results.size());
        assertTrue(results.get(0).success);
        assertEquals("mpv", results.get(0).moduleId);
        assertTrue(new File(modulesRoot, "mpv/libmpv.so").isFile());
        assertTrue(new File(modulesRoot, "mpv/manifest.json").isFile());
    }

    @Test
    public void installRejectsShaMismatchAndKeepsOldModule() throws Exception {
        // 先装一个好的，再用 sha 错的包覆盖安装：旧模块必须原样保留。
        File good = moduleZip("mpv", "arm64-v8a", 560, 560,
                Map.of("libmpv.so", "good".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ModuleInstaller.installZip(good, modulesRoot, "arm64-v8a", 560)
                .get(0).success);
        File bad = badShaZip("mpv", "arm64-v8a", 560, 560,
                Map.of("libmpv.so", "tampered".getBytes(StandardCharsets.UTF_8)));
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(bad, modulesRoot, "arm64-v8a", 560);
        assertFalse(results.get(0).success);
        assertTrue(results.get(0).error.contains("sha256"));
        assertEquals("good", Files.readString(
                new File(modulesRoot, "mpv/libmpv.so").toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void installRejectsAbiMismatch() throws Exception {
        File zip = moduleZip("mpv", "armeabi-v7a", 560, 560,
                Map.of("libmpv.so", "x".getBytes(StandardCharsets.UTF_8)));
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(zip, modulesRoot, "arm64-v8a", 560);
        assertFalse(results.get(0).success);
        assertTrue(results.get(0).error.contains("ABI"));
        assertFalse(new File(modulesRoot, "mpv").exists());
    }

    @Test
    public void installRejectsExpiredVersionBinding() throws Exception {
        File zip = moduleZip("mpv", "arm64-v8a", 550, 559,
                Map.of("libmpv.so", "x".getBytes(StandardCharsets.UTF_8)));
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(zip, modulesRoot, "arm64-v8a", 560);
        assertFalse(results.get(0).success);
        assertTrue(results.get(0).error.contains("App"));
        assertFalse(new File(modulesRoot, "mpv").exists());
    }

    @Test
    public void installRejectsZipWithoutManifest() throws Exception {
        File zip = new File(tempRoot, "empty.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("random.txt"));
            out.write("hi".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(zip, modulesRoot, "arm64-v8a", 560);
        assertFalse(results.get(0).success);
        assertTrue(results.get(0).error.contains("manifest.json"));
    }

    @Test
    public void installRejectsZipSlipName() throws Exception {
        ModuleManifest manifest = manifest("mpv", "arm64-v8a", 560, 560);
        ModuleManifest.ModuleFile entry = new ModuleManifest.ModuleFile();
        entry.name = "../evil.so";
        entry.size = 1;
        entry.sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        manifest.files = List.of(entry);
        File zip = new File(tempRoot, "slip.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            putEntry(out, "manifest.json", manifest.toJson().getBytes(StandardCharsets.UTF_8));
        }
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(zip, modulesRoot, "arm64-v8a", 560);
        assertFalse(results.get(0).success);
        assertFalse(new File(tempRoot, "evil.so").exists());
    }

    @Test
    public void totalBundleInstallsGoodSkipsBad() throws Exception {
        File goodInner = moduleZip("mpv", "arm64-v8a", 560, 560,
                Map.of("libmpv.so", "good".getBytes(StandardCharsets.UTF_8)));
        File badInner = badShaZip("sherpa", "arm64-v8a", 560, 560,
                Map.of("libsherpa-onnx-jni.so", "tampered".getBytes(StandardCharsets.UTF_8)));
        File total = totalZip(List.of(
                new InnerSpec("mpv", goodInner),
                new InnerSpec("sherpa", badInner)));
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(total, modulesRoot, "arm64-v8a", 560);
        assertEquals(2, results.size());
        ModuleInstaller.InstallResult mpv = find(results, "mpv");
        ModuleInstaller.InstallResult sherpa = find(results, "sherpa");
        assertTrue(mpv.success);
        assertFalse(sherpa.success);
        assertTrue(new File(modulesRoot, "mpv/libmpv.so").isFile());
        assertFalse(new File(modulesRoot, "sherpa").exists());
    }

    @Test
    public void totalBundleRejectsInnerWithBadTotalSha() throws Exception {
        File goodInner = moduleZip("mpv", "arm64-v8a", 560, 560,
                Map.of("libmpv.so", "good".getBytes(StandardCharsets.UTF_8)));
        File otherInner = moduleZip("sherpa", "arm64-v8a", 560, 560,
                Map.of("libsherpa-onnx-jni.so", "good".getBytes(StandardCharsets.UTF_8)));
        // 总清单里故意把 sherpa 分包的 sha256 写错：该分包失败，mpv 仍装上。
        File total = totalZip(List.of(
                new InnerSpec("mpv", goodInner),
                new InnerSpec("sherpa", otherInner, true)));
        List<ModuleInstaller.InstallResult> results =
                ModuleInstaller.installZip(total, modulesRoot, "arm64-v8a", 560);
        assertTrue(find(results, "mpv").success);
        assertFalse(find(results, "sherpa").success);
        assertTrue(find(results, "sherpa").error.contains("sha256"));
        assertTrue(new File(modulesRoot, "mpv/libmpv.so").isFile());
        assertFalse(new File(modulesRoot, "sherpa").exists());
    }

    // ---------- helpers ----------

    private static ModuleInstaller.InstallResult find(
            List<ModuleInstaller.InstallResult> results, String moduleId) {
        for (ModuleInstaller.InstallResult result : results) {
            if (moduleId.equals(result.moduleId)) return result;
        }
        throw new AssertionError("no result for " + moduleId);
    }

    private static ModuleManifest manifest(String moduleId, String abi, int min, int max) {
        ModuleManifest manifest = new ModuleManifest();
        manifest.module = moduleId;
        manifest.moduleVersion = String.valueOf(max);
        manifest.appVersionCodeMin = min;
        manifest.appVersionCodeMax = max;
        manifest.abi = abi;
        manifest.upstreamVersion = "test";
        manifest.files = new ArrayList<>();
        return manifest;
    }

    private File moduleZip(String moduleId, String abi, int min, int max,
                           Map<String, byte[]> files) throws Exception {
        return writeModuleZip(new File(tempRoot, "module-" + moduleId + ".zip"),
                manifestWithFiles(moduleId, abi, min, max, files), files);
    }

    /** 清单 sha256 故意写错的包（文件内容与清单不符）。 */
    private File badShaZip(String moduleId, String abi, int min, int max,
                           Map<String, byte[]> files) throws Exception {
        ModuleManifest manifest = manifestWithFiles(moduleId, abi, min, max, files);
        for (ModuleManifest.ModuleFile entry : manifest.files) {
            entry.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        }
        return writeModuleZip(new File(tempRoot, "module-" + moduleId + "-bad.zip"),
                manifest, files);
    }

    private static ModuleManifest manifestWithFiles(String moduleId, String abi, int min, int max,
                                                    Map<String, byte[]> files) {
        ModuleManifest manifest = manifest(moduleId, abi, min, max);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            ModuleManifest.ModuleFile entry = new ModuleManifest.ModuleFile();
            entry.name = file.getKey();
            entry.size = file.getValue().length;
            entry.sha256 = ModuleVerifier.sha256(file.getValue());
            manifest.files.add(entry);
        }
        return manifest;
    }

    private static File writeModuleZip(File zip, ModuleManifest manifest,
                                       Map<String, byte[]> files) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            putEntry(out, "manifest.json", manifest.toJson().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                putEntry(out, file.getKey(), file.getValue());
            }
        }
        return zip;
    }

    private static final class InnerSpec {
        final String module;
        final File zip;
        final boolean corruptTotalSha;

        InnerSpec(String module, File zip) {
            this(module, zip, false);
        }

        InnerSpec(String module, File zip, boolean corruptTotalSha) {
            this.module = module;
            this.zip = zip;
            this.corruptTotalSha = corruptTotalSha;
        }
    }

    private File totalZip(List<InnerSpec> inners) throws Exception {
        File total = new File(tempRoot, "modules-all.zip");
        ModuleInstaller.TotalManifest manifest = new ModuleInstaller.TotalManifest();
        manifest.modules = new ArrayList<>();
        Map<String, byte[]> innerBytes = new LinkedHashMap<>();
        for (InnerSpec inner : inners) {
            byte[] bytes = Files.readAllBytes(inner.zip.toPath());
            innerBytes.put(inner.zip.getName(), bytes);
            ModuleInstaller.TotalManifest.TotalEntry entry =
                    new ModuleInstaller.TotalManifest.TotalEntry();
            entry.file = inner.zip.getName();
            entry.module = inner.module;
            entry.size = bytes.length;
            entry.sha256 = inner.corruptTotalSha
                    ? "0000000000000000000000000000000000000000000000000000000000000000"
                    : ModuleVerifier.sha256(bytes);
            manifest.modules.add(entry);
        }
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(total))) {
            putEntry(out, "total-manifest.json",
                    new com.google.gson.Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> file : innerBytes.entrySet()) {
                putEntry(out, file.getKey(), file.getValue());
            }
        }
        return total;
    }

    private static void putEntry(ZipOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
