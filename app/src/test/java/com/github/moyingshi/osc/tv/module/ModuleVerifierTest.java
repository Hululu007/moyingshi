package com.github.moyingshi.osc.tv.module;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModuleVerifierTest {

    @Test
    public void sha256KnownVector() {
        // "abc" 的 SHA-256 标准值。
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ModuleVerifier.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void verifyFileOk() throws Exception {
        File file = Files.createTempFile("module-test-", ".bin").toFile();
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            assertNull(ModuleVerifier.verifyFile(file, 3,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void verifyFileRejectsSizeMismatch() throws Exception {
        File file = Files.createTempFile("module-test-", ".bin").toFile();
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            assertTrue(ModuleVerifier.verifyFile(file, 4,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                    .contains("大小"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void verifyFileRejectsShaMismatch() throws Exception {
        File file = Files.createTempFile("module-test-", ".bin").toFile();
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            assertTrue(ModuleVerifier.verifyFile(file, 3,
                    "0000000000000000000000000000000000000000000000000000000000000000")
                    .contains("sha256"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void verifyFileRejectsMissing() {
        assertTrue(ModuleVerifier.verifyFile(new File("/no/such/file.bin"), 3,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .contains("缺失"));
    }

    @Test
    public void verifyFilesChecksAllManifestEntries() throws Exception {
        File dir = Files.createTempDirectory("module-test-").toFile();
        try {
            Files.write(new File(dir, "a.so").toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            ModuleManifest manifest = new ModuleManifest();
            manifest.module = "mpv";
            ModuleManifest.ModuleFile entry = new ModuleManifest.ModuleFile();
            entry.name = "a.so";
            entry.size = 3;
            entry.sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            manifest.files = Collections.singletonList(entry);
            assertNull(ModuleVerifier.verifyFiles(dir, manifest));
            assertNull(ModuleVerifier.quickCheckFiles(dir, manifest));
        } finally {
            deleteRecursive(dir);
        }
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
}
