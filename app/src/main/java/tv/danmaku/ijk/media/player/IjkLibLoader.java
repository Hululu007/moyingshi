package tv.danmaku.ijk.media.player;

import android.util.Log;
import java.util.Arrays;

/* loaded from: classes3.dex */
public abstract class IjkLibLoader {
    private static final String TAG = "IjkLibLoader";
    private boolean isAvailable;
    private boolean loadAttempted;
    private final String[] nativeLibraries;

    public IjkLibLoader(String... strArr) {
        this.nativeLibraries = strArr;
    }

    public synchronized boolean isAvailable() {
        if (this.loadAttempted) {
            return this.isAvailable;
        }
        this.loadAttempted = true;
        try {
            for (String str : this.nativeLibraries) {
                loadLibrary(str);
            }
            this.isAvailable = true;
        } catch (Throwable unused) {
            // 失败不缓存：模块后装上时下一次调用重新尝试加载
            //（旧代码一旦失败整进程判不可用，用户装完模块仍播不了 ijk）。
            this.loadAttempted = false;
            Log.w(TAG, "Failed to load " + Arrays.toString(this.nativeLibraries));
        }
        return this.isAvailable;
    }

    public abstract void loadLibrary(String str);
}
