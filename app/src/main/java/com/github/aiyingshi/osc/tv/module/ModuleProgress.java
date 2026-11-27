package com.github.aiyingshi.osc.tv.module;

/** 下载/安装进度与取消：调用方在工作线程跑，回调也在同一线程，UI 侧自行 post 到主线程。 */
public final class ModuleProgress {

    /** 已下载/已处理字节数，total <= 0 表示未知。 */
    public interface Listener {
        void onProgress(long done, long total);
    }

    /** 取消令牌：下载循环每块检查一次；取消后保留已下载的半截文件，下次可断点续传。 */
    public static final class CancellationToken {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private ModuleProgress() {
    }
}
