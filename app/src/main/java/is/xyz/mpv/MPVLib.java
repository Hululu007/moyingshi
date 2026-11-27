package is.xyz.mpv;

import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.github.aiyingshi.osc.tv.module.ModuleDefinition;
import com.github.aiyingshi.osc.tv.module.ModuleManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MPVLib {

    private static final String TAG = "mpv";
    // 预载顺序见 ModuleDefinition.MPV.loadOrder（与旧 LOAD_ORDER 同顺序）。
    private static final List<EventObserver> OBSERVERS = new ArrayList<>();
    private static final List<LogObserver> LOG_OBSERVERS = new ArrayList<>();
    private static boolean loaded;
    private static Throwable loadError;
    private static String loadedAbi;
    private static Boolean bundledVulkanEnabled;
    private static long bundledVulkanStamp;
    private static Boolean deviceVulkan13Capable;
    private static final long CONTEXT_RECREATE_COOLDOWN_MS = 350;
    private static final long CONTEXT_SHUTDOWN_TIMEOUT_MS = 2000;
    private static long lastContextDestroyedAtMs;
    private static boolean contextCreationAttempted;
    private static boolean contextCreated;
    private static boolean contextDestroying;

    private MPVLib() {
    }

    /**
     * 从 mpv 模块（已下载的 zip 安装到私有目录）加载原生库，不再从 assets 拷 bundle。
     * 模块未安装/损坏时返回 false（不抛），MpvPlayer 走现有播放错误链；
     * 不缓存失败：模块装好或重装后下一次调用会重新尝试加载。
     */
    public static synchronized boolean ensureLoaded(Context context) {
        if (loaded) return true;
        Context app = context.getApplicationContext();
        ModuleManager modules = ModuleManager.getInstance(app);
        if (!modules.ensureLoaded(ModuleDefinition.MPV)) {
            Throwable error = modules.getLoadError(ModuleDefinition.MPV.id);
            loadError = error != null ? error
                    : new IOException("MPV 模块未安装，请到模块里面下载 MPV");
            com.github.aiyingshi.osc.tv.player.NativeLibraryDiagnostics.failure("mpv-bundle", loadError);
            Log.e(TAG, "Unable to load MPV native libraries from module", loadError);
            return false;
        }
        loadError = null;
        loadedAbi = modules.abi();
        loaded = true;
        com.github.aiyingshi.osc.tv.player.NativeLibraryDiagnostics.request();
        return true;
    }

    public static synchronized Throwable getLoadError() {
        return loadError;
    }

    public static synchronized String getLoadedAbi() {
        return loadedAbi;
    }

    public static synchronized boolean isBundledVulkanEnabled(Context context) {
        Context app = context.getApplicationContext();
        ModuleManager modules = ModuleManager.getInstance(app);
        // 缓存按模块安装戳失效：模块装好/重装后 libmpv.so 换了，Vulkan 能力要重测。
        File libmpv = modules.moduleFile(ModuleDefinition.MPV.id, "libmpv.so");
        long stamp = libmpv.lastModified();
        if (bundledVulkanEnabled != null && stamp == bundledVulkanStamp) {
            return bundledVulkanEnabled;
        }
        try {
            bundledVulkanEnabled = stamp != 0 && hasBundledFeature(libmpv, "vulkan");
        } catch (Throwable e) {
            bundledVulkanEnabled = false;
            Log.w(TAG, "Unable to detect bundled MPV Vulkan support", e);
        }
        bundledVulkanStamp = stamp;
        return bundledVulkanEnabled;
    }

    public static synchronized boolean isDeviceVulkan13Capable(Context context) {
        if (deviceVulkan13Capable != null) return deviceVulkan13Capable;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                deviceVulkan13Capable = false;
                return false;
            }
            PackageManager pm = context.getApplicationContext().getPackageManager();
            int glesVersion = 0;
            FeatureInfo[] features = pm.getSystemAvailableFeatures();
            if (features != null) {
                for (FeatureInfo feature : features) {
                    if (feature != null && feature.name == null) {
                        glesVersion = feature.reqGlEsVersion;
                        break;
                    }
                }
            }
            if (glesVersion < 0x00030001) {
                deviceVulkan13Capable = false;
                return false;
            }
            deviceVulkan13Capable = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x00403000);
        } catch (Throwable e) {
            deviceVulkan13Capable = false;
            Log.w(TAG, "Unable to detect device Vulkan support", e);
        }
        return deviceVulkan13Capable;
    }

    public static boolean isVulkanRendererAvailable(Context context) {
        return isBundledVulkanEnabled(context) && isDeviceVulkan13Capable(context);
    }

    private static boolean hasBundledFeature(File library, String feature) throws IOException {
        try (InputStream in = new FileInputStream(library)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            String text = out.toString(StandardCharsets.ISO_8859_1.name());
            int start = text.indexOf("List of enabled features:");
            if (start < 0) return false;
            int end = text.indexOf('\0', start);
            String features = text.substring(start, end > start ? end : Math.min(text.length(), start + 2048));
            for (String token : features.split("\\s+")) if (feature.equals(token)) return true;
            return false;
        }
    }

    public static native void create(Context appctx);

    public static native void init();

    public static native int destroy();

    public static synchronized void initializeCreatedContext() {
        try {
            init();
        } catch (RuntimeException error) {
            contextCreated = false;
            contextCreationAttempted = false;
            contextDestroying = false;
            lastContextDestroyedAtMs = SystemClock.elapsedRealtime();
            MPVLib.class.notifyAll();
            throw error;
        }
    }

    public static synchronized boolean tryCreate(Context appctx) {
        if (!awaitContextShutdown()) return false;
        if (contextCreationAttempted) {
            Log.w(TAG, "Ignore duplicate MPV context creation");
            return false;
        }
        long elapsed = SystemClock.elapsedRealtime() - lastContextDestroyedAtMs;
        if (lastContextDestroyedAtMs > 0 && elapsed < CONTEXT_RECREATE_COOLDOWN_MS) {
            long waitMs = CONTEXT_RECREATE_COOLDOWN_MS - elapsed;
            Log.i(TAG, "Waiting " + waitMs + "ms for previous MPV/HWUI teardown");
            SystemClock.sleep(waitMs);
        }
        contextCreationAttempted = true;
        try {
            create(appctx);
            contextCreated = true;
            return true;
        } catch (RuntimeException | Error error) {
            contextCreationAttempted = false;
            throw error;
        }
    }

    private static boolean awaitContextShutdown() {
        if (!contextDestroying) return true;
        long deadline = SystemClock.elapsedRealtime() + CONTEXT_SHUTDOWN_TIMEOUT_MS;
        while (contextDestroying) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                Log.w(TAG, "Timed out waiting for previous MPV context shutdown");
                return false;
            }
            try {
                MPVLib.class.wait(Math.min(remaining, 100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for MPV shutdown", e);
            }
        }
        return true;
    }

    public static synchronized void destroyCreatedContext() {
        if (!contextCreated) return;
        try {
            int result = destroy();
            // The native no-event-thread path can deliver MPV_EVENT_SHUTDOWN
            // synchronously from destroy(). In that case event() already
            // cleared contextCreated/contextDestroying before this call
            // returns, so do not reintroduce a phantom pending shutdown.
            contextDestroying = result >= MpvError.MPV_ERROR_SUCCESS && contextCreated;
            if (result < MpvError.MPV_ERROR_SUCCESS) {
                Log.w(TAG, "MPV context destroy failed: " + result);
            }
        } finally {
            contextCreated = false;
            contextCreationAttempted = false;
            if (!contextDestroying) lastContextDestroyedAtMs = SystemClock.elapsedRealtime();
        }
    }

    public static native void attachSurface(Surface surface);

    public static native void replaceSurface(Surface surface);

    public static native void detachSurface();

    public static native void attachOsdSurface(Surface surface);

    public static native void replaceOsdSurface(Surface surface);

    public static native void detachOsdSurface();

    public static native int enqueueOsdSurface(long requestId, Surface surface);

    public static native int command(String[] cmd);

    public static native int enqueueCommand(long requestId, String[] cmd);

    public static native int setOptionString(String name, String value);

    public static native Bitmap grabThumbnail(int dimension);

    public static native Integer getPropertyInt(String property);

    public static native int setPropertyInt(String property, int value);

    public static native Double getPropertyDouble(String property);

    public static native int setPropertyDouble(String property, double value);

    public static native Boolean getPropertyBoolean(String property);

    public static native int setPropertyBoolean(String property, boolean value);

    public static native String getPropertyString(String property);

    public static native int setPropertyString(String property, String value);

    public static native byte[] getPropertyByteArray(String property);

    public static native void dumpTrackList();

    public static native int observeProperty(String property, int format);

    public static void addObserver(EventObserver observer) {
        synchronized (OBSERVERS) {
            OBSERVERS.add(observer);
        }
    }

    public static void removeObserver(EventObserver observer) {
        synchronized (OBSERVERS) {
            OBSERVERS.remove(observer);
        }
    }

    public static void eventProperty(String property, long value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, boolean value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, double value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, String value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventProperty(property);
        }
    }

    public static void eventPropertyNode(String property, String json) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventPropertyNode(property, json);
        }
    }

    public static void event(int eventId) {
        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN) {
            synchronized (MPVLib.class) {
                contextDestroying = false;
                contextCreated = false;
                contextCreationAttempted = false;
                lastContextDestroyedAtMs = SystemClock.elapsedRealtime();
                MPVLib.class.notifyAll();
            }
        }
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.event(eventId);
        }
    }

    public static void eventCommandReply(long requestId, int error) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.eventCommandReply(requestId, error);
        }
    }

    public static void eventEndFile(int reason, int error, String errorText) {
        endFile(reason, error, errorText);
    }

    public static void endFile(int reason, int error, String errorText) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : OBSERVERS) observer.endFile(reason, error, errorText);
        }
    }

    public static void addLogObserver(LogObserver observer) {
        synchronized (LOG_OBSERVERS) {
            LOG_OBSERVERS.add(observer);
        }
    }

    public static void removeLogObserver(LogObserver observer) {
        synchronized (LOG_OBSERVERS) {
            LOG_OBSERVERS.remove(observer);
        }
    }

    public static void logMessage(String prefix, int level, String text) {
        synchronized (LOG_OBSERVERS) {
            for (LogObserver observer : LOG_OBSERVERS) observer.logMessage(prefix, level, text);
        }
    }

    public interface EventObserver {
        default void eventPropertyNode(String property, String json) {
        }

        void eventProperty(String property);

        void eventProperty(String property, long value);

        void eventProperty(String property, boolean value);

        void eventProperty(String property, String value);

        void eventProperty(String property, double value);

        void event(int eventId);

        default void eventCommandReply(long requestId, int error) {
        }

        default void endFile(int reason, int error, String errorText) {
            event(MpvEvent.MPV_EVENT_END_FILE);
        }
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    public static final class MpvFormat {
        public static final int MPV_FORMAT_NONE = 0;
        public static final int MPV_FORMAT_STRING = 1;
        public static final int MPV_FORMAT_OSD_STRING = 2;
        public static final int MPV_FORMAT_FLAG = 3;
        public static final int MPV_FORMAT_INT64 = 4;
        public static final int MPV_FORMAT_DOUBLE = 5;
        public static final int MPV_FORMAT_NODE = 6;
        public static final int MPV_FORMAT_NODE_ARRAY = 7;
        public static final int MPV_FORMAT_NODE_MAP = 8;
        public static final int MPV_FORMAT_BYTE_ARRAY = 9;

        private MpvFormat() {
        }
    }

    public static final class MpvEvent {
        public static final int MPV_EVENT_NONE = 0;
        public static final int MPV_EVENT_SHUTDOWN = 1;
        public static final int MPV_EVENT_LOG_MESSAGE = 2;
        public static final int MPV_EVENT_GET_PROPERTY_REPLY = 3;
        public static final int MPV_EVENT_SET_PROPERTY_REPLY = 4;
        public static final int MPV_EVENT_COMMAND_REPLY = 5;
        public static final int MPV_EVENT_START_FILE = 6;
        public static final int MPV_EVENT_END_FILE = 7;
        public static final int MPV_EVENT_FILE_LOADED = 8;
        public static final int MPV_EVENT_IDLE = 11;
        public static final int MPV_EVENT_TICK = 14;
        public static final int MPV_EVENT_CLIENT_MESSAGE = 16;
        public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
        public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
        public static final int MPV_EVENT_SEEK = 20;
        public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
        public static final int MPV_EVENT_PROPERTY_CHANGE = 22;
        public static final int MPV_EVENT_QUEUE_OVERFLOW = 24;
        public static final int MPV_EVENT_HOOK = 25;

        private MpvEvent() {
        }
    }

    public static final class MpvEndFileReason {
        public static final int MPV_END_FILE_REASON_UNKNOWN = -1;
        public static final int MPV_END_FILE_REASON_EOF = 0;
        public static final int MPV_END_FILE_REASON_STOP = 2;
        public static final int MPV_END_FILE_REASON_QUIT = 3;
        public static final int MPV_END_FILE_REASON_ERROR = 4;
        public static final int MPV_END_FILE_REASON_REDIRECT = 5;

        private MpvEndFileReason() {
        }
    }

    public static final class MpvError {
        public static final int MPV_ERROR_SUCCESS = 0;
        public static final int MPV_ERROR_EVENT_QUEUE_FULL = -1;
        public static final int MPV_ERROR_NOMEM = -2;
        public static final int MPV_ERROR_UNINITIALIZED = -3;
        public static final int MPV_ERROR_INVALID_PARAMETER = -4;
        public static final int MPV_ERROR_OPTION_NOT_FOUND = -5;
        public static final int MPV_ERROR_OPTION_FORMAT = -6;
        public static final int MPV_ERROR_OPTION_ERROR = -7;
        public static final int MPV_ERROR_PROPERTY_NOT_FOUND = -8;
        public static final int MPV_ERROR_PROPERTY_FORMAT = -9;
        public static final int MPV_ERROR_PROPERTY_UNAVAILABLE = -10;
        public static final int MPV_ERROR_PROPERTY_ERROR = -11;
        public static final int MPV_ERROR_COMMAND = -12;
        public static final int MPV_ERROR_LOADING_FAILED = -13;
        public static final int MPV_ERROR_AO_INIT_FAILED = -14;
        public static final int MPV_ERROR_VO_INIT_FAILED = -15;
        public static final int MPV_ERROR_NOTHING_TO_PLAY = -16;
        public static final int MPV_ERROR_UNKNOWN_FORMAT = -17;
        public static final int MPV_ERROR_UNSUPPORTED = -18;
        public static final int MPV_ERROR_NOT_IMPLEMENTED = -19;
        public static final int MPV_ERROR_GENERIC = -20;

        private MpvError() {
        }
    }
}
