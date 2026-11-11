package com.github.moyingshi.osc.tv.download;

import android.os.Environment;

import java.io.File;

/**
 * 下载文件路径：手机内置存储 Download 文件夹，按剧名建子文件夹。
 *
 * 布局：
 * - 直链：Download/&lt;剧名&gt;/&lt;剧名&gt; &lt;集名&gt;.&lt;ext&gt;
 * - m3u8：Download/&lt;剧名&gt;/&lt;剧名&gt; &lt;集名&gt;/index.m3u8 + 同目录各段
 *
 * targetSdk 28 走传统存储模式，Environment.getExternalStoragePublicDirectory
 * 直接读写公共 Download 目录合规；不写死 /sdcard 路径。
 */
public final class DownloadPathUtil {

    private DownloadPathUtil() {
    }

    /** 内置存储公共 Download 文件夹。 */
    @SuppressWarnings("deprecation")
    public static File getDownloadRoot() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /**
     * 文件名清洗：把不能当文件名的字符（/ \ : * ? " &lt; &gt; | 及控制字符）换成下划线，
     * 去首尾空白与点。空结果返回“未知剧名”。
     */
    public static String sanitize(String name) {
        if (name == null) return "未知剧名";
        String clean = name.replaceAll("[/\\\\:*?\"<>|\\x00-\\x1F]", "_").trim();
        while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1).trim();
        if (clean.isEmpty()) return "未知剧名";
        return clean;
    }

    /** 任务 id：清洗后剧名 + 集名，同一部剧同一集唯一。 */
    public static String taskId(String dramaName, String episodeName) {
        return sanitize(dramaName) + "|" + sanitize(episodeName);
    }

    /** Download/&lt;剧名&gt;/（不存在时不自动创建，调用方按需 mkdirs）。 */
    public static File getDramaDir(String dramaName) {
        return new File(getDownloadRoot(), sanitize(dramaName));
    }

    /** 直链目标文件：Download/&lt;剧名&gt;/&lt;剧名&gt; &lt;集名&gt;.&lt;ext&gt; */
    public static File getDirectFile(String dramaName, String episodeName, String ext) {
        return new File(getDramaDir(dramaName), sanitize(dramaName) + " " + sanitize(episodeName) + "." + ext);
    }

    /** m3u8 剧集目录：Download/&lt;剧名&gt;/&lt;剧名&gt; &lt;集名&gt;/ */
    public static File getHlsDir(String dramaName, String episodeName) {
        return new File(getDramaDir(dramaName), sanitize(dramaName) + " " + sanitize(episodeName));
    }

    /** m3u8 本地播放列表：Download/&lt;剧名&gt;/&lt;剧名&gt; &lt;集名&gt;/index.m3u8 */
    public static File getHlsIndex(String dramaName, String episodeName) {
        return new File(getHlsDir(dramaName, episodeName), "index.m3u8");
    }

    /** 递归删除文件或文件夹；不存在返回 true。 */
    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) ok = deleteRecursively(child) && ok;
            }
        }
        return file.delete() && ok;
    }
}
