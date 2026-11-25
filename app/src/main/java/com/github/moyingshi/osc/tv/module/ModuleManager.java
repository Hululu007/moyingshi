package com.github.moyingshi.osc.tv.module;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Response;

/**
 * 模块管理器：UI 与各功能模块之间的唯一门面。
 *
 * <p>存放：应用私有目录 {@code getDir("modules")/<id>/}（禁止外部存储，见评审威胁模型）。
 * 安装状态以该目录下的 manifest.json 为准；版本绑定以 manifest 的 appVersionCode 范围为准，
 * App 升级后旧模块判为 {@link ModuleStatus#OUTDATED}，首次使用时重新下载。
 * 加载：{@link #ensureLoaded} 按 {@link ModuleDefinition#loadOrder} 用 {@code System.load(绝对路径)}
 * 预载；加载失败记 loadError 并返回 false，绝不抛到调用方（回落逻辑在调用处）。
 * 下载：见 {@link ModuleDownloader}（走 GitHub 代理 + 断点续传 + 代理失败回退直连）。
 * 本类所有方法（除特别注明）都可能做文件 IO / 网络，调用方保证在工作线程执行。
 */
public final class ModuleManager {

    private static final String REPO = "xinyonghuu/moyingshi";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + REPO + "/releases?per_page=1";
    private static final String MODULES_DIR = "modules";
    private static final String DOWNLOADS_DIR = ".downloads";
    private static final String MANIFEST_NAME = "manifest.json";

    private static volatile ModuleManager instance;

    private final Context appContext;
    private final Set<String> loaded = new HashSet<>();
    private final Map<String, Throwable> loadErrors = new HashMap<>();

    /** 下载回调（调用线程即工作线程，UI 侧自行 post 到主线程）。 */
    public interface DownloadCallback {
        void onProgress(long done, long total);

        /** downloadError 非空表示下载失败（此时 results 为空）；否则看每个模块的安装结果。 */
        void onFinished(List<ModuleInstaller.InstallResult> results, String downloadError);
    }

