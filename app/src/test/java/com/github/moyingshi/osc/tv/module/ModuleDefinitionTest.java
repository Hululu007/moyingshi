package com.github.moyingshi.osc.tv.module;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModuleDefinitionTest {

    @Test
    public void allSixModulesDefined() {
        assertEquals(6, ModuleDefinition.ALL.size());
        Set<String> ids = new HashSet<>();
        for (ModuleDefinition definition : ModuleDefinition.ALL) {
            assertTrue("duplicate id: " + definition.id, ids.add(definition.id));
            assertNotNull(ModuleDefinition.byId(definition.id));
        }
    }

    @Test
    public void loadOrderIsSubsetOfFiles() {
        for (ModuleDefinition definition : ModuleDefinition.ALL) {
            for (String name : definition.loadOrder) {
                assertTrue(definition.id + " loadOrder not in files: " + name,
                        definition.files.contains(name));
            }
        }
    }

    @Test
    public void nativeModulesHaveLoadOrder_lutDoesNot() {
        assertTrue(ModuleDefinition.MPV.loadOrder.size() == 10);
        assertTrue(ModuleDefinition.VLC.loadOrder.size() == 3);
        assertTrue(ModuleDefinition.SHERPA.loadOrder.size() == 1);
        assertTrue(ModuleDefinition.DOVI.loadOrder.size() == 2);
        assertTrue(ModuleDefinition.IJK.loadOrder.size() == 3);
        assertTrue(ModuleDefinition.LUT.loadOrder.isEmpty());
    }

    @Test
    public void vlcModuleBundlesLibcxxFirst() {
        // Stage 0 实测：libvlc.so 需要 libc++_shared.so，基础包拆走 VLC 后不再自带它，
        // 故 VLC 模块必须自带并先预载。
        assertEquals("libc++_shared.so", ModuleDefinition.VLC.loadOrder.get(0));
        assertEquals("libvlc.so", ModuleDefinition.VLC.loadOrder.get(1));
        assertEquals("libvlcjni.so", ModuleDefinition.VLC.loadOrder.get(2));
    }

    @Test
    public void zipNames() {
        assertEquals("module-mpv-arm64-v8a-560.zip",
                ModuleDefinition.MPV.zipName("arm64-v8a", 560));
        assertEquals("modules-all-arm64-v8a-560.zip",
                ModuleDefinition.totalBundleName("arm64-v8a", 560));
        assertNull(ModuleDefinition.byId("no-such-module"));
    }
}
