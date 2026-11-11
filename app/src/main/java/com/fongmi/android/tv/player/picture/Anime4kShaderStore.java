package com.fongmi.android.tv.player.picture;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Anime4K shader 文件落盘（B6）。上游文件见 app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl
 *（bloc97/Anime4K，MIT，一字不改）；mpv 只能按文件路径挂 glsl-shader，所以运行时拷到缓存目录。
 * 缓存目录 cacheDir/mpv_shaders/，清缓存时一并清理（见 CacheCleanupManager MPV_RUNTIME）。
 */
public class Anime4kShaderStore {

    public static final String ASSET_PATH = "shaders/Anime4K_Upscale_CNN_x2_S.glsl";
    public static final String FILE_NAME = "Anime4K_Upscale_CNN_x2_S.glsl";
    private static final String DIR = "mpv_shaders";

    private Anime4kShaderStore() {
    }

    /** 返回可挂载的 shader 文件绝对路径；拷贝失败返回 null（调用方当作 Anime4K 不可用）。 */
    @Nullable
    public static String ensureShaderPath() {
        File file = ensureShaderFile();
        return file == null ? null : file.getAbsolutePath();
    }

    @Nullable
    public static synchronized File ensureShaderFile() {
        try {
            byte[] asset = readAll(App.get().getAssets().open(ASSET_PATH));
            File dir = new File(App.get().getCacheDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File file = new File(dir, FILE_NAME);
            if (file.isFile() && Arrays.equals(readAll(new FileInputStream(file)), asset)) return file;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(asset);
            }
            if (SpiderDebug.isEnabled()) SpiderDebug.log("pic-mpv", "anime4k shader written %s bytes=%d", file.getAbsolutePath(), asset.length);
            return file;
        } catch (Exception e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("pic-mpv", "anime4k shader copy failed error=%s", e.toString());
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
