package com.github.aiyingshi.osc.tv.module;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModuleManifestTest {

    private static final String JSON = "{"
            + "\"module\":\"mpv\","
            + "\"moduleVersion\":\"560\","
            + "\"appVersionCodeMin\":560,"
            + "\"appVersionCodeMax\":560,"
            + "\"abi\":\"arm64-v8a\","
            + "\"upstreamVersion\":\"mpv=test\","
            + "\"files\":[{\"name\":\"libmpv.so\",\"size\":10,"
            + "\"sha256\":\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"}]"
            + "}";

    @Test
    public void parseAndValidateOk() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        assertEquals("mpv", manifest.module);
        assertEquals(1, manifest.files.size());
        assertNull(manifest.validate("mpv", "arm64-v8a", 560));
        // toJson/parse 往返
        assertEquals("mpv", ModuleManifest.parse(manifest.toJson()).module);
    }

    @Test
    public void rejectAbiMismatch() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        String error = manifest.validate("mpv", "armeabi-v7a", 560);
        assertTrue(error.contains("ABI"));
    }

    @Test
    public void rejectVersionBelowMin() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        assertTrue(manifest.validate("mpv", "arm64-v8a", 559).contains("App"));
    }

    @Test
    public void rejectVersionAboveMax() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        assertTrue(manifest.validate("mpv", "arm64-v8a", 561).contains("App"));
    }

    @Test
    public void rejectUnknownModule() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        manifest.module = "no-such";
        assertTrue(manifest.validate("no-such", "arm64-v8a", 560).contains("未知模块"));
    }

    @Test
    public void rejectBadSha256() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        manifest.files.get(0).sha256 = "xyz";
        assertTrue(manifest.validate("mpv", "arm64-v8a", 560).contains("sha256"));
    }

    @Test
    public void rejectEmptyFiles() {
        ModuleManifest manifest = ModuleManifest.parse(JSON);
        manifest.files.clear();
        assertTrue(manifest.validate("mpv", "arm64-v8a", 560).contains("文件列表"));
    }
}
