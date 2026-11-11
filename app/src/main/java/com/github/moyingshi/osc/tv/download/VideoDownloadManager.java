package com.github.moyingshi.osc.tv.download;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.setting.Setting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 视频下载管理器（单例，单线程队列逐集下载）。
 *
 * 流程：入队 → 后台调 SiteApi.playerContentIsolated 解析出播放地址
 * （与播放同一条解析链）→ 直链文件 Range 断点续传；m3u8 下载分段后
 * 写本地 index.m3u8；DASH（.mpd）等类型暂不支持。
 *
 * 文件落到内置存储 Download/&lt;剧名&gt;/（见 DownloadPathUtil）。
 * 任务记录持久化到内部存储 download_tasks.json。
 */
public final class VideoDownloadManager {

    private static final String TAG = "VideoDownload";
    private static final long TIMEOUT_MS = 30_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BUFFER_SIZE = 8192;
    private static final long NOTIFY_INTERVAL_MS = 500;

    /** 任务被外部删除时用来中断正在进行的下载。 */
    private static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    public interface Listener {
        void onTasksChanged();
    }

    private static volatile VideoDownloadManager sInstance;

    public static VideoDownloadManager get() {
        if (sInstance == null) {
            synchronized (VideoDownloadManager.class) {
                if (sInstance == null) sInstance = new VideoDownloadManager();
            }
        }
        return sInstance;
    }

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final Set<Listener> listeners = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> cancelledIds = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicLong lastNotifyMs = new AtomicLong(0);
    private boolean workerActive;

    private VideoDownloadManager() {
        tasks.addAll(DownloadStore.load());
        boolean changed = false;
        for (DownloadTask task : tasks) {
            if (task.isActive()) {
                task.status = DownloadTask.Status.QUEUED;
                task.error = null;
                changed = true;
            }
        }
        if (changed) persist();
    }

    // ------------------------------------------------------------------ 对外接口

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 任务快照（按入队时间排序）。 */
    public synchronized List<DownloadTask> getTasks() {
        return new ArrayList<>(tasks);
    }

    /**
     * 入队一集。已完成且文件还在 → 不重复下；已在队列/下载中 → 不重复入队；
     * 之前失败过 → 重置后重新排队。返回 true 表示已入队或已重新排队。
     */
    public synchronized boolean enqueue(String dramaName, String siteKey, String flag, String episodeName, String episodeUrl) {
        String id = DownloadPathUtil.taskId(dramaName, episodeName);
        DownloadTask existing = findById(id);
        if (existing != null) {
            if (existing.isDone() && existing.filePath != null && new File(existing.filePath).exists()) return false;
            if (existing.isActive()) return false;
            existing.status = DownloadTask.Status.QUEUED;
            existing.error = null;
            existing.downloadedBytes = 0;
            existing.totalBytes = -1;
            existing.segmentsDone = 0;
            existing.segmentsTotal = 0;
            existing.resolvedUrl = null;
            persist();
            notifyChanged(true);
            kickWorker();
            return true;
        }
        DownloadTask task = new DownloadTask();
        task.id = id;
        task.dramaName = dramaName;
        task.episodeName = episodeName;
        task.siteKey = siteKey;
        task.flag = flag;
        task.episodeUrl = episodeUrl;
        task.status = DownloadTask.Status.QUEUED;
        task.createdAt = System.currentTimeMillis();
        tasks.add(task);
        persist();
        notifyChanged(true);
        kickWorker();
        return true;
    }

    /**
     * 删除一部剧的全部下载：删任务记录 + 递归删掉 Download/&lt;剧名&gt;/ 文件夹本身。
     * 正在下载的同一集会被中断。结果回调在主线程执行。
     */
    public void deleteDrama(String dramaName, Consumer<Boolean> callback) {
        File dramaDir = DownloadPathUtil.getDramaDir(dramaName);
        synchronized (this) {
            List<DownloadTask> doomed = new ArrayList<>();
            for (DownloadTask task : tasks) {
                if (TextUtils.equals(task.dramaName, dramaName)) doomed.add(task);
            }
            for (DownloadTask task : doomed) {
                cancelledIds.add(task.id);
                tasks.remove(task);
            }
            persist();
        }
        notifyChanged(true);
        executor.execute(() -> {
            boolean ok = DownloadPathUtil.deleteRecursively(dramaDir);
            App.post(() -> {
                if (callback != null) callback.accept(ok);
                notifyChanged(true);
            });
        });
    }

