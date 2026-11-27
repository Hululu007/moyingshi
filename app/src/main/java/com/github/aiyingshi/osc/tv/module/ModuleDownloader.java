package com.github.aiyingshi.osc.tv.module;

import com.github.catvod.net.OkHttp;
import com.github.aiyingshi.osc.tv.utils.GithubProxy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Response;

/**
 * 模块 zip 下载：OkHttp + 断点续传（Range）+ 进度回调。
 *
 * <p>加速设置（2026-11-25 19:32 用户补充）：在线下载必须走现有的 GitHub 代理
 * {@link GithubProxy#apply(String)}，用用户配好的代理源/模式下载；代理失败时照
 * NodeLib 的做法回退直连（{@code NodeLib.fetch}：代理 URL 失败且与源 URL 不同时重试源 URL），
 * 直连也失败才报下载失败。本地导入是本地文件，不走代理、不受影响；代理配成直连（DIRECT）
 * 时 {@code GithubProxy.apply} 原样返回源 URL，下载即直连。
 * 调用方保证在工作线程执行；不做 UI。
 */
public final class ModuleDownloader {

    /** 与 NodeLib 相同的传输超时（5 分钟）：大模块弱网下不断在中途。 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 进度回调节流：每 1MB 推一次，结束时再推一次（照 NodeLib.download）。 */
    private static final long PROGRESS_STEP_BYTES = 1024 * 1024L;

    private ModuleDownloader() {
    }

    /**
     * 下载一个模块 zip（或总包）到 target。target 已存在半截文件时断点续传。
     * @return null 表示成功，否则为中文原因（可直接展示，失败后可重试）。
     */
    public static String download(String originUrl, File target,
                                  ModuleProgress.Listener progress,
                                  ModuleProgress.CancellationToken cancel) {
        if (originUrl == null || originUrl.isEmpty()) return "下载地址为空";
        if (target == null) return "下载目标不可用";
        return downloadWithFallback(GithubProxy.apply(originUrl), originUrl, target, progress, cancel);
    }

    /** 代理优先、失败回退直连（包内可见，供 MockWebServer 单元测试）。 */
    static String downloadWithFallback(String firstUrl, String fallbackUrl, File target,
                                       ModuleProgress.Listener progress,
                                       ModuleProgress.CancellationToken cancel) {
        String error = downloadWithResume(firstUrl, target, progress, cancel);
        if (error == null) return null;
        if (cancel != null && cancel.isCancelled()) return error;
        if (fallbackUrl != null && !fallbackUrl.equals(firstUrl)) {
            // 代理本身失效（挂了、限流）时退回直连，好过整个功能不可用（照 NodeLib.fetch）。
            return downloadWithResume(fallbackUrl, target, progress, cancel);
        }
        return error;
    }

    /** 单次下载：支持 Range 断点续传（包内可见，供 MockWebServer 单元测试）。 */
    static String downloadWithResume(String url, File target,
                                     ModuleProgress.Listener progress,
                                     ModuleProgress.CancellationToken cancel) {
        return downloadWithResume(url, target, progress, cancel, false);
    }

    private static String downloadWithResume(String url, File target,
                                             ModuleProgress.Listener progress,
                                             ModuleProgress.CancellationToken cancel,
                                             boolean retried416) {
        long done = target.exists() ? target.length() : 0L;
        Map<String, String> headers = new HashMap<>();
        if (done > 0) headers.put("Range", "bytes=" + done + "-");
        try (Response response = OkHttp.newCall(OkHttp.client(TIMEOUT_MS), url, headers).execute()) {
            if (response.code() == 416 && !retried416) {
                // 服务端认为已传范围无效（如文件已换新），删半截文件从头下（只重试一次）。
                if (!target.delete()) return "断点续传失败，无法清理旧文件";
                return downloadWithResume(url, target, progress, cancel, true);
            }
            if (!response.isSuccessful() || response.body() == null) {
                return "下载失败 HTTP " + response.code();
            }
            boolean resume = response.code() == 206;
            // 服务端忽略 Range 返回 200 时从头写：已传字节数清零，否则进度会虚高。
            if (!resume) done = 0L;
            long total = response.body().contentLength();
            if (total > 0 && resume) total += done;
            try (InputStream in = response.body().byteStream();
                 OutputStream out = new FileOutputStream(target, resume)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int length;
                long lastPost = done;
                while ((length = in.read(buffer)) != -1) {
                    if (cancel != null && cancel.isCancelled()) return "已取消下载";
                    out.write(buffer, 0, length);
                    done += length;
                    if (progress != null && done - lastPost >= PROGRESS_STEP_BYTES) {
                        lastPost = done;
                        progress.onProgress(done, total);
                    }
                }
            }
            if (progress != null) progress.onProgress(done, total);
            return null;
        } catch (IOException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }
}
