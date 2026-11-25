package com.github.moyingshi.osc.tv.module;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** sha256 校验：安装时逐文件校验，运行时用大小+存在性快检（见 ModuleManager）。纯 Java，可 JVM 测试。 */
public final class ModuleVerifier {

    private ModuleVerifier() {
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return toHex(digest.digest());
    }

    public static String sha256(byte[] data) {
        MessageDigest digest = newDigest();
        digest.update(data);
        return toHex(digest.digest());
    }

    /** 校验单个文件：存在、大小、sha256 全对才返回 null，否则为中文原因。 */
    public static String verifyFile(File file, long expectedSize, String expectedSha256) {
        if (file == null || !file.isFile()) return "文件缺失";
        if (expectedSize < 0 || file.length() != expectedSize) {
            return "文件大小不符（应为 " + expectedSize + "，实为 " + file.length() + "）";
        }
        if (!ModuleManifest.isValidSha256(expectedSha256)) return "清单 sha256 无效";
        try {
            String actual = sha256(file);
            if (!expectedSha256.toLowerCase(Locale.US).equals(actual)) return "文件校验失败（sha256 不符）";
        } catch (IOException e) {
            return "文件读取失败：" + e.getMessage();
        }
        return null;
    }

    /** 按 manifest 校验目录内全部文件；null 表示全部通过。 */
    public static String verifyFiles(File dir, ModuleManifest manifest) {
        if (dir == null || !dir.isDirectory()) return "模块目录缺失";
        if (manifest == null || manifest.files == null) return "清单无效";
        for (ModuleManifest.ModuleFile file : manifest.files) {
            String error = verifyFile(new File(dir, file.name), file.size, file.sha256);
            if (error != null) return file.name + "：" + error;
        }
        return null;
    }

    /** 快检（运行时用）：只查存在 + 大小，不算 sha256。 */
    public static String quickCheckFiles(File dir, ModuleManifest manifest) {
        if (dir == null || !dir.isDirectory()) return "模块目录缺失";
        if (manifest == null || manifest.files == null) return "清单无效";
        for (ModuleManifest.ModuleFile file : manifest.files) {
            File actual = new File(dir, file.name);
            if (!actual.isFile()) return file.name + "：文件缺失";
            if (actual.length() != file.size) return file.name + "：文件大小不符";
        }
        return null;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value & 0xff));
        return builder.toString();
    }
}