    // ------------------------------------------------------------------ 队列

    private synchronized DownloadTask findById(String id) {
        for (DownloadTask task : tasks) {
            if (TextUtils.equals(task.id, id)) return task;
        }
        return null;
    }

    private synchronized void persist() {
        DownloadStore.save(new ArrayList<>(tasks));
    }

    private void notifyChanged(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifyMs.get() < NOTIFY_INTERVAL_MS) return;
        lastNotifyMs.set(now);
        App.post(() -> {
            for (Listener listener : listeners) listener.onTasksChanged();
        });
    }

    private synchronized void kickWorker() {
        if (workerActive) return;
        DownloadTask next = null;
        for (DownloadTask task : tasks) {
            if (task.status == DownloadTask.Status.QUEUED) {
                next = task;
                break;
            }
        }
        if (next == null) return;
        workerActive = true;
        DownloadTask task = next;
        executor.execute(() -> {
            try {
                processTask(task);
            } finally {
                synchronized (VideoDownloadManager.this) {
                    workerActive = false;
                }
                kickWorker();
            }
        });
    }

    private boolean isCancelled(DownloadTask task) {
        return cancelledIds.contains(task.id);
    }

    private void setStatus(DownloadTask task, DownloadTask.Status status) {
        task.status = status;
        persist();
        notifyChanged(true);
    }

    private void fail(DownloadTask task, String error) {
        task.status = DownloadTask.Status.FAILED;
        task.error = error;
        persist();
        notifyChanged(true);
    }

    // ------------------------------------------------------------------ 单集处理

    private void processTask(DownloadTask task) {
        if (isCancelled(task)) return;
        try {
            setStatus(task, DownloadTask.Status.RESOLVING);
            Result result = SiteApi.playerContentIsolated(task.siteKey, task.flag, task.episodeUrl);
            String url = result == null ? "" : result.getUrl().v();
            if (TextUtils.isEmpty(url)) {
                fail(task, "解析不到播放地址");
                return;
            }
            if (isCancelled(task)) return;
            task.resolvedUrl = url;
            Map<String, String> headers = new HashMap<>(result.getHeader());
            addUserAgentIfMissing(headers);
            if (isHlsUrl(url)) {
                downloadHls(task, url, headers);
            } else if (isDashUrl(url)) {
                fail(task, "暂不支持 DASH 视频下载");
            } else {
                downloadDirect(task, url, headers);
            }
        } catch (CancelledException e) {
            // 任务已被删除，不再动记录。
        } catch (Exception e) {
            fail(task, messageOf(e));
        }
    }

    private void addUserAgentIfMissing(Map<String, String> headers) {
        for (String key : headers.keySet()) {
            if ("User-Agent".equalsIgnoreCase(key)) return;
        }
        String ua = Setting.getUa();
        if (!TextUtils.isEmpty(ua)) headers.put("User-Agent", ua);
    }

    private static String messageOf(Exception e) {
        String msg = e.getMessage();
        if (TextUtils.isEmpty(msg)) return e.getClass().getSimpleName();
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    // ------------------------------------------------------------------ 类型判断

    private static String stripQuery(String url) {
        int cut = url.length();
        int q = url.indexOf('?');
        if (q >= 0) cut = Math.min(cut, q);
        int h = url.indexOf('#');
        if (h >= 0) cut = Math.min(cut, h);
        return url.substring(0, cut);
    }

    private static String extensionOf(String url) {
        String path = stripQuery(url);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == path.length() - 1) return "";
        String ext = path.substring(dot + 1).toLowerCase();
        if (ext.length() > 5) return "";
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) return "";
        }
        return ext;
    }

    private static boolean isHlsUrl(String url) {
        return stripQuery(url).toLowerCase().endsWith(".m3u8");
    }

    private static boolean isDashUrl(String url) {
        return stripQuery(url).toLowerCase().endsWith(".mpd");
    }

    private static String extensionForContentType(String contentType) {
        if (contentType == null) return "";
        String type = contentType.toLowerCase();
        if (type.contains("mpegurl")) return "m3u8";
        if (type.contains("mp4")) return "mp4";
        if (type.contains("matroska")) return "mkv";
        if (type.contains("webm")) return "webm";
        if (type.contains("quicktime")) return "mov";
        if (type.contains("msvideo")) return "avi";
        if (type.contains("x-flv")) return "flv";
        if (type.contains("mp2t")) return "ts";
        if (type.contains("octet-stream")) return "mp4";
        return "";
    }

    // ------------------------------------------------------------------ 直链下载

    private void downloadDirect(DownloadTask task, String url, Map<String, String> headers) throws Exception {
        String ext = extensionOf(url);
        File target;
        if (!TextUtils.isEmpty(ext)) {
            target = DownloadPathUtil.getDirectFile(task.dramaName, task.episodeName, ext);
        } else if (!TextUtils.isEmpty(task.filePath)) {
            target = new File(task.filePath);
        } else {
            target = null; // 扩展名未知：先发请求，按 Content-Type 决定
        }
        if (target != null && target.exists() && target.length() > 0) {
            task.filePath = target.getAbsolutePath();
            task.downloadedBytes = target.length();
            task.totalBytes = target.length();
            setStatus(task, DownloadTask.Status.DONE);
            return;
        }
        setStatus(task, DownloadTask.Status.DOWNLOADING);
        OkHttpClient client = OkHttp.client(TIMEOUT_MS);
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (isCancelled(task)) throw new CancelledException();
            try {
                downloadDirectOnce(task, client, url, headers, target);
                return;
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (task.status != DownloadTask.Status.DONE) {
            throw lastError == null ? new IOException("下载失败") : lastError;
        }
    }

    private void downloadDirectOnce(DownloadTask task, OkHttpClient client, String url,
                                    Map<String, String> headers, File knownTarget) throws Exception {
        File part = knownTarget == null ? null : new File(knownTarget.getAbsolutePath() + ".part");
        long existing = part != null && part.exists() ? part.length() : 0;
        Map<String, String> requestHeaders = new HashMap<>(headers);
        if (existing > 0) requestHeaders.put("Range", "bytes=" + existing + "-");
        Call call = OkHttp.newCall(client, url, requestHeaders);
        try (Response response = call.execute()) {
            if (response.code() == 416) {
                // 已下完：直接改名收尾。
                finishDirectPart(task, part, knownTarget);
                return;
            }
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("空响应");
            String contentType = response.header("Content-Type", "");
            if (contentType != null && contentType.toLowerCase().contains("mpegurl")) {
                // 服务端实际返回的是 m3u8，走 HLS 流程。
                String playlist = body.string();
                downloadHlsFromPlaylist(task, url, playlist, headers);
                return;
            }
            File target = knownTarget;
            if (target == null) {
                String ext = extensionForContentType(contentType);
                if (TextUtils.isEmpty(ext)) throw new IOException("暂不支持下载这种视频");
                target = DownloadPathUtil.getDirectFile(task.dramaName, task.episodeName, ext);
                part = new File(target.getAbsolutePath() + ".part");
                existing = part.exists() ? part.length() : 0;
            }
            if (response.code() == 200 && existing > 0) {
                // 服务端不支持 Range：删掉已下部分从头来（本次连接的 body 仍可用）。
                part.delete();
                existing = 0;
            }
            long total = body.contentLength();
            task.totalBytes = total < 0 ? -1 : total + existing;
            task.filePath = target.getAbsolutePath();
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(part, existing > 0)) {
                copyWithProgress(task, in, out, existing);
            }
            finishDirectPart(task, part, target);
        }
    }

    private void finishDirectPart(DownloadTask task, File part, File target) {
        if (part == null || target == null) return;
        if (part.exists()) {
            target.delete();
            part.renameTo(target);
        }
        if (target.exists()) {
            task.filePath = target.getAbsolutePath();
            task.downloadedBytes = target.length();
            task.totalBytes = target.length();
            setStatus(task, DownloadTask.Status.DONE);
        }
    }

    private void copyWithProgress(DownloadTask task, InputStream in, OutputStream out, long startBytes) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long done = startBytes;
        task.downloadedBytes = done;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (isCancelled(task)) throw new CancelledException();
            out.write(buffer, 0, read);
            done += read;
            task.downloadedBytes = done;
            notifyChanged(false);
        }
        out.flush();
        persist();
    }

    // ------------------------------------------------------------------ m3u8 下载

    private void downloadHls(DownloadTask task, String url, Map<String, String> headers) throws Exception {
        File index = DownloadPathUtil.getHlsIndex(task.dramaName, task.episodeName);
        if (index.exists() && index.length() > 0) {
            task.filePath = index.getAbsolutePath();
            setStatus(task, DownloadTask.Status.DONE);
            return;
        }
        setStatus(task, DownloadTask.Status.DOWNLOADING);
        String playlist = OkHttp.string(url, headers, TIMEOUT_MS);
        if (TextUtils.isEmpty(playlist)) throw new IOException("播放列表拉取失败");
        downloadHlsFromPlaylist(task, url, playlist, headers);
    }

    private void downloadHlsFromPlaylist(DownloadTask task, String playlistUrl, String playlist, Map<String, String> headers) throws Exception {
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            // master 列表：选最大码率的 variant。
            String variant = pickBestVariant(playlistUrl, playlist);
            if (TextUtils.isEmpty(variant)) throw new IOException("播放列表解析失败");
            String media = OkHttp.string(variant, headers, TIMEOUT_MS);
            if (TextUtils.isEmpty(media)) throw new IOException("播放列表拉取失败");
            downloadHlsFromPlaylist(task, variant, media, headers);
            return;
        }
        File dir = DownloadPathUtil.getHlsDir(task.dramaName, task.episodeName);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("创建下载目录失败");
        File index = new File(dir, "index.m3u8");
        if (index.exists() && index.length() > 0) {
            task.filePath = index.getAbsolutePath();
            setStatus(task, DownloadTask.Status.DONE);
            return;
        }
        String[] lines = playlist.split("\n");
        List<String> segmentUrls = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            segmentUrls.add(line);
        }
        task.segmentsTotal = segmentUrls.size();
        task.segmentsDone = 0;
        if (segmentUrls.isEmpty()) throw new IOException("播放列表里没有分段");
        OkHttpClient client = OkHttp.client(TIMEOUT_MS);
        Map<String, String> keyFiles = new HashMap<>();
        Map<String, String> mapFiles = new HashMap<>();
        List<String> outLines = new ArrayList<>();
        int segmentIndex = 0;
        String pendingKeyUri = null;
        String pendingMapUri = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY")) {
                pendingKeyUri = attributeValue(line, "URI");
                if (TextUtils.isEmpty(pendingKeyUri)) {
                    // METHOD=NONE 等无 URI 的情况：原样保留，不下载。
                    outLines.add(line);
                    continue;
                }
                String localKey = keyFiles.get(pendingKeyUri);
                if (localKey == null) {
                    localKey = "key-" + keyFiles.size() + ".key";
                    keyFiles.put(pendingKeyUri, localKey);
                    downloadSmallFile(client, resolveUrl(playlistUrl, pendingKeyUri), headers, new File(dir, localKey), task);
                }
                outLines.add(replaceAttribute(line, "URI", localKey));
                continue;
            }
            if (line.startsWith("#EXT-X-MAP")) {
                pendingMapUri = attributeValue(line, "URI");
                String localMap = mapFiles.get(pendingMapUri);
                if (localMap == null) {
                    localMap = "init-" + mapFiles.size() + ".mp4";
                    mapFiles.put(pendingMapUri, localMap);
                    downloadSmallFile(client, resolveUrl(playlistUrl, pendingMapUri), headers, new File(dir, localMap), task);
                }
                outLines.add(replaceAttribute(line, "URI", localMap));
                continue;
            }
            if (line.startsWith("#")) {
                outLines.add(line);
                continue;
            }
            // 分段：逐个下载，失败重试 3 次，断点续传。
            segmentIndex++;
            String ext = extensionOf(line);
            if (TextUtils.isEmpty(ext)) ext = "ts";
            String localName = String.format("seg-%05d.%s", segmentIndex, ext);
            File segmentFile = new File(dir, localName);
            String segmentUrl = resolveUrl(playlistUrl, line);
            downloadSegmentWithRetry(client, segmentUrl, headers, segmentFile, task);
            task.segmentsDone = segmentIndex;
            notifyChanged(false);
            outLines.add(localName);
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(index), StandardCharsets.UTF_8)) {
            for (String outLine : outLines) {
                writer.write(outLine);
                writer.write("\n");
            }
        }
        task.filePath = index.getAbsolutePath();
        persist();
        setStatus(task, DownloadTask.Status.DONE);
    }

    private String pickBestVariant(String baseUrl, String playlist) {
        String[] lines = playlist.split("\n");
        String bestUri = null;
        long bestBandwidth = -1;
        for (int i = 0; i + 1 < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            long bandwidth = parseBandwidth(line);
            String uri = lines[i + 1].trim();
            if (uri.isEmpty() || uri.startsWith("#")) continue;
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth;
                bestUri = uri;
            }
        }
        if (bestUri == null) return "";
        return resolveUrl(baseUrl, bestUri);
    }

    private long parseBandwidth(String line) {
        try {
            int start = line.indexOf("BANDWIDTH=");
            if (start < 0) return 0;
            int end = line.indexOf(',', start);
            String value = end < 0 ? line.substring(start + 10) : line.substring(start + 10, end);
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String attributeValue(String line, String name) {
        int start = line.indexOf(name + "=\"");
        if (start < 0) return "";
        int valueStart = start + name.length() + 2;
        int valueEnd = line.indexOf('"', valueStart);
        if (valueEnd < 0) return "";
        return line.substring(valueStart, valueEnd);
    }

    private String replaceAttribute(String line, String name, String value) {
        int start = line.indexOf(name + "=\"");
        if (start < 0) return line;
        int valueStart = start + name.length() + 2;
        int valueEnd = line.indexOf('"', valueStart);
        if (valueEnd < 0) return line;
        return line.substring(0, valueStart) + value + line.substring(valueEnd);
    }

    private String resolveUrl(String baseUrl, String relative) {
        if (TextUtils.isEmpty(relative)) return "";
        try {
            return new URL(new URL(baseUrl), relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private void downloadSmallFile(OkHttpClient client, String url, Map<String, String> headers, File target, DownloadTask task) throws Exception {
        if (target.exists() && target.length() > 0) return;
        downloadSegmentWithRetry(client, url, headers, target, task);
    }

    private void downloadSegmentWithRetry(OkHttpClient client, String url, Map<String, String> headers, File target, DownloadTask task) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (isCancelled(task)) throw new CancelledException();
            try {
                downloadFileOnce(client, url, headers, target, task);
                return;
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("分段下载失败") : lastError;
    }

    /** 单个文件下载（分段/key/初始化段共用）：支持 Range 续传，就地写目标文件。 */
    private void downloadFileOnce(OkHttpClient client, String url, Map<String, String> headers, File target, DownloadTask task) throws Exception {
        long existing = target.exists() ? target.length() : 0;
        Map<String, String> requestHeaders = new HashMap<>(headers);
        if (existing > 0) requestHeaders.put("Range", "bytes=" + existing + "-");
        Call call = OkHttp.newCall(client, url, requestHeaders);
        try (Response response = call.execute()) {
            if (response.code() == 416) return; // 已下完
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("空响应");
            if (response.code() == 200 && existing > 0) {
                target.delete();
                existing = 0;
            }
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(target, existing > 0)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (isCancelled(task)) throw new CancelledException();
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        }
    }
}