    public static ModuleManager getInstance(Context context) {
        ModuleManager local = instance;
        if (local == null) {
            synchronized (ModuleManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new ModuleManager(context.getApplicationContext());
                }
            }
        }
        return local;
    }

    private ModuleManager(Context appContext) {
        this.appContext = appContext;
    }

    // ---------- 目录与状态 ----------

    public File modulesRoot() {
        return appContext.getDir(MODULES_DIR, Context.MODE_PRIVATE);
    }

    public File moduleDir(String moduleId) {
        return new File(modulesRoot(), moduleId);
    }

    /** 模块目录内某文件的绝对路径（供 MPVLib 等按绝对路径 System.load）。 */
    public File moduleFile(String moduleId, String name) {
        return new File(moduleDir(moduleId), name);
    }

    public String abi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 ? abis[0] : "arm64-v8a";
    }

    public int appVersionCode() {
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** 读已安装模块的 manifest；未安装/读不出返回 null。 */
    public ModuleManifest installedManifest(String moduleId) {
        File manifestFile = new File(moduleDir(moduleId), MANIFEST_NAME);
        if (!manifestFile.isFile()) return null;
        try {
            String json = new String(java.nio.file.Files.readAllBytes(manifestFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return ModuleManifest.parse(json);
        } catch (Exception e) {
            return null;
        }
    }

    public ModuleStatus getStatus(String moduleId) {
        ModuleManifest manifest = installedManifest(moduleId);
        if (manifest == null) {
            return moduleDir(moduleId).exists() ? ModuleStatus.CORRUPT : ModuleStatus.NOT_INSTALLED;
        }
        String error = manifest.validate(moduleId, abi(), appVersionCode());
        if (error != null) {
            // 只有版本范围问题算过期，其余（ABI/清单损坏）算损坏。
            if (appVersionCode() < manifest.appVersionCodeMin
                    || appVersionCode() > manifest.appVersionCodeMax) {
                return ModuleStatus.OUTDATED;
            }
            return ModuleStatus.CORRUPT;
        }
        String quick = ModuleVerifier.quickCheckFiles(moduleDir(moduleId), manifest);
        return quick == null ? ModuleStatus.INSTALLED : ModuleStatus.CORRUPT;
    }

    public boolean isInstalled(String moduleId) {
        return getStatus(moduleId) == ModuleStatus.INSTALLED;
    }

    /** 已安装模块占用的字节数（未安装返回 0）。 */
    public long installedSizeBytes(String moduleId) {
        File dir = moduleDir(moduleId);
        if (!dir.isDirectory()) return 0L;
        long size = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) if (file.isFile()) size += file.length();
        }
        return size;
    }

    /** 上次 ensureLoaded 失败的原因（供管理页展示“损坏/加载失败”）。 */
    public synchronized Throwable getLoadError(String moduleId) {
        return loadErrors.get(moduleId);
    }

    // ---------- 加载 ----------

    /**
     * 确保模块已预载：已安装且校验通过时按 loadOrder 逐个 System.load。
     * 同一模块只载一次；失败返回 false（不抛异常，调用方走回落）。
     */
    public boolean ensureLoaded(ModuleDefinition definition) {
        if (definition == null) return false;
        synchronized (this) {
            if (loaded.contains(definition.id)) return true;
        }
        if (!isInstalled(definition.id)) return false;
        File dir = moduleDir(definition.id);
        for (String name : definition.loadOrder) {
            File file = new File(dir, name);
            if (!file.isFile()) {
                recordError(definition.id, new IOException("模块文件缺失：" + name));
                return false;
            }
            try {
                System.load(file.getAbsolutePath());
            } catch (Throwable error) {
                recordError(definition.id, error);
                return false;
            }
        }
        synchronized (this) {
            loaded.add(definition.id);
        }
        return true;
    }

    private synchronized void recordError(String moduleId, Throwable error) {
        loadErrors.put(moduleId, error);
    }

    // ---------- 安装 / 删除 ----------

    /** 安装一个已下载/已导入的 zip（分包或总包），返回每个模块的结果。 */
    public List<ModuleInstaller.InstallResult> installZip(File zipFile) {
        return ModuleInstaller.installZip(zipFile, modulesRoot(), abi(), appVersionCode());
    }

    /** 删除模块（目录整体删；已加载的 .so 在进程内无法卸载，下次启动即不再加载）。 */
    public boolean deleteModule(String moduleId) {
        synchronized (this) {
            loaded.remove(moduleId);
            loadErrors.remove(moduleId);
        }
        File dir = moduleDir(moduleId);
        if (!dir.exists()) return true;
        ModuleInstaller.deleteRecursive(dir);
        return !dir.exists();
    }

    // ---------- 在线下载 ----------

    /** 当前 Wi-Fi 是否可用：下载前由 UI 判断，移动网络下先弹确认（评审下载体验）。 */
    public boolean isWifi() {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            NetworkCapabilities capabilities =
                    manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /** 本版 App 对应的 release tag：CI 打包时由 WEBHTV_RELEASE_TAG 写入 BuildConfig。 */
    public String releaseTag() {
        String tag = BuildConfig.BUILD_TAG;
        if (!TextUtils.isEmpty(tag)) return tag;
        return fetchLatestReleaseTag();
    }

    /** 模块分包的下载地址（GitHub release 附件）。 */
    public String zipUrl(ModuleDefinition definition, String tag) {
        return "https://github.com/" + REPO + "/releases/download/" + tag + "/"
                + definition.zipName(abi(), appVersionCode());
    }

    /** 总包的下载地址（一次下全 6 个模块）。 */
    public String totalBundleUrl(String tag) {
        return "https://github.com/" + REPO + "/releases/download/" + tag + "/"
                + ModuleDefinition.totalBundleName(abi(), appVersionCode());
    }

    /**
     * 下载并安装一个模块的分包（同步，调用方在工作线程执行；断点续传、进度回调、
     * 代理失败回退直连见 ModuleDownloader；下载成功后临时 zip 删除）。
     */
    public void downloadAndInstall(ModuleDefinition definition,
                                   ModuleProgress.CancellationToken cancel,
                                   DownloadCallback callback) {
        String tag = releaseTag();
        if (TextUtils.isEmpty(tag)) {
            callback.onFinished(new ArrayList<>(), "无法确定当前版本，请检查网络后重试");
            return;
        }
        File downloads = new File(modulesRoot(), DOWNLOADS_DIR);
        downloads.mkdirs();
        File target = new File(downloads, definition.zipName(abi(), appVersionCode()));
        String error = ModuleDownloader.download(zipUrl(definition, tag), target,
                callback::onProgress, cancel);
        if (error != null) {
            callback.onFinished(new ArrayList<>(), error);
            return;
        }
        List<ModuleInstaller.InstallResult> results = installZip(target);
        target.delete();
        callback.onFinished(results, null);
    }

    /** 下载并安装总包（一次装全 6 个模块；某个分包失败不影响其他分包）。 */
    public void downloadAndInstallAll(ModuleProgress.CancellationToken cancel,
                                      DownloadCallback callback) {
        String tag = releaseTag();
        if (TextUtils.isEmpty(tag)) {
            callback.onFinished(new ArrayList<>(), "无法确定当前版本，请检查网络后重试");
            return;
        }
        File downloads = new File(modulesRoot(), DOWNLOADS_DIR);
        downloads.mkdirs();
        File target = new File(downloads,
                ModuleDefinition.totalBundleName(abi(), appVersionCode()));
        String error = ModuleDownloader.download(totalBundleUrl(tag), target,
                callback::onProgress, cancel);
        if (error != null) {
            callback.onFinished(new ArrayList<>(), error);
            return;
        }
        List<ModuleInstaller.InstallResult> results = installZip(target);
        target.delete();
        callback.onFinished(results, null);
    }

    /** 兜底：BuildConfig 里没有 tag 时查 GitHub API 取最新（含预发布）release 的 tag。 */
    private String fetchLatestReleaseTag() {
        try (Response response = OkHttp.newCall(OkHttp.client(30_000L), RELEASES_API).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String body = response.body().string();
            Release[] releases = new Gson().fromJson(body, Release[].class);
            if (releases != null && releases.length > 0
                    && !TextUtils.isEmpty(releases[0].tagName)) {
                return releases[0].tagName;
            }
        } catch (Exception ignored) {
            // 无网/API 失败时返回 null，调用方报“无法确定当前版本”。
        }
        return null;
    }

    private static final class Release {
        @SerializedName("tag_name")
        String tagName;
    }
}
