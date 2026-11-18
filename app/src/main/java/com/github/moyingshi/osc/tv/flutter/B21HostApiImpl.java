package com.github.moyingshi.osc.tv.flutter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.lab.LabActions;
import com.github.moyingshi.osc.tv.lab.LabCommandEditDialog;
import com.github.moyingshi.osc.tv.lab.LabCommandSheet;
import com.github.moyingshi.osc.tv.lab.LabConfig;
import com.github.moyingshi.osc.tv.lab.LabCustomCommands;
import com.github.moyingshi.osc.tv.lab.LabEnv;
import com.github.moyingshi.osc.tv.lab.LabFilePicker;
import com.github.moyingshi.osc.tv.lab.LabModels;
import com.github.moyingshi.osc.tv.lab.LabProcManager;
import com.github.moyingshi.osc.tv.lab.LabRunner;
import com.github.moyingshi.osc.tv.lab.LabTerminalActivity;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.utils.FileChooser;
import com.github.moyingshi.osc.tv.utils.LocalSource;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.Util;

import io.flutter.plugin.common.BinaryMessenger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * B8 B21: host side of the mobile lab list + detail pages (native
 * LabActivity, 940 lines, + LabDetailActivity, 435 lines).
 *
 * <p>Shape follows B19 (single worker thread + main-thread Completion
 * with a watchdog, one impl per engine) and B20 (static per-messenger
 * registry + rebind on rotation, so a file pick or an import running
 * through a rotation still completes instead of timing out). Unlike
 * B20 this impl lives in the main source set, so no reflection
 * indirection is needed: C1ScreenActivity and C0ShellActivity call
 * {@link #register} / {@link #unregister} directly.
 *
 * <p>Everything that executes (install, uninstall, run, terminal,
 * export, settings save, import, scan) is 100% host-side, mirroring
 * the native Activities step for step; Dart only sends action
 * requests and renders the @async snapshots. The terminal and output
 * pages stay native (plan §6 P4): the terminal button opens the
 * native LabTerminalActivity, the command sheet opens the native
 * LabOutputActivity through LabActions. No EventChannel anywhere:
 * the list page polls getLabList() every 2s and the detail page
 * polls pollLabDetail() every 2s (native updateButtons cadence);
 * file/dir pickers use the B19 500ms pick polling instead of a
 * watchdog because they wait on the user.
 *
 * <p>Watchdogs: 60s on the calls that reload the lab config (the
 * source can be a network URL), 35s on the cached-root snapshots
 * (plan §4.6 convention). See docs/b8-b21-lab.md.
 */
public final class B21HostApiImpl implements B21Messages.B21HostApi {

    private static final String TAG = "B21HostApiImpl";

    /** Config-loading calls (URL source can hit the network). */
    private static final long LOAD_TIMEOUT_MS = 60_000L;
    /** Cached-root snapshots (no config load, no network). */
    private static final long SNAPSHOT_TIMEOUT_MS = 35_000L;

    /** Classic startActivityForResult codes (C1ScreenActivity forwards them here). */
    public static final int REQUEST_IMPORT = 21001;
    public static final int REQUEST_LOCAL_CONFIG = 21002;

    /** One impl per engine (C0 and C1 engines each get their own). */
    private static final Map<BinaryMessenger, B21HostApiImpl> IMPLS = new HashMap<>();

    private volatile AppCompatActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private volatile boolean loadedOnce;

    /** Native LabActivity.reload(): per-Activity attempted set. */
    private final Set<String> autoInstallAttempted = new HashSet<>();

    /** List pick/scan state (import archive + local-dir scan). Main thread writes, worker reads for snapshots. */
    private volatile String listPickState = "idle";
    private volatile long listPickEpoch;
    private volatile String listPickMessage = "";

    /** Local-config-file pick state (lab settings dialog). */
    private volatile String settingsPickState = "idle";
    private volatile long settingsPickEpoch;
    private volatile String settingsPickMessage = "";

    /** Package-settings directory pick state (one row at a time). */
    private volatile String formPickState = "idle";
    private volatile long formPickEpoch;
    private volatile String formPickValue = "";

    private B21HostApiImpl(AppCompatActivity activity) {
        this.activity = activity;
    }

    /**
     * Registers the B21 API on the given messenger (first call per
     * engine), or rebinds the existing impl to the current Activity
     * (later calls, e.g. after rotation). See B20Registrar.
     */
    public static void register(BinaryMessenger messenger, AppCompatActivity activity) {
        B21HostApiImpl impl = IMPLS.get(messenger);
        if (impl == null) {
            impl = new B21HostApiImpl(activity);
            B21Messages.B21HostApi.setUp(messenger, impl);
            IMPLS.put(messenger, impl);
        } else {
            impl.rebind(activity);
        }
    }

    /** Drops the impl for the given messenger (engine destroy). */
    public static void unregister(BinaryMessenger messenger) {
        B21HostApiImpl old = IMPLS.remove(messenger);
        if (old != null) old.dispose();
    }

    /** Rebinds this impl to a new Activity instance (rotation). */
    public void rebind(AppCompatActivity activity) {
        this.activity = activity;
    }

    /** Shuts the worker down and releases the Activity (engine destroy). */
    public void dispose() {
        activity = null;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Forwards a classic onActivityResult to the impl bound to the
     * given engine (C1ScreenActivity calls this for every result).
     */
    public static void onActivityResult(BinaryMessenger messenger, int requestCode,
            int resultCode, @Nullable Intent data) {
        B21HostApiImpl impl = IMPLS.get(messenger);
        if (impl == null) return;
        if (requestCode == REQUEST_IMPORT) impl.handleImportResult(resultCode, data);
        else if (requestCode == REQUEST_LOCAL_CONFIG) impl.handleLocalConfigResult(resultCode, data);
    }

    /** Forwards the FileChooser directory-picker result to the impl bound to the given engine. */
    public static void onLocalSourceDirPicked(BinaryMessenger messenger, ActivityResult result) {
        B21HostApiImpl impl = IMPLS.get(messenger);
        if (impl == null) return;
        impl.handleLocalSourceDirPicked(result);
    }

    // ------------------------------------------------------------------
    // Completion (B19 shape, without the busy flag: B21 snapshots
    // carry no busy field, so nothing owns one)
    // ------------------------------------------------------------------

    private final class Completion<T> {
        private final B21Messages.Result<T> result;
        private boolean done;
        private final Runnable timeout;

        Completion(B21Messages.Result<T> result, long timeoutMs, T timeoutValue) {
            this.result = result;
            this.timeout = () -> complete(timeoutValue);
            handler.postDelayed(this.timeout, timeoutMs);
        }

        /** Must be called on the main thread (Pigeon Result). */
        void complete(T value) {
            if (done) return;
            done = true;
            handler.removeCallbacks(timeout);
            result.success(value);
        }

        boolean isDone() {
            return done;
        }
    }

    private ExecutorService executorService() {
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        return executor;
    }

    private static String nn(@Nullable String value) {
        return value == null ? "" : value;
    }

    private String timeoutText() {
        AppCompatActivity act = activity;
        return act == null ? "" : act.getString(R.string.error_play_timeout);
    }

    // ------------------------------------------------------------------
    // Native lookups (mirrors LabActivity / LabDetailActivity)
    // ------------------------------------------------------------------

    @Nullable
    private static LabModels.Item findItem(@Nullable LabModels.LabRoot root, String name) {
        if (root == null || root.lists == null) return null;
        for (LabModels.Item item : root.lists) {
            if (name.equals(item.name)) return item;
        }
        return null;
    }

    @Nullable
    private static Site findSite(String key) {
        for (Site site : LocalSource.getSites()) {
            if (key.equals(site.getKey())) return site;
        }
        return null;
    }

    /** Built-in commands in file order, then custom commands in stored order (LabCommandAdapter.setItem). */
    private static List<LabModels.Command> allCommands(LabModels.Item item) {
        List<LabModels.Command> rows = new ArrayList<>();
        if (item.commands != null) rows.addAll(item.commands);
        List<LabCustomCommands.CustomCommand> customs = LabCustomCommands.list(item.name);
        for (int i = 0; i < customs.size(); i++) {
            LabCustomCommands.CustomCommand custom = customs.get(i);
            LabModels.Command command = new LabModels.Command();
            command.id = custom.id != null ? custom.id : ("custom_" + i);
            command.name = custom.name;
            command.description = custom.description;
            command.command = custom.command;
            command.auto_execute = custom.autoExecute;
            rows.add(command);
        }
        return rows;
    }

    @Nullable
    private static LabModels.Command findCommand(LabModels.Item item, String commandId) {
        for (LabModels.Command command : allCommands(item)) {
            if (commandId.equals(command.id)) return command;
        }
        return null;
    }

    /** The CustomCommand behind a command row, if it is a custom one (LabDetailActivity.customCommand). */
    @Nullable
    private static LabCustomCommands.CustomCommand customCommand(LabModels.Item item, LabModels.Command command) {
        if (command == null || command.id == null || !command.id.startsWith("custom_")) return null;
        for (LabCustomCommands.CustomCommand custom : LabCustomCommands.list(item.name)) {
            if (command.id.equals(custom.id)) return custom;
        }
        return null;
    }

    private static boolean hasNewVersion(LabModels.Item item, String displayVersion) {
        String installed = LabConfig.get().getInstalledVersion(item.name);
        return !installed.isEmpty() && LabEnv.compareVersions(displayVersion, installed) > 0;
    }

    /** List status only looks at built-in commands (LabPackageAdapter.running). */
    private static boolean runningBuiltIn(LabModels.Item item) {
        if (item.commands == null) return false;
        for (LabModels.Command command : item.commands) {
            if (LabRunner.isRunning(item.name + "/" + command.id)) return true;
        }
        return false;
    }

    /** Detail status also counts custom commands (LabDetailActivity.anyRunning). */
    private static boolean anyRunning(LabModels.Item item) {
        if (runningBuiltIn(item)) return true;
        for (LabCustomCommands.CustomCommand custom : LabCustomCommands.list(item.name)) {
            if (custom.id != null && LabRunner.isRunning(item.name + "/" + custom.id)) return true;
        }
        return false;
    }

    /**
     * DRIFT WARNING: LabEnv.findDownload is package-private (lab
     * package), so this replicates its arch-matching loop verbatim
     * (normalizeAbi + arm64-v8a / armeabi-v7a only). If
     * LabEnv.findDownload changes, update this copy. The size text
     * is the only field that needs it.
     */
    private static String sizeText(LabModels.Item item) {
        if (item == null || item.downloads == null || item.downloads.isEmpty()) return "";
        String normalizedArch = normalizeAbi(LabEnv.arch());
        if (!"arm64-v8a".equals(normalizedArch) && !"armeabi-v7a".equals(normalizedArch)) return "";
        for (LabModels.Download download : item.downloads) {
            if (download != null && normalizedArch.equals(normalizeAbi(download.arch))) {
                return download.size == null ? "" : download.size;
            }
        }
        return "";
    }

    private static String normalizeAbi(@Nullable String abi) {
        if (TextUtils.isEmpty(abi)) return "";
        String normalized = abi.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("arm64-v8a".equals(normalized) || "arm64".equals(normalized) || "aarch64".equals(normalized)) {
            return "arm64-v8a";
        }
        if ("armeabi-v7a".equals(normalized) || "armeabi".equals(normalized)
                || "armv7".equals(normalized) || "armv7-a".equals(normalized)
                || "armv7a".equals(normalized)) {
            return "armeabi-v7a";
        }
        return normalized;
    }

    // ------------------------------------------------------------------
    // Snapshot building (mirrors LabActivity.render + LabPackageAdapter)
    // ------------------------------------------------------------------

    private List<B21Messages.B21LabGroup> buildGroups(@Nullable LabModels.LabRoot root) {
        AppCompatActivity act = activity;
        List<B21Messages.B21LabGroup> groups = new ArrayList<>();
        groups.add(new B21Messages.B21LabGroup.Builder().setId("all").setName("全部").build());
        // The local-source and miniapp groups are mobile-only, exactly
        // like native render(): TV never shows them.
        if (act != null && !Util.isLeanback()) {
            groups.add(new B21Messages.B21LabGroup.Builder()
                    .setId("__local_source__")
                    .setName(act.getString(R.string.setting_local_source))
                    .build());
        }
        if (act != null && !Util.isLeanback() && Setting.isHkMiniapp()) {
            groups.add(new B21Messages.B21LabGroup.Builder()
                    .setId("__miniapp__")
                    .setName("海阔小程序")
                    .build());
        }
        if (root != null && root.groups != null) {
            for (LabModels.Group group : root.groups) {
                groups.add(new B21Messages.B21LabGroup.Builder()
                        .setId(nn(group.id))
                        .setName(nn(group.name))
                        .build());
            }
        }
        return groups;
    }

    private B21Messages.B21LabPackage buildPackage(LabModels.Item item) {
        AppCompatActivity act = activity;
        String version = LabEnv.displayVersion(item);
        boolean installed = act != null && LabEnv.installed(act, item);
        boolean running = runningBuiltIn(item);
        boolean hasUpdate = installed && hasNewVersion(item, version);
        String statusText;
        String statusKind;
        if (!item.available) {
            statusText = "未上线";
            statusKind = "unavailable";
        } else if (running) {
            statusText = act == null ? "" : act.getString(R.string.lab_running);
            statusKind = "running";
        } else if (installed) {
            statusText = act == null ? "" : act.getString(R.string.lab_installed);
            statusKind = "installed";
        } else {
            statusText = act == null ? "" : act.getString(R.string.lab_not_installed);
            statusKind = "not_installed";
        }
        return new B21Messages.B21LabPackage.Builder()
                .setName(nn(item.name))
                .setTitleText(nn(item.name) + " " + version)
                .setInfo(nn(item.info))
                .setIconUrl(nn(item.icon))
                .setVersionText(version)
                .setSizeText(sizeText(item))
                .setStatusText(statusText)
                .setStatusKind(statusKind)
                .setHasUpdate(hasUpdate)
                .setGroupId(nn(item.group))
                .build();
    }

    private B21Messages.B21LabList buildList(String notice) {
        LabModels.LabRoot root = LabConfig.get().getLabRoot();
        List<B21Messages.B21LabPackage> packages = new ArrayList<>();
        if (root != null && root.lists != null) {
            for (LabModels.Item item : root.lists) {
                if (item.show) packages.add(buildPackage(item));
            }
        }
        List<B21Messages.B21LabSite> localSources = new ArrayList<>();
        List<B21Messages.B21LabSite> miniappSites = new ArrayList<>();
        for (Site site : LocalSource.getSites()) {
            B21Messages.B21LabSite row = new B21Messages.B21LabSite.Builder()
                    .setKey(nn(site.getKey()))
                    .setName(nn(site.getName()))
                    .build();
            if (LocalSource.isMiniapp(site)) miniappSites.add(row);
            else localSources.add(row);
        }
        return new B21Messages.B21LabList.Builder()
                .setGroups(buildGroups(root))
                .setPackages(packages)
                .setLocalSources(localSources)
                .setMiniappSites(miniappSites)
                .setLoadErrorText(nn(LabConfig.get().getLoadError()))
                .setNoticeText(notice)
                .setPickState(listPickState)
                .setPickEpoch(listPickEpoch)
                .setPickMessage(listPickMessage)
                .setIsHkMiniapp(Setting.isHkMiniapp())
                .build();
    }

    private B21Messages.B21LabList errorList(String message) {
        return new B21Messages.B21LabList.Builder()
                .setGroups(buildGroups(null))
                .setPackages(new ArrayList<>())
                .setLocalSources(new ArrayList<>())
                .setMiniappSites(new ArrayList<>())
                .setLoadErrorText(nn(message))
                .setNoticeText("")
                .setPickState(listPickState)
                .setPickEpoch(listPickEpoch)
                .setPickMessage(listPickMessage)
                .setIsHkMiniapp(Setting.isHkMiniapp())
                .build();
    }

    /** Native LabActivity.reload(): sync assets + reload config + auto-install (per-Activity attempted set). */
    private void autoInstall(@Nullable LabModels.LabRoot root) {
        AppCompatActivity act = activity;
        if (act == null || root == null || root.lists == null) return;
        for (LabModels.Item item : root.lists) {
            if (!item.auto_install || !item.available) continue;
            if (autoInstallAttempted.contains(item.name)) continue;
            autoInstallAttempted.add(item.name);
            if (LabEnv.installed(act, item)) continue;
            LabActions.install(act, item, () -> {
            });
        }
    }

    /** Runs syncVodPlusAssets + LabConfig.reload on the worker; the reload callback lands on main. */
    private void loadAndSnapshot(Completion<B21Messages.B21LabList> completion, String notice) {
        AppCompatActivity act = activity;
        if (act == null) {
            handler.post(() -> {
                if (!completion.isDone()) completion.complete(errorList(""));
            });
            return;
        }
        LabEnv.syncVodPlusAssets(act);
        LabConfig.get().reload(new LabConfig.LoadCallback() {
            @Override
            public void onLoaded(LabModels.LabRoot root) {
                if (completion.isDone()) return;
                loadedOnce = true;
                autoInstall(root);
                completion.complete(buildList(notice));
            }

            @Override
            public void onError(String message) {
                if (completion.isDone()) return;
                completion.complete(errorList(message));
            }
        });
    }

    /** Fire-and-forget config refresh (import / scan follow-up): reloads the cached root, no Result. */
    private void refreshConfigAsync() {
        AppCompatActivity act = activity;
        if (act == null) return;
        executorService().execute(() -> {
            LabEnv.syncVodPlusAssets(act);
            LabConfig.get().reload(new LabConfig.LoadCallback() {
                @Override
                public void onLoaded(LabModels.LabRoot root) {
                    loadedOnce = true;
                    autoInstall(root);
                }

                @Override
                public void onError(String message) {
                }
            });
        });
    }

    /** Native LabActivity.reloadLocalConfig: re-loads the current vod config so new sources appear on home. */
    private void reloadLocalConfig() {
        VodConfig.load(Config.vod(), new Callback() {
            @Override
            public void success() {
                ConfigEvent.vod();
            }
        });
    }

    // ------------------------------------------------------------------
    // Flag + shell
    // ------------------------------------------------------------------

    @Override
    public B21Messages.B21LabShell getLabShell() {
        AppCompatActivity act = activity;
        String empty = "";
        if (act == null) {
            return new B21Messages.B21LabShell.Builder()
                    .setTitle(empty).setAllLabel(empty).setLocalSourceLabel(empty)
                    .setMiniappLabel(empty).setSwitchGroupLabel(empty).setRefreshLabel(empty)
                    .setImportLabel(empty).setSettingsLabel(empty).setHelpLabel(empty)
                    .setScanLabel(empty).setEmptyText(empty).setLocalSourceEmptyText(empty)
                    .setMiniappEmptyText(empty).setCancelLabel(empty).setRetryLabel(empty)
                    .setConfirmLabel(empty).setImportTitle(empty).setImportHint(empty)
                    .setImportPlaceholder(empty).setImportSelectFileLabel(empty)
                    .setImportButtonLabel(empty).setDownloadLabel(empty).setUninstallLabel(empty)
                    .setCommandsTitle(empty).setNoCommandsText(empty).setAddCommandLabel(empty)
                    .setRefreshCommandsLabel(empty).setTerminalLabel(empty).setExportLabel(empty)
                    .setSettingsMenuLabel(empty).setDeleteLabel(empty)
                    .build();
        }
        return new B21Messages.B21LabShell.Builder()
                .setTitle(act.getString(R.string.lab_title))
                .setAllLabel("全部")
                .setLocalSourceLabel(act.getString(R.string.setting_local_source))
                .setMiniappLabel("海阔小程序")
                .setSwitchGroupLabel(act.getString(R.string.lab_switch_group))
                .setRefreshLabel(act.getString(R.string.lab_refresh))
                .setImportLabel(act.getString(R.string.lab_import))
                .setSettingsLabel(act.getString(R.string.nav_setting))
                .setHelpLabel(act.getString(R.string.menu_help))
                .setScanLabel(act.getString(R.string.lab_local_source_scan))
                .setEmptyText(act.getString(R.string.lab_empty_no_source))
                .setLocalSourceEmptyText(act.getString(R.string.lab_local_source_empty))
                .setMiniappEmptyText(act.getString(R.string.hk_miniapp_empty))
                .setCancelLabel(act.getString(R.string.dialog_cancel))
                .setRetryLabel(act.getString(R.string.error_retry))
                .setConfirmLabel(act.getString(R.string.dialog_confirm))
                .setImportTitle(act.getString(R.string.lab_import_title))
                .setImportHint(act.getString(R.string.lab_import_hint))
                .setImportPlaceholder(act.getString(R.string.lab_import_placeholder))
                .setImportSelectFileLabel(act.getString(R.string.lab_import_select_file))
                .setImportButtonLabel(act.getString(R.string.lab_import))
                .setDownloadLabel("下载安装")
                .setUninstallLabel("卸载")
                .setCommandsTitle("命令列表")
                .setNoCommandsText("暂无命令")
                .setAddCommandLabel(act.getString(R.string.lab_add_custom))
                .setRefreshCommandsLabel("重置命令列表")
                .setTerminalLabel(act.getString(R.string.lab_terminal))
                .setExportLabel(act.getString(R.string.lab_export_file))
                .setSettingsMenuLabel(act.getString(R.string.nav_setting))
                .setDeleteLabel(act.getString(R.string.setting_delete))
                .build();
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @Override
    public void getLabList(B21Messages.Result<B21Messages.B21LabList> result) {
        Completion<B21Messages.B21LabList> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS, errorList(timeoutText()));
        executorService().execute(() -> {
            if (!loadedOnce) {
                loadAndSnapshot(completion, "");
            } else {
                B21Messages.B21LabList snapshot = buildList("");
                handler.post(() -> {
                    if (completion.isDone()) return;
                    completion.complete(snapshot);
                });
            }
        });
    }

    @Override
    public void reloadLabList(B21Messages.Result<B21Messages.B21LabList> result) {
        Completion<B21Messages.B21LabList> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS, errorList(timeoutText()));
        executorService().execute(() -> loadAndSnapshot(completion, ""));
    }

    // ------------------------------------------------------------------
    // Lab source settings (native showSettings)
    // ------------------------------------------------------------------

    @Override
    public B21Messages.B21LabSettings getLabSettings() {
        AppCompatActivity act = activity;
        LabConfig config = LabConfig.get();
        int source = config.getSource() == LabConfig.SOURCE_URL ? 1 : 0;
        String empty = "";
        if (act == null) {
            return new B21Messages.B21LabSettings.Builder()
                    .setSource((long) source).setSourceLocalLabel(empty).setSourceUrlLabel(empty)
                    .setUrl(nn(config.getUrl())).setLocalPath(nn(config.getLocalPath()))
                    .setRootOverride(nn(config.getRootOverride()))
                    .setForeground(config.getForeground()).setBattery(config.getBattery())
                    .setGlobalProxy(config.getGlobalProxy())
                    .setProxyPort((long) config.getGlobalProxyPort())
                    .setProxyNoProxy(nn(config.getGlobalProxyNoProxy()))
                    .setTitle(empty).setSaveLabel(empty).setCancelLabel(empty)
                    .setLocalPathHint(empty).setUrlHint(empty).setRootHint(empty)
                    .setSourceHint(empty).setSourceDropdownHint(empty)
                    .setForegroundTitle(empty).setForegroundHint(empty)
                    .setBatteryTitle(empty).setBatteryHint(empty)
                    .setProxyTitle(empty).setProxyHint(empty)
                    .setProxyPortHint(empty).setProxyNoProxyHint(empty)
                    .setPickState(settingsPickState).setPickEpoch(settingsPickEpoch)
                    .setPickMessage(settingsPickMessage)
                    .build();
        }
        return new B21Messages.B21LabSettings.Builder()
                .setSource((long) source)
                .setSourceLocalLabel(act.getString(R.string.lab_source_local))
                .setSourceUrlLabel(act.getString(R.string.lab_source_url))
                .setUrl(nn(config.getUrl()))
                .setLocalPath(nn(config.getLocalPath()))
                .setRootOverride(nn(config.getRootOverride()))
                .setForeground(config.getForeground())
                .setBattery(config.getBattery())
                .setGlobalProxy(config.getGlobalProxy())
                .setProxyPort((long) config.getGlobalProxyPort())
                .setProxyNoProxy(nn(config.getGlobalProxyNoProxy()))
                .setTitle(act.getString(R.string.lab_source_title))
                .setSaveLabel(act.getString(android.R.string.ok))
                .setCancelLabel(act.getString(R.string.dialog_cancel))
                .setLocalPathHint(act.getString(R.string.lab_source_placeholder))
                .setUrlHint(act.getString(R.string.lab_source_placeholder))
                .setRootHint("根目录（留空跟随配置）")
                .setSourceHint(act.getString(R.string.lab_source_hint))
                .setSourceDropdownHint(act.getString(R.string.lab_source_switch))
                .setForegroundTitle(act.getString(R.string.lab_keepalive_foreground_title))
                .setForegroundHint(act.getString(R.string.lab_keepalive_foreground_hint))
                .setBatteryTitle(act.getString(R.string.lab_keepalive_battery_title))
                .setBatteryHint(act.getString(R.string.lab_keepalive_battery_hint))
                .setProxyTitle(act.getString(R.string.lab_global_proxy_title))
                .setProxyHint(act.getString(R.string.lab_global_proxy_hint))
                .setProxyPortHint(act.getString(R.string.lab_global_proxy_port))
                .setProxyNoProxyHint(act.getString(R.string.lab_global_proxy_no_proxy))
                .setPickState(settingsPickState)
                .setPickEpoch(settingsPickEpoch)
                .setPickMessage(settingsPickMessage)
                .build();
    }

    @Override
    public void saveLabSettings(B21Messages.B21LabSettingsInput input,
            B21Messages.Result<B21Messages.B21LabList> result) {
        Completion<B21Messages.B21LabList> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS, errorList(timeoutText()));
        executorService().execute(() -> {
            AppCompatActivity act = activity;
            if (act == null) {
                handler.post(() -> {
                    if (!completion.isDone()) completion.complete(errorList(""));
                });
                return;
            }
            LabConfig config = LabConfig.get();
            long source = input.getSource() == null ? 0L : input.getSource();
            int selected = source == 1L ? LabConfig.SOURCE_URL : LabConfig.SOURCE_LOCAL;
            config.setSource(selected);
            if (selected == LabConfig.SOURCE_LOCAL) {
                String localPath = nn(input.getLocalPath());
                if (localPath.isEmpty()) {
                    handler.post(() -> Notify.show("未选择文件，将自动搜索手机常见目录"));
                } else {
                    config.setLocalPath(localPath);
                }
            }
            if (selected == LabConfig.SOURCE_URL) {
                String url = nn(input.getUrl()).trim();
                if (!url.isEmpty()) config.setUrl(url);
            }
            config.setRoot(nn(input.getRootOverride()).trim());
            boolean foreground = Boolean.TRUE.equals(input.getForeground());
            boolean battery = Boolean.TRUE.equals(input.getBattery());
            config.setForeground(foreground);
            config.setBattery(battery);
            config.setGlobalProxy(Boolean.TRUE.equals(input.getGlobalProxy()));
            long port = input.getProxyPort() == null ? 7890L : input.getProxyPort();
            config.setGlobalProxyPort(port > 0 ? (int) port : 7890);
            config.setGlobalProxyNoProxy(nn(input.getProxyNoProxy()).trim());
            handler.post(() -> {
                if (foreground) requestNotifyPermission(act);
                if (battery) {
                    try {
                        act.startActivity(new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + act.getPackageName())));
                    } catch (Exception ignored) {
                    }
                }
                LabProcManager.updateService();
            });
            loadAndSnapshot(completion, "");
        });
    }

    /** Native LabActivity.requestNotifyPermission (the result path's updateService is covered by saveLabSettings itself). */
    private static void requestNotifyPermission(Activity act) {
        if (Build.VERSION.SDK_INT >= 33
                && act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    // ------------------------------------------------------------------
    // About + import
    // ------------------------------------------------------------------

    @Override
    public void openLabAbout() {
        AppCompatActivity act = activity;
        if (act == null) return;
        handler.post(() -> new WebHtvAlertDialogBuilder(act, R.style.Theme_App_Lab_DayNight_Dialog)
                .setTitle(R.string.lab_about_title)
                .setMessage(R.string.lab_about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    @Override
    public B21Messages.B21LabImportDecode decodeLabImport(String text) {
        String input = text == null ? "" : text.trim();
        if (input.isEmpty()) {
            return new B21Messages.B21LabImportDecode.Builder()
                    .setJson("").setErrorText("无效的导入数据").build();
        }
        if (input.startsWith("VodPlusLabConfig://")) {
            try {
                String json = new String(Base64.decode(
                        input.substring("VodPlusLabConfig://".length()), Base64.DEFAULT),
                        StandardCharsets.UTF_8);
                return new B21Messages.B21LabImportDecode.Builder()
                        .setJson(json).setErrorText("").build();
            } catch (Exception e) {
                return new B21Messages.B21LabImportDecode.Builder()
                        .setJson("").setErrorText("无效的导入数据").build();
            }
        }
        return new B21Messages.B21LabImportDecode.Builder()
                .setJson(input).setErrorText("").build();
    }

    @Override
    public void importLabPackage(String json, B21Messages.Result<B21Messages.B21LabList> result) {
        Completion<B21Messages.B21LabList> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS, errorList(timeoutText()));
        executorService().execute(() -> {
            AppCompatActivity act = activity;
            if (act == null) {
                handler.post(() -> {
                    if (!completion.isDone()) completion.complete(errorList(""));
                });
                return;
            }
            LabConfig.get().saveImported(nn(json));
            loadAndSnapshot(completion, act.getString(R.string.lab_import_success));
        });
    }

    @Override
    public void requestLabImportFile() {
        AppCompatActivity act = activity;
        if (act == null) return;
        listPickState = "picking";
        PermissionUtil.requestFile(act, granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.setting_custom_csp_permission_required);
                listPickState = "idle";
                listPickEpoch++;
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                act.startActivityForResult(intent, REQUEST_IMPORT);
            } catch (Exception e) {
                Notify.show("无法打开文件选择器: " + e.getMessage());
                listPickState = "idle";
                listPickEpoch++;
            }
        });
    }

    /** Native LabActivity.importArchive, minus the Activity (the result arrives through C1ScreenActivity). */
    private void handleImportResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            // Cancelled: silent, like native (no pickMessage), but the
            // epoch still moves so Dart's pick poll stops promptly.
            listPickState = "idle";
            listPickEpoch++;
            return;
        }
        listPickState = "importing";
        Uri uri = data.getData();
        handler.post(() -> Notify.show("导入中…"));
        executorService().execute(() -> {
            AppCompatActivity act = activity;
            if (act == null) {
                listPickState = "idle";
                listPickEpoch++;
                return;
            }
            try {
                File cache = new File(act.getCacheDir(), "import_" + System.currentTimeMillis() + ".lab.7z");
                try (InputStream in = act.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cache)) {
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }
                boolean ok = LabEnv.importPackage(act, cache);
                cache.delete();
                listPickMessage = ok ? act.getString(R.string.lab_import_success)
                        : act.getString(R.string.lab_import_error_short);
                listPickState = "idle";
                listPickEpoch++;
                refreshConfigAsync();
            } catch (Exception e) {
                listPickMessage = "导入失败: " + e.getMessage();
                listPickState = "idle";
                listPickEpoch++;
            }
        });
    }

    @Override
    public void requestLabLocalConfigFile() {
        AppCompatActivity act = activity;
        if (act == null) return;
        settingsPickState = "picking";
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            act.startActivityForResult(intent, REQUEST_LOCAL_CONFIG);
        } catch (Exception e) {
            Notify.show("无法打开文件选择器: " + e.getMessage());
            settingsPickState = "idle";
            settingsPickEpoch++;
        }
    }

    /** Native LabActivity.onActivityResult(REQUEST_LOCAL_CONFIG), minus the dialog dismiss (the dialog is Dart-side). */
    private void handleLocalConfigResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            settingsPickState = "idle";
            settingsPickEpoch++;
            return;
        }
        Uri uri = data.getData();
        AppCompatActivity act = activity;
        if (act == null) {
            settingsPickState = "idle";
            settingsPickEpoch++;
            return;
        }
        try {
            act.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            LabConfig.get().setLocalPath(uri.toString());
        } catch (Exception e) {
            try {
                File local = new File(act.getFilesDir(), "lab_local_config.json");
                try (InputStream in = act.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(local)) {
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }
                LabConfig.get().setLocalPath(local.getAbsolutePath());
            } catch (Exception ignored) {
                Notify.show("无法读取所选文件");
                settingsPickState = "idle";
                settingsPickEpoch++;
                return;
            }
        }
        LabConfig.get().setSource(LabConfig.SOURCE_LOCAL);
        settingsPickMessage = LabConfig.get().getLocalPath();
        settingsPickState = "idle";
        settingsPickEpoch++;
        Notify.show("已选择本地配置");
        refreshConfigAsync();
    }

    // ------------------------------------------------------------------
    // Local sources
    // ------------------------------------------------------------------

    @Override
    public void pickLabLocalSourceDir() {
        AppCompatActivity act = activity;
        if (act == null) return;
        listPickState = "picking";
        if (act instanceof C1ScreenActivity) {
            ((C1ScreenActivity) act).launchLabLocalSourceDir();
        } else {
            listPickState = "idle";
            listPickEpoch++;
        }
    }

    /** Native LabActivity localSourceLauncher result + scanLocalDir. */
    private void handleLocalSourceDirPicked(ActivityResult result) {
        if (result.getData() == null || result.getData().getData() == null) {
            listPickState = "idle";
            listPickEpoch++;
            return;
        }
        String path = FileChooser.getPersistentPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) {
            listPickState = "idle";
            listPickEpoch++;
            return;
        }
        LocalSource.setScanDir(path);
        listPickState = "scanning";
        executorService().execute(() -> {
            int count = LocalSource.scan(new File(path));
            handler.post(() -> {
                AppCompatActivity act = activity;
                LocalSource.invalidate();
                boolean skippedLarge = LocalSource.takeSkippedLargeDir();
                int pruned = LocalSource.takePrunedCount();
                if (act != null) {
                    if (skippedLarge) {
                        listPickMessage = act.getString(R.string.lab_local_source_too_many_files);
                    } else {
                        listPickMessage = act.getString(R.string.lab_local_source_imported, count);
                    }
                    if (pruned > 0) {
                        listPickMessage = listPickMessage + "\n"
                                + act.getString(R.string.lab_local_source_pruned, pruned);
                    }
                }
                listPickState = "idle";
                listPickEpoch++;
                if (count > 0 || pruned > 0) reloadLocalConfig();
            });
        });
    }

    @Override
    public void setLabLocalSourceHome(String key) {
        AppCompatActivity act = activity;
        if (act == null) return;
        Site site = findSite(nn(key));
        if (site == null) return;
        VodConfig.get().setHome(site);
        ConfigEvent.vod();
        act.finish();
    }

    @Override
    public void deleteLabLocalSource(String key, B21Messages.Result<B21Messages.B21LabList> result) {
        Completion<B21Messages.B21LabList> completion =
                new Completion<>(result, SNAPSHOT_TIMEOUT_MS, errorList(timeoutText()));
        executorService().execute(() -> {
            LocalSource.removeByKeys(List.of(nn(key)));
            handler.post(() -> {
                reloadLocalConfig();
                if (completion.isDone()) return;
                AppCompatActivity act = activity;
                String notice = act == null ? ""
                        : act.getString(R.string.lab_local_source_deleted, 1);
                completion.complete(buildList(notice));
            });
        });
    }

    // ------------------------------------------------------------------
    // Miniapps (embedded list + manager entry)
    // ------------------------------------------------------------------

    @Override
    public void openLabMiniappList(String key) {
        AppCompatActivity act = activity;
        if (act == null) return;
        Site site = findSite(nn(key));
        if (site == null) return;
        Intent intent = new Intent(App.get().getPackageName() + ".hk_list");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.putExtra("title", LocalSource.miniappTitle(site));
        if (intent.resolveActivity(act.getPackageManager()) != null) act.startActivity(intent);
        else Notify.show(R.string.hk_miniapp_list_pending);
    }

    @Override
    public void deleteLabMiniapp(String key, B21Messages.Result<B21Messages.B21LabList> result) {
        Completion<B21Messages.B21LabList> completion =
                new Completion<>(result, SNAPSHOT_TIMEOUT_MS, errorList(timeoutText()));
        executorService().execute(() -> {
            LocalSource.removeByKeys(List.of(nn(key)));
            handler.post(() -> {
                reloadLocalConfig();
                if (completion.isDone()) return;
                AppCompatActivity act = activity;
                String notice = act == null ? ""
                        : act.getString(R.string.lab_local_source_deleted, 1);
                completion.complete(buildList(notice));
            });
        });
    }

    @Override
    public void openLabMiniappManager() {
        AppCompatActivity act = activity;
        if (act == null) return;
        // Native LabActivity.openMiniappManager (B20): on mobile, the
        // Flutter /hk-manager page — from this Flutter page that is
        // start-then-finish (B15/B19 precedent, same G2 cross-screen
        // race); on TV the ".hk_miniapp" implicit intent stacks on top
        // and returns here.
        if (Util.isMobile()) {
            C1ScreenActivity.start(act, C1ScreenActivity.ROUTE_HK_MANAGER);
            act.finish();
            return;
        }
        Intent intent = new Intent(App.get().getPackageName() + ".hk_miniapp");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (intent.resolveActivity(act.getPackageManager()) != null) act.startActivity(intent);
    }

    @Override
    public void openLabLocalSourceDialog() {
        AppCompatActivity act = activity;
        if (act == null) return;
        Intent intent = new Intent(App.get().getPackageName() + ".local_source_dialog");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (intent.resolveActivity(act.getPackageManager()) != null) act.startActivity(intent);
    }

    // ------------------------------------------------------------------
    // Detail
    // ------------------------------------------------------------------

    private B21Messages.B21LabDetail detailNotFound(String name, String loadErrorText, String noticeText) {
        return new B21Messages.B21LabDetail.Builder()
                .setFound(false)
                .setName(name)
                .setTitle(name)
                .setVersionText("")
                .setInfo("")
                .setIconUrl("")
                .setInstalled(false)
                .setRunning(false)
                .setHasUpdate(false)
                .setDownloadLabel("下载安装")
                .setDownloadVisible(false)
                .setUninstallVisible(false)
                .setStatusText("")
                .setStatusKind("not_installed")
                .setHasSettings(false)
                .setCommands(new ArrayList<>())
                .setLoadErrorText(loadErrorText)
                .setNoticeText(noticeText)
                .build();
    }

    private B21Messages.B21LabDetailState notFoundState() {
        return new B21Messages.B21LabDetailState.Builder()
                .setFound(false)
                .setInstalled(false)
                .setRunning(false)
                .setHasUpdate(false)
                .setDownloadLabel("下载安装")
                .setDownloadVisible(false)
                .setUninstallVisible(false)
                .setStatusText("")
                .setStatusKind("not_installed")
                .setCommandStates(new ArrayList<>())
                .build();
    }

    private B21Messages.B21LabCommand buildCommand(LabModels.Item item, LabModels.Command command) {
        AppCompatActivity act = activity;
        boolean running = LabRunner.isRunning(item.name + "/" + command.id);
        boolean supported = act != null && command.isSupported(LabEnv.appVersionCode(act));
        return new B21Messages.B21LabCommand.Builder()
                .setId(nn(command.id))
                .setDisplayName(command.name == null ? nn(command.description) : command.name)
                .setDescription(nn(command.description))
                .setRunning(running)
                .setActionLabel(act == null ? ""
                        : act.getString(running ? R.string.lab_stop : R.string.lab_run))
                .setSupported(supported)
                .setMinVersion((long) command.min_version)
                .setIsCustom(command.id != null && command.id.startsWith("custom_"))
                .build();
    }

    private B21Messages.B21LabCommandState commandState(LabModels.Item item, LabModels.Command command) {
        AppCompatActivity act = activity;
        boolean running = LabRunner.isRunning(item.name + "/" + command.id);
        return new B21Messages.B21LabCommandState.Builder()
                .setId(nn(command.id))
                .setRunning(running)
                .setActionLabel(act == null ? ""
                        : act.getString(running ? R.string.lab_stop : R.string.lab_run))
                .build();
    }

    private B21Messages.B21LabDetail buildDetail(LabModels.Item item, String notice) {
        AppCompatActivity act = activity;
        String version = LabEnv.displayVersion(item);
        boolean installed = act != null && LabEnv.installed(act, item);
        boolean running = anyRunning(item);
        boolean hasUpdate = installed && hasNewVersion(item, version);
        List<B21Messages.B21LabCommand> commands = new ArrayList<>();
        for (LabModels.Command command : allCommands(item)) commands.add(buildCommand(item, command));
        String statusText;
        String statusKind;
        if (running) {
            statusText = act == null ? "" : act.getString(R.string.lab_running);
            statusKind = "running";
        } else if (installed) {
            statusText = act == null ? "" : act.getString(R.string.lab_installed);
            statusKind = "installed";
        } else {
            statusText = act == null ? "" : act.getString(R.string.lab_not_installed);
            statusKind = "not_installed";
        }
        return new B21Messages.B21LabDetail.Builder()
                .setFound(true)
                .setName(nn(item.name))
                .setTitle(nn(item.name))
                .setVersionText(nn(item.name) + " " + version)
                .setInfo(nn(item.info))
                .setIconUrl(nn(item.icon))
                .setInstalled(installed)
                .setRunning(running)
                .setHasUpdate(hasUpdate)
                .setDownloadLabel(hasUpdate ? "更新" : "下载安装")
                .setDownloadVisible(!installed || hasUpdate)
                .setUninstallVisible(installed)
                .setStatusText(statusText)
                .setStatusKind(statusKind)
                .setHasSettings(item.hasSettings())
                .setCommands(commands)
                .setLoadErrorText("")
                .setNoticeText(notice)
                .build();
    }

    @Override
    public void getLabDetail(String name, B21Messages.Result<B21Messages.B21LabDetail> result) {
        Completion<B21Messages.B21LabDetail> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS,
                        detailNotFound(nn(name), timeoutText(), timeoutText()));
        executorService().execute(() -> {
            AppCompatActivity act = activity;
            if (act == null) {
                handler.post(() -> {
                    if (!completion.isDone()) {
                        completion.complete(detailNotFound(nn(name), "", ""));
                    }
                });
                return;
            }
            LabEnv.syncVodPlusAssets(act);
            LabConfig.get().reload(new LabConfig.LoadCallback() {
                @Override
                public void onLoaded(LabModels.LabRoot root) {
                    if (completion.isDone()) return;
                    loadedOnce = true;
                    LabModels.Item item = findItem(root, nn(name));
                    if (item == null) {
                        completion.complete(detailNotFound(nn(name), "", "找不到模块: " + nn(name)));
                        return;
                    }
                    LabConfig.get().applyCommandOverrides(item);
                    completion.complete(buildDetail(item, ""));
                }

                @Override
                public void onError(String message) {
                    if (completion.isDone()) return;
                    completion.complete(detailNotFound(nn(name), message, "配置加载失败: " + message));
                }
            });
        });
    }

    @Override
    public B21Messages.B21LabDetailState pollLabDetail(String name) {
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) return notFoundState();
        AppCompatActivity act = activity;
        String version = LabEnv.displayVersion(item);
        boolean installed = act != null && LabEnv.installed(act, item);
        boolean running = anyRunning(item);
        boolean hasUpdate = installed && hasNewVersion(item, version);
        List<B21Messages.B21LabCommandState> states = new ArrayList<>();
        for (LabModels.Command command : allCommands(item)) states.add(commandState(item, command));
        String statusText;
        String statusKind;
        if (running) {
            statusText = act == null ? "" : act.getString(R.string.lab_running);
            statusKind = "running";
        } else if (installed) {
            statusText = act == null ? "" : act.getString(R.string.lab_installed);
            statusKind = "installed";
        } else {
            statusText = act == null ? "" : act.getString(R.string.lab_not_installed);
            statusKind = "not_installed";
        }
        return new B21Messages.B21LabDetailState.Builder()
                .setFound(true)
                .setInstalled(installed)
                .setRunning(running)
                .setHasUpdate(hasUpdate)
                .setDownloadLabel(hasUpdate ? "更新" : "下载安装")
                .setDownloadVisible(!installed || hasUpdate)
                .setUninstallVisible(installed)
                .setStatusText(statusText)
                .setStatusKind(statusKind)
                .setCommandStates(states)
                .build();
    }

    // ------------------------------------------------------------------
    // Detail actions (native LabDetailActivity, step for step)
    // ------------------------------------------------------------------

    @Override
    public void downloadLabItem(String name) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) {
            Toast.makeText(act, "找不到模块: " + nn(name), Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(() -> {
            if (!item.available) {
                Toast.makeText(act, "该包暂未上线", Toast.LENGTH_SHORT).show();
                return;
            }
            String version = LabEnv.displayVersion(item);
            if (LabEnv.installed(act, item) && hasNewVersion(item, version)) {
                new WebHtvAlertDialogBuilder(act, R.style.Theme_App_Lab_Dialog)
                        .setTitle("确认更新")
                        .setMessage("发现新版本 " + version + "，是否立即更新？")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("更新", (d, w) -> LabActions.installWithDialog(act, item, () -> {
                        }))
                        .show();
                return;
            }
            LabActions.installWithDialog(act, item, () -> {
            });
        });
    }

    @Override
    public void uninstallLabItem(String name) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) {
            Toast.makeText(act, "找不到模块: " + nn(name), Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(() -> new WebHtvAlertDialogBuilder(act, R.style.Theme_App_Lab_Dialog)
                .setTitle("确认卸载")
                .setMessage("确定要卸载 " + item.name + " 吗？这将停止所有运行中的命令。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("卸载", (d, w) -> LabActions.uninstall(act, item, () -> {
                }))
                .show());
    }

    @Override
    public void openLabTerminal(String name) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabTerminalActivity.start(act, nn(name), nn(name));
    }

    @Override
    public void addLabCommand(String name) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) return;
        handler.post(() -> LabCommandEditDialog.show(act, item, null, () -> {
        }));
    }

    @Override
    public void editLabCommand(String name, String commandId) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) return;
        LabModels.Command command = findCommand(item, nn(commandId));
        if (command == null) return;
        handler.post(() -> LabCommandEditDialog.show(act, item, command, () -> {
        }));
    }

    @Override
    public void openLabCommandSheet(String name, String commandId) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) return;
        LabModels.Command command = findCommand(item, nn(commandId));
        if (command == null) return;
        handler.post(() -> {
            LabCustomCommands.CustomCommand custom = customCommand(item, command);
            if (custom != null) {
                LabActions.runCustom(act, item, custom, () -> {
                });
                return;
            }
            if (!command.isSupported(LabEnv.appVersionCode(act))) {
                Toast.makeText(act, "需要更新影视+至 v" + command.min_version + " 以上",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            new LabCommandSheet(act, item, command, () -> {
            }).show();
        });
    }

    @Override
    public void toggleLabCommand(String name, String commandId) {
        AppCompatActivity act = activity;
        if (act == null) return;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        if (item == null) return;
        LabModels.Command command = findCommand(item, nn(commandId));
        if (command == null) return;
        handler.post(() -> {
            String key = item.name + "/" + command.id;
            if (LabRunner.isRunning(key)) {
                LabRunner.stop(key);
                Notify.show(R.string.lab_command_stopped);
                return;
            }
            LabCustomCommands.CustomCommand custom = customCommand(item, command);
            if (custom != null) {
                LabActions.runCustom(act, item, custom, () -> {
                });
                return;
            }
            if (!command.isSupported(LabEnv.appVersionCode(act))) {
                Toast.makeText(act, "需要更新影视+至 v" + command.min_version + " 以上",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            LabCommandSheet sheet = new LabCommandSheet(act, item, command, () -> {
            });
            sheet.show();
            sheet.run();
        });
    }

    @Override
    public void refreshLabCommands(String name, B21Messages.Result<B21Messages.B21LabDetail> result) {
        Completion<B21Messages.B21LabDetail> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS,
                        detailNotFound(nn(name), timeoutText(), timeoutText()));
        AppCompatActivity act = activity;
        if (act == null) {
            handler.post(() -> {
                if (!completion.isDone()) {
                    completion.complete(detailNotFound(nn(name), "", ""));
                }
            });
            return;
        }
        handler.post(() -> {
            LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
            if (item == null) {
                if (!completion.isDone()) {
                    completion.complete(detailNotFound(nn(name), "", "找不到模块: " + nn(name)));
                }
                return;
            }
            new WebHtvAlertDialogBuilder(act, R.style.Theme_App_Lab_Dialog)
                    .setTitle("重置命令列表")
                    .setMessage("确定要从远程重新加载命令列表吗？这将覆盖本地修改的命令，并停止所有运行中的进程。")
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        if (completion.isDone()) return;
                        completion.complete(buildDetail(item, ""));
                    })
                    .setPositiveButton("确定", (d, w) -> {
                        if (completion.isDone()) return;
                        LabActions.stopItem(item);
                        LabConfig.get().clearCommandCache(item.name);
                        LabConfig.get().clearCommandOverrides(item.name);
                        executorService().execute(() -> {
                            LabEnv.syncVodPlusAssets(act);
                            LabConfig.get().reload(new LabConfig.LoadCallback() {
                                @Override
                                public void onLoaded(LabModels.LabRoot root) {
                                    if (completion.isDone()) return;
                                    loadedOnce = true;
                                    LabModels.Item fresh = findItem(root, nn(name));
                                    if (fresh == null) {
                                        completion.complete(detailNotFound(nn(name), "",
                                                "找不到模块: " + nn(name)));
                                        return;
                                    }
                                    LabConfig.get().applyCommandOverrides(fresh);
                                    completion.complete(buildDetail(fresh,
                                            act.getString(R.string.lab_refresh_success)));
                                }

                                @Override
                                public void onError(String message) {
                                    if (completion.isDone()) return;
                                    completion.complete(detailNotFound(nn(name), message,
                                            "配置加载失败: " + message));
                                }
                            });
                        });
                    })
                    .setOnCancelListener(d -> {
                        if (completion.isDone()) return;
                        completion.complete(buildDetail(item, ""));
                    })
                    .show();
        });
    }

    // ------------------------------------------------------------------
    // Package settings form (native LabDetailActivity.showPackageSettings)
    // ------------------------------------------------------------------

    @Override
    public B21Messages.B21LabSettingsForm getLabSettingsForm(String name) {
        AppCompatActivity act = activity;
        LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
        B21Messages.B21LabSettingsForm.Builder builder = new B21Messages.B21LabSettingsForm.Builder()
                .setName(nn(name))
                .setTitle(nn(name) + " 设置")
                .setSaveLabel("保存")
                .setCancelLabel(act == null ? "" : act.getString(R.string.dialog_cancel))
                .setPickState(formPickState)
                .setPickEpoch(formPickEpoch)
                .setPickValue(formPickValue);
        List<B21Messages.B21LabSettingRow> rows = new ArrayList<>();
        if (item != null && item.settings != null) {
            Map<String, String> values = LabConfig.get().loadUserSettings(item.name);
            for (LabModels.Setting setting : item.settings) {
                String type = setting.type == null ? "text" : setting.type;
                String value = values.containsKey(setting.key)
                        ? values.get(setting.key) : setting.defaultValue;
                if (value == null) value = "";
                List<B21Messages.B21LabSettingOption> options = new ArrayList<>();
                if (setting.options != null) {
                    for (LabModels.Option option : setting.options) {
                        options.add(new B21Messages.B21LabSettingOption.Builder()
                                .setLabel(nn(option.label))
                                .setValue(nn(option.value))
                                .build());
                    }
                }
                rows.add(new B21Messages.B21LabSettingRow.Builder()
                        .setKey(nn(setting.key))
                        .setName(nn(setting.name))
                        .setType(type)
                        .setValue(value)
                        .setHint(nn(setting.hint))
                        .setOptions(options)
                        .build());
            }
        }
        return builder.setRows(rows).build();
    }

    @Override
    public void saveLabSettingsForm(String name, List<B21Messages.B21LabSettingValue> values,
            B21Messages.Result<B21Messages.B21LabDetail> result) {
        Completion<B21Messages.B21LabDetail> completion =
                new Completion<>(result, SNAPSHOT_TIMEOUT_MS,
                        detailNotFound(nn(name), timeoutText(), timeoutText()));
        executorService().execute(() -> {
            Map<String, String> map = new HashMap<>();
            if (values != null) {
                for (B21Messages.B21LabSettingValue value : values) {
                    map.put(nn(value.getKey()), nn(value.getValue()));
                }
            }
            LabConfig.get().saveUserSettings(nn(name), map);
            handler.post(() -> {
                if (completion.isDone()) return;
                LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
                if (item == null) {
                    completion.complete(detailNotFound(nn(name), "", "找不到模块: " + nn(name)));
                    return;
                }
                completion.complete(buildDetail(item, "设置已保存"));
            });
        });
    }

    @Override
    public void requestLabSettingDirectory() {
        AppCompatActivity act = activity;
        if (act == null) return;
        formPickState = "picking";
        LabFilePicker.pick(act, true, path -> {
            formPickValue = path == null ? "" : path;
            formPickState = "idle";
            formPickEpoch++;
        });
    }

    // ------------------------------------------------------------------
    // Export + dispose
    // ------------------------------------------------------------------

    @Override
    public void exportLabPackage(String name) {
        AppCompatActivity act = activity;
        if (act == null) return;
        handler.post(() -> {
            LabModels.Item item = findItem(LabConfig.get().getLabRoot(), nn(name));
            if (item == null) {
                Toast.makeText(act, "找不到模块: " + nn(name), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!LabEnv.installed(act, item)) {
                Toast.makeText(act, "该包尚未安装", Toast.LENGTH_SHORT).show();
                return;
            }
            TextView progress = new TextView(act);
            progress.setTextColor(Color.WHITE);
            progress.setTextSize(14);
            progress.setPadding(48, 32, 48, 32);
            AlertDialog dialog = new WebHtvAlertDialogBuilder(act, R.style.Theme_App_Lab_Dialog)
                    .setTitle(act.getString(R.string.lab_export_file))
                    .setView(progress)
                    .setCancelable(false)
                    .setPositiveButton("关闭", null)
                    .create();
            dialog.show();
            hideCloseButton(dialog);
            executorService().execute(() -> {
                try {
                    File file = LabEnv.exportPackage(act, item, LabCustomCommands.list(item.name));
                    App.post(() -> {
                        dialog.dismiss();
                        Notify.show("已导出到: " + file.getAbsolutePath());
                    });
                } catch (Exception e) {
                    App.post(() -> {
                        progress.setText("导出失败：\n" + e.getMessage());
                        showCloseButton(dialog);
                    });
                }
            });
        });
    }

    /** LabActions.hideCloseButton is package-private (lab); its two lines are replicated here. */
    private static void hideCloseButton(AlertDialog dialog) {
        android.widget.Button close = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (close != null) close.setVisibility(View.GONE);
    }

    /** LabActions.showCloseButton is package-private (lab); its three lines are replicated here. */
    private static void showCloseButton(AlertDialog dialog) {
        dialog.setCancelable(true);
        android.widget.Button close = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (close != null) close.setVisibility(View.VISIBLE);
    }

    @Override
    public void disposeLab() {
        loadedOnce = false;
        listPickState = "idle";
        listPickMessage = "";
        settingsPickState = "idle";
        settingsPickMessage = "";
        formPickState = "idle";
        formPickValue = "";
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
