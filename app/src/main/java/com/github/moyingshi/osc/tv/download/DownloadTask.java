package com.github.moyingshi.osc.tv.download;

/**
 * 一条下载任务。Gson 持久化到内部存储 download_tasks.json，字段用 public 以便直接读写。
 */
public class DownloadTask {

    public enum Status {
        QUEUED, RESOLVING, DOWNLOADING, PAUSED, DONE, FAILED
    }

    /** 任务唯一标识：剧名 + 集名（清洗后拼接），同一部剧同一集只建一条任务。 */
    public String id;
    /** 展示用剧名（原始，未清洗）。 */
    public String dramaName;
    /** 展示用集名（原始，未清洗）。 */
    public String episodeName;
    /** 站点 key，用于解析播放地址。 */
    public String siteKey;
    /** 当前线路 flag，用于解析播放地址。 */
    public String flag;
    /** 集的 spider 地址（playerContent 的 id 参数）。 */
    public String episodeUrl;
    /** 解析后的直链地址（m3u8 或文件直链），解析完成前为空。 */
    public String resolvedUrl;
    /** 最终文件绝对路径：直链是视频文件，m3u8 是本地 index.m3u8。 */
    public String filePath;
    public Status status = Status.QUEUED;
    public long downloadedBytes;
    /** 直链总字节数；m3u8 时为 -1（用段数算进度）。 */
    public long totalBytes = -1;
    /** m3u8 已下载段数。 */
    public int segmentsDone;
    /** m3u8 总段数。 */
    public int segmentsTotal;
    /** 失败原因（展示用）。 */
    public String error;
    public long createdAt;

    public boolean isDone() {
        return status == Status.DONE;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RESOLVING || status == Status.DOWNLOADING;
    }

    public boolean isPaused() {
        return status == Status.PAUSED;
    }

    /** 0..100 的进度；总数未知时按段数算，段数未知时返回 -1。 */
    public int progress() {
        if (totalBytes > 0) return (int) Math.min(100, downloadedBytes * 100 / totalBytes);
        if (segmentsTotal > 0) return Math.min(100, segmentsDone * 100 / segmentsTotal);
        return -1;
    }
}
