package com.github.moyingshi.osc.tv.flutter;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.Updater;
import com.github.moyingshi.osc.tv.api.config.LiveConfig;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.api.config.WallConfig;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.Live;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.cache.CacheCenter;
import com.github.moyingshi.osc.tv.cache.CachePolicyStore;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.download.DownloadPathUtil;
import com.github.moyingshi.osc.tv.download.DownloadTask;
import com.github.moyingshi.osc.tv.download.VideoDownloadManager;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.gitcloud.GitCloudAccountStore;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.impl.ConfigListener;
import com.github.moyingshi.osc.tv.impl.LiveListener;
import com.github.moyingshi.osc.tv.impl.SiteListener;
import com.github.moyingshi.osc.tv.setting.ConfigSyncPolicy;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.setting.SiteHealthStore;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.lab.LabActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.dialog.AboutDialog;
import com.github.moyingshi.osc.tv.ui.dialog.BackupProgressDialog;
import com.github.moyingshi.osc.tv.ui.dialog.CacheManagementDialog;
import com.github.moyingshi.osc.tv.ui.dialog.ChoiceDialog;
import com.github.moyingshi.osc.tv.ui.dialog.GitCloudDialog;
import com.github.moyingshi.osc.tv.ui.dialog.OneKeySyncDialog;
import com.github.moyingshi.osc.tv.ui.dialog.SiteHealthDialog;
import com.github.moyingshi.osc.tv.utils.AppVersion;
import com.github.moyingshi.osc.tv.utils.FileUtil;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.Util;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B8 B7: host side of the mobile settings home (native
 * SettingFragment, 1012 lines), now the {@code /settings} family home.
 *
 * <p>Every display string and computed value is resolved here; Dart only
 * renders the snapshot. The native SettingFragment is untouched and stays
 * the flag-off path. Row keys are the same literals as
 * SettingFragment.ROW_* (that class is mobile-only, so the literals are
 * inlined here, not referenced). See docs/b8-b7-settings-home.md.
 *
 * <p>Dialogs: HistoryDialog / SiteDialog / LiveDialog /
 * AppearanceDialog / LocalSourceDialog / RestoreDialog are mobile-only,
 * so they are opened via reflection with the headless
 * {@link B7HomeHostFragment} as their parent (the B3
 * DanmakuApiHostFragment pattern). Dialogs that report a change
 * complete their {@code @async} call in that callback; dialogs with no
 * change callback (Appearance, LocalSource, CacheManagement) complete
 * when the dialog fragment is destroyed, via a FragmentManager
 * lifecycle watcher (see {@link #watchDialogDismiss}). Cancelling a
 * dialog completes nothing (B3 V1): Dart only attaches a {@code .then}
 * refetch and never awaits.
 */
public class B7HostApiImpl implements B7Messages.B7HostApi {

    private static final String TAG_HOME_HOST = "b8_b7_home_host";
    private static final String FRAGMENT_SETTING = "com.github.moyingshi.osc.tv.ui.fragment.SettingFragment";
    private static final String DIALOG_HISTORY = "com.github.moyingshi.osc.tv.ui.dialog.HistoryDialog";
    private static final String DIALOG_SITE = "com.github.moyingshi.osc.tv.ui.dialog.SiteDialog";
    private static final String DIALOG_LIVE = "com.github.moyingshi.osc.tv.ui.dialog.LiveDialog";
    private static final String DIALOG_APPEARANCE = "com.github.moyingshi.osc.tv.ui.dialog.AppearanceDialog";
    private static final String DIALOG_LOCAL_SOURCE = "com.github.moyingshi.osc.tv.ui.dialog.LocalSourceDialog";
    private static final String DIALOG_RESTORE = "com.github.moyingshi.osc.tv.ui.dialog.RestoreDialog";

    private final FragmentActivity activity;

    /** Pending @async result for the dialog currently open, if any. */
    private B7Messages.VoidResult pendingResult;
    private boolean[] pendingCompleted;

    /** Cache text (mirrors SettingFragment.setCacheText). */
    private String lastCacheText = "";
    private boolean cacheStale = true;
    private boolean cacheComputing;
    private long lastCacheComputeStartMs;

    /** Pending settings-search deep-link row, consumed once. */
    private boolean pendingRowConsumed;
    private String pendingRowId = "";

    public B7HostApiImpl(FragmentActivity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------ flag

    @Override
    public Boolean isB7Enabled() {
        return Setting.isFlutterB7Enabled();
    }

    @Override
    public void setB7Enabled(Boolean enabled) {
        Setting.putFlutterB7Enabled(enabled != null && enabled);
    }

    /** B8 master switch: writes the single {@code flutter_enabled} key (see docs/b8-master-switch.md). */
    @Override
    public void setFlutterEnabled(Boolean enabled) {
        Setting.putFlutterEnabled(enabled != null && enabled);
    }

    // ------------------------------------------------------------------ snapshot

    @Override
    public B7Messages.B7SettingsHome getSettingsHome() {
        consumePendingRow();
        maybeComputeCacheText();
        List<B7Messages.B7SettingsGroup> groups = new ArrayList<>();
        groups.add(siteGroup());
        groups.add(generalGroup());
        groups.add(accountGroup());
        groups.add(featureGroup());
        groups.add(storageGroup());
        groups.add(aboutGroup());
        if (Setting.isHiddenSettingsEnabled()) groups.add(hiddenGroup());
        return new B7Messages.B7SettingsHome.Builder()
                .setScreenTitle(activity.getString(R.string.nav_setting))
                .setSearchFlutter(Setting.isFlutterB5Enabled())
                .setCacheManagementEnabled(CachePolicyStore.isManagementEnabled())
                .setHiddenVisible(Setting.isHiddenSettingsEnabled())
                .setDownloadEnabled(Setting.isDownloadEnabled())
                .setDownloadDeleteLabel(activity.getString(R.string.download_delete))
                .setDownloadEmptyText(activity.getString(R.string.setting_download_list_empty))
                .setGroups(groups)
                .setDownloadGroups(downloadGroups())
                .setDownloadsActive(downloadsActive())
                .setCacheScanning(cacheComputing)
                .setPendingRowId(pendingRowId)
                .setFlutterEnabled(Setting.isFlutterEnabled())
                .build();
    }

    /** Reads + clears SettingFragment.sPendingRow (mobile-only, via reflection) once per engine. */
    private void consumePendingRow() {
        if (pendingRowConsumed) return;
        pendingRowConsumed = true;
        try {
            Class<?> fragmentClass = Class.forName(FRAGMENT_SETTING);
            Field field = fragmentClass.getDeclaredField("sPendingRow");
            field.setAccessible(true);
            Object value = field.get(null);
            field.set(null, null);
            if (value instanceof String) pendingRowId = (String) value;
        } catch (Exception ignored) {
            // Leanback has no SettingFragment, or the field moved: no
            // pending row, the home just opens normally.
        }
    }

    private B7Messages.B7SettingsGroup group(String title, List<B7Messages.B7SettingsRow> rows) {
        return new B7Messages.B7SettingsGroup.Builder().setTitle(title).setRows(rows).build();
    }

    private B7Messages.B7SettingsRow row(String key, int titleRes, int subtitleRes,
            String value, boolean hasError, boolean isConfig, String member,
            boolean memberFlutter, long nativePage) {
        return new B7Messages.B7SettingsRow.Builder()
                .setKey(key)
                .setTitle(activity.getString(titleRes))
                .setSubtitle(subtitleRes == 0 ? "" : activity.getString(subtitleRes))
                .setValue(value == null ? "" : value)
                .setHasError(hasError)
                .setIsConfig(isConfig)
                .setMember(member)
                .setMemberFlutter(memberFlutter)
                .setNativePage(nativePage)
                .build();
    }

    /** One row's summary text: a failure degrades to the host error text (B3 safeRow pattern). */
    private static final class RowText {
        String text = "";
        boolean error;
    }

    private RowText safeRow(java.util.function.Supplier<String> supplier) {
        RowText row = new RowText();
        try {
            row.text = supplier.get();
            if (row.text == null) row.text = "";
        } catch (Throwable e) {
            e.printStackTrace();
            row.text = activity.getString(R.string.error_config_get);
            row.error = true;
        }
        return row;
    }

    private String getSwitch(boolean value) {
        return activity.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private B7Messages.B7SettingsGroup siteGroup() {
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        RowText vod = safeRow(VodConfig::getDesc);
        rows.add(row("vod", R.string.setting_vod, 0, vod.text, vod.error, true, "", false, -1));
        RowText live = safeRow(LiveConfig::getDesc);
        rows.add(row("live", R.string.setting_live, 0, live.text, live.error, true, "", false, -1));
        RowText wall = safeRow(() -> Setting.getWallDesc(WallConfig.getDesc()));
        rows.add(row("wall", R.string.setting_wall, 0, wall.text, wall.error, true, "", false, -1));
        return group(activity.getString(R.string.setting_group_site), rows);
    }

    private B7Messages.B7SettingsGroup generalGroup() {
        boolean b3 = Setting.isFlutterB3Enabled();
        boolean b4 = Setting.isFlutterB4Enabled();
        boolean b5 = Setting.isFlutterB5Enabled();
        boolean b6 = Setting.isFlutterB6Enabled();
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        RowText appearance = safeRow(this::getThemeModeText);
        // B8 master switch first in the general group, mirroring the
        // native SettingFragment row order (see docs/b8-master-switch.md).
        // The value stays empty: Dart renders a Switch from the snapshot's
        // flutterEnabled field instead of an on/off text.
        rows.add(row("flutter", R.string.b8_flutter_title, R.string.b8_flutter_sub,
                "", false, false, "", false, -1));
        rows.add(row("appearance", R.string.setting_appearance, R.string.setting_appearance_sub,
                appearance.text, appearance.error, false, "", false, -1));
        rows.add(row("widget", R.string.setting_enhance, R.string.setting_enhance_sub,
                "", false, false, "enhance", b4, 3));
        rows.add(row("player", R.string.setting_player, R.string.setting_player_sub,
                "", false, false, "player", b6, 2));
        rows.add(row("danmaku", R.string.setting_danmaku, R.string.setting_danmaku_sub,
                "", false, false, "danmaku", b3, 4));
        rows.add(row("subtitle", R.string.setting_subtitle, R.string.setting_subtitle_sub,
                "", false, false, "", false, -1));
        rows.add(row("ai", R.string.setting_ai, R.string.setting_ai_sub,
                "", false, false, "ai", b3, 8));
        rows.add(row("ad", R.string.setting_ad, R.string.setting_ad_sub,
                "", false, false, "ad", b3, 9));
        return group(activity.getString(R.string.setting_group_general), rows);
    }

    private B7Messages.B7SettingsGroup accountGroup() {
        boolean b3 = Setting.isFlutterB3Enabled();
        boolean b5 = Setting.isFlutterB5Enabled();
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        RowText tmdb = safeRow(() -> activity.getString(Setting.isTmdbReady()
                ? R.string.setting_configured : R.string.setting_unconfigured));
        rows.add(row("account", R.string.setting_tmdb, R.string.setting_tmdb_sub,
                tmdb.text, tmdb.error, false, "tmdb", b3, 7));
        rows.add(row("personal", R.string.setting_personal, R.string.setting_personal_sub,
                "", false, false, "personal", b5, 5));
        return group(activity.getString(R.string.setting_group_account), rows);
    }

    private B7Messages.B7SettingsGroup featureGroup() {
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        rows.add(row("local_source", R.string.setting_local_source, R.string.setting_local_source_sub,
                "", false, false, "", false, -1));
        rows.add(row("lab", R.string.setting_lab, R.string.setting_lab_sub,
                "", false, false, "", false, -1));
        RowText gitCloud = safeRow(() -> activity.getString(
                R.string.git_cloud_account_count, GitCloudAccountStore.list().size()));
        rows.add(row("gitcloud", R.string.setting_git_cloud, R.string.setting_gitcloud_sub,
                gitCloud.text, gitCloud.error, false, "", false, -1));
        rows.add(row("onekey", R.string.setting_one_key_sync, R.string.setting_onekey_sub,
                "", false, false, "", false, -1));
        RowText drive = safeRow(() -> getSwitch(Setting.isDriveCheck()));
        rows.add(row("drive", R.string.setting_drive_check, R.string.setting_drive_sub,
                drive.text, drive.error, false, "", false, -1));
        RowText siteHealth = safeRow(this::getSiteHealthText);
        rows.add(row("site_health", R.string.setting_site_health, R.string.setting_site_health_sub,
                siteHealth.text, siteHealth.error, false, "", false, -1));
        return group(activity.getString(R.string.setting_group_feature), rows);
    }

    private B7Messages.B7SettingsGroup storageGroup() {
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        rows.add(row("cache", R.string.setting_cache, R.string.setting_cache_sub,
                cacheTextShown(), false, false, "", false, -1));
        rows.add(row("backup", R.string.setting_backup, R.string.setting_backup_sub,
                "", false, false, "", false, -1));
        rows.add(row("restore", R.string.setting_restore, R.string.setting_restore_sub,
                "", false, false, "", false, -1));
        RowText doh = safeRow(() -> getDohList()[getDohIndex()]);
        rows.add(row("doh", R.string.setting_doh, R.string.setting_doh_sub,
                doh.text, doh.error, false, "", false, -1));
        RowText incognito = safeRow(() -> getSwitch(Setting.isIncognito()));
        rows.add(row("incognito", R.string.setting_incognito, R.string.setting_incognito_sub,
                incognito.text, incognito.error, false, "", false, -1));
        return group(activity.getString(R.string.setting_group_storage), rows);
    }

    private B7Messages.B7SettingsGroup aboutGroup() {
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        RowText about = safeRow(AppVersion::fullName);
        rows.add(row("about", R.string.setting_about, R.string.setting_about_sub,
                about.text, about.error, false, "", false, -1));
        return group(activity.getString(R.string.setting_group_about), rows);
    }

    private B7Messages.B7SettingsGroup hiddenGroup() {
        List<B7Messages.B7SettingsRow> rows = new ArrayList<>();
        RowText download = safeRow(() -> getSwitch(Setting.isDownloadEnabled()));
        rows.add(row("download", R.string.setting_download, R.string.setting_download_sub,
                download.text, download.error, false, "", false, -1));
        return group(activity.getString(R.string.setting_group_hidden), rows);
    }

    private String getThemeModeText() {
        switch (Setting.getThemeMode()) {
            case 0:
                return activity.getString(R.string.setting_theme_mode_light);
            case 1:
                return activity.getString(R.string.setting_theme_mode_dark);
            default:
                return activity.getString(R.string.setting_theme_mode_system);
        }
    }

    private String getSiteHealthText() {
        SiteHealthStore.Summary summary = SiteHealthStore.summary();
        String state = getSwitch(Setting.isSiteHealthSort());
        if (summary.siteCount <= 0) return state;
        return state + " · " + activity.getString(
                R.string.site_health_report_setting_summary, summary.siteCount, summary.sampleCount);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ cache text

    /** The cache row's shown text: the scanning placeholder while a computation runs (management on). */
    private String cacheTextShown() {
        if (CachePolicyStore.isManagementEnabled() && cacheComputing) {
            return activity.getString(R.string.cache_management_scanning);
        }
        return lastCacheText;
    }

    /** Starts one async cache-size computation when the cached text is stale (mirrors setCacheText). */
    private void maybeComputeCacheText() {
        if (!cacheStale || cacheComputing) return;
        if (System.currentTimeMillis() - lastCacheComputeStartMs < 1000) return;
        cacheComputing = true;
        lastCacheComputeStartMs = System.currentTimeMillis();
        if (!CachePolicyStore.isManagementEnabled()) {
            FileUtil.getCacheSize(new Callback() {
                @Override
                public void success(String result) {
                    lastCacheText = result == null ? "" : result;
                    cacheStale = false;
                    cacheComputing = false;
                }
            });
            return;
        }
        CacheCenter.get().requestSnapshot(false, snapshot -> {
            String total = FileUtil.byteCountToDisplaySize(snapshot.totalBytes());
            long quota = snapshot.systemQuotaBytes();
            lastCacheText = quota > 0
                    ? activity.getString(R.string.cache_management_inline, total,
                            FileUtil.byteCountToDisplaySize(quota))
                    : total;
            cacheStale = false;
            cacheComputing = false;
        });
    }

    // ------------------------------------------------------------------ download list

    /** Mirrors SettingFragment.refreshDownloadList: only when hidden + download enabled. */
    private List<B7Messages.B7DownloadDrama> downloadGroups() {
        List<B7Messages.B7DownloadDrama> groups = new ArrayList<>();
        if (!Setting.isHiddenSettingsEnabled() || !Setting.isDownloadEnabled()) return groups;
        Map<String, List<DownloadTask>> byDrama = new LinkedHashMap<>();
        for (DownloadTask task : VideoDownloadManager.get().getTasks()) {
            List<DownloadTask> list = byDrama.get(task.dramaName);
            if (list == null) {
                list = new ArrayList<>();
                byDrama.put(task.dramaName, list);
            }
            list.add(task);
        }
        for (Map.Entry<String, List<DownloadTask>> entry : byDrama.entrySet()) {
            List<B7Messages.B7DownloadEpisode> episodes = new ArrayList<>();
            for (DownloadTask task : entry.getValue()) episodes.add(downloadEpisode(task));
            int done = 0;
            for (DownloadTask task : entry.getValue()) if (task.isDone()) done++;
            String summary = activity.getString(
                    R.string.download_drama_summary, entry.getValue().size(), done);
            groups.add(new B7Messages.B7DownloadDrama.Builder()
                    .setDramaName(entry.getKey())
                    .setSummary(summary)
                    .setEpisodes(episodes)
                    .build());
        }
        return groups;
    }

    private B7Messages.B7DownloadEpisode downloadEpisode(DownloadTask task) {
        int progress = task.progress();
        boolean showProgress;
        boolean indeterminate;
        if (task.status == DownloadTask.Status.PAUSED) {
            showProgress = progress >= 0;
            indeterminate = false;
        } else if (task.status == DownloadTask.Status.DOWNLOADING) {
            showProgress = true;
            indeterminate = progress < 0;
        } else if (task.status == DownloadTask.Status.RESOLVING) {
            showProgress = true;
            indeterminate = true;
        } else {
            showProgress = false;
            indeterminate = false;
        }
        return new B7Messages.B7DownloadEpisode.Builder()
                .setId(task.id == null ? "" : task.id)
                .setName(task.episodeName == null ? "" : task.episodeName)
                .setStatusText(downloadStatusText(task))
                .setError(downloadError(task))
                .setProgress((long) progress)
                .setShowProgress(showProgress)
                .setIndeterminate(indeterminate)
                .build();
    }

    private String downloadStatusText(DownloadTask task) {
        if (task == null || task.status == null) return "";
        switch (task.status) {
            case DONE:
                return activity.getString(R.string.download_state_done);
            case FAILED:
                return activity.getString(R.string.download_state_failed_retry);
            case PAUSED:
                return activity.getString(R.string.download_state_paused);
            case RESOLVING:
                return activity.getString(R.string.download_state_resolving);
            case DOWNLOADING: {
                int progress = task.progress();
                String state = activity.getString(R.string.download_state_downloading);
                return progress < 0 ? state : state + " " + progress + "%";
            }
            case QUEUED:
            default:
                return activity.getString(R.string.download_state_queued);
        }
    }

    /** Mirrors SettingFragment.bindDownloadError + shortenDownloadError. */
    private String downloadError(DownloadTask task) {
        if (task.status != DownloadTask.Status.FAILED || TextUtils.isEmpty(task.error)) return "";
        String root = DownloadPathUtil.getDownloadRoot().getParent();
        if (!TextUtils.isEmpty(root) && task.error.startsWith(root + "/")) {
            return task.error.substring(root.length() + 1);
        }
        return task.error;
    }

    private boolean downloadsActive() {
        for (DownloadTask task : VideoDownloadManager.get().getTasks()) {
            if (task.isActive()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ config rows

    @Override
    public void openVodManage(B7Messages.VoidResult result) {
        openHistoryDialog("vod", true, result);
    }

    @Override
    public void openLiveManage(B7Messages.VoidResult result) {
        openHistoryDialog("live", true, result);
    }

    @Override
    public void openWallManage(B7Messages.VoidResult result) {
        openHistoryDialog("wall", true, result);
    }

    @Override
    public void openVodHistory(B7Messages.VoidResult result) {
        openHistoryDialog("vod", false, result);
    }

    @Override
    public void openLiveHistory(B7Messages.VoidResult result) {
        openHistoryDialog("live", false, result);
    }

    @Override
    public void openWallHistory(B7Messages.VoidResult result) {
        openHistoryDialog("wall", false, result);
    }

    /** HistoryDialog is mobile-only: create().vod()/live()/wall()[.manage()].show(fragment). */
    private void openHistoryDialog(String type, boolean manage, B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            setPending(result, completed);
            Class<?> dialogClass = Class.forName(DIALOG_HISTORY);
            Object dialog = dialogClass.getMethod("create").invoke(null);
            dialogClass.getMethod(type).invoke(dialog);
            if (manage) dialogClass.getMethod("manage").invoke(dialog);
            dialogClass.getMethod("show", Fragment.class).invoke(dialog, homeHostFragment());
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openVodHome(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            setPending(result, completed);
            Class<?> dialogClass = Class.forName(DIALOG_SITE);
            Object dialog = dialogClass.getMethod("create").invoke(null);
            dialogClass.getMethod("search").invoke(dialog);
            dialogClass.getMethod("change").invoke(dialog);
            dialogClass.getMethod("show", Fragment.class).invoke(dialog, homeHostFragment());
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openLiveHome(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            setPending(result, completed);
            Class<?> dialogClass = Class.forName(DIALOG_LIVE);
            dialogClass.getMethod("show", Fragment.class).invoke(null, homeHostFragment());
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void setWallDefault() {
        Setting.putWall(Setting.nextDefaultWall());
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    @Override
    public void setWallRefresh(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        Setting.putWall(0);
        WallConfig.get().load(new Callback() {
            @Override
            public void start() {
                Notify.progress(activity);
            }

            @Override
            public void success() {
                Notify.dismiss();
                cacheStale = true;
                completeOnce(result, completed);
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        });
    }

    // ------------------------------------------------------------------ config apply

    /** Mirrors SettingFragment.setConfig (incl. the file-permission branch). */
    private void applyConfig(Config config) {
        if (config == null) return;
        String url = config.getUrl();
        if (!TextUtils.isEmpty(url) && url.startsWith("file")) {
            // B4 V9 pattern: the Activity overload (same PermissionX call
            // as the native Fragment overload).
            PermissionUtil.requestFile(activity, allGranted -> loadConfig(config));
        } else {
            loadConfig(config);
        }
    }

    /** Mirrors SettingFragment.load + getCallback. */
    private void loadConfig(Config config) {
        switch (config.getType()) {
            case 0: {
                String previousVodUrl = VodConfig.getUrl();
                VodConfig.load(config, loadCallback());
                if (ConfigSyncPolicy.shouldSyncLive(previousVodUrl, LiveConfig.getUrl())) {
                    Config liveConfig = AppDatabase.get().getConfigDao().find(config.getUrl(), 1);
                    if (liveConfig != null) LiveConfig.load(liveConfig, new Callback());
                }
                break;
            }
            case 1:
                LiveConfig.load(config, loadCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, loadCallback());
                break;
            default:
                break;
        }
    }

    private Callback loadCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(activity);
            }

            @Override
            public void success() {
                Notify.dismiss();
                cacheStale = true;
                completePending();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    // ------------------------------------------------------------------ other rows

    @Override
    public void openAppearance(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // AppearanceDialog.show(Fragment) returns void, so the
            // dialog instance is not available: instantiate it the same
            // way and show it via the headless fragment's child manager.
            Fragment host = homeHostFragment();
            FragmentManager childFm = host.getChildFragmentManager();
            watchDialogDismiss(childFm, DIALOG_APPEARANCE, result, completed);
            Class<?> dialogClass = Class.forName(DIALOG_APPEARANCE);
            Object dialog = dialogClass.getDeclaredConstructor().newInstance();
            Method show = DialogFragment.class.getMethod(
                    "show", FragmentManager.class, String.class);
            show.invoke(dialog, childFm, null);
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openLocalSource(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            Fragment host = homeHostFragment();
            watchDialogDismiss(host.getChildFragmentManager(), DIALOG_LOCAL_SOURCE, result, completed);
            Class<?> dialogClass = Class.forName(DIALOG_LOCAL_SOURCE);
            Object dialog = dialogClass.getMethod("create").invoke(null);
            dialogClass.getMethod("show", Fragment.class).invoke(dialog, host);
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openLab() {
        // B8 B21: mobile + flag on goes to the Flutter lab page
        // (start-then-finish: one C1-engine screen at a time, same G2
        // race as the other start-then-finish sites); TV and flag off
        // stay on the native LabActivity.
        if (Util.isMobile() && Setting.isFlutterB21Enabled()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_LAB);
            activity.finish();
            return;
        }
        LabActivity.start(activity);
    }

    @Override
    public void openGitCloud(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // The Runnable runs in onDestroyView (any dismiss path).
            GitCloudDialog.show(homeHostFragment(), () -> completeOnce(result, completed));
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openOneKeySync() {
        OneKeySyncDialog.create().show(activity);
    }

    @Override
    public void toggleDriveCheck() {
        Setting.putDriveCheck(!Setting.isDriveCheck());
    }

    @Override
    public void openSiteHealth(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // The Runnable runs in onPositive only (confirm path).
            SiteHealthDialog.show(homeHostFragment(), () -> completeOnce(result, completed));
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void clearSiteHealth() {
        SiteHealthStore.clear();
        Notify.show(R.string.site_health_clear_done);
    }

    @Override
    public void openCacheClear() {
        if (!CachePolicyStore.isManagementEnabled()) {
            FileUtil.clearCache(new Callback() {
                @Override
                public void success() {
                    cacheStale = true;
                }
            });
        } else {
            CacheManagementDialog.cleanEverything();
        }
        cacheStale = true;
    }

    @Override
    public void openCacheManage(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            if (!CachePolicyStore.isManagementEnabled()) {
                openCacheClear();
                completeOnce(result, completed);
                return;
            }
            FragmentManager fm = activity.getSupportFragmentManager();
            watchDialogDismiss(fm, "com.github.moyingshi.osc.tv.ui.dialog.CacheManagementDialog",
                    result, completed, () -> cacheStale = true);
            CacheManagementDialog.show(activity);
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openBackup(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        PermissionUtil.requestFile(activity, allGranted -> {
            if (!allGranted) {
                Notify.show(R.string.backup_permission_denied);
                return;
            }
            // The literal mirrors the native call (hardcoded in SettingFragment.onBackup).
            BackupProgressDialog progress = BackupProgressDialog.open(
                    activity.getSupportFragmentManager(), "备份应用数据");
            AppDatabase.backup(new Callback() {
                @Override
                public void success() {
                    progress.finish();
                    Notify.show(R.string.backup_success);
                    completeOnce(result, completed);
                }

                @Override
                public void error() {
                    progress.finish();
                    Notify.show(R.string.backup_fail);
                }
            }, progress::update);
        });
    }

    @Override
    public void openRestore(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        PermissionUtil.requestFile(activity, allGranted -> {
            if (!allGranted) {
                Notify.show(R.string.backup_permission_denied);
                return;
            }
            try {
                Class<?> dialogClass = Class.forName(DIALOG_RESTORE);
                Object dialog = dialogClass.getMethod("create").invoke(null);
                // RestoreDialog.Callback is the main impl.Callback; the
                // dialog itself is mobile-only, so the callback is a
                // reflective Proxy (B3 V5 pattern).
                Object callback = Proxy.newProxyInstance(
                        B7HostApiImpl.class.getClassLoader(),
                        new Class<?>[]{Callback.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "success":
                                    Notify.show(R.string.restore_success);
                                    completeOnce(result, completed);
                                    break;
                                case "error":
                                    Notify.show(R.string.restore_fail);
                                    break;
                                default:
                                    break;
                            }
                            return null;
                        });
                dialogClass.getMethod("show", FragmentActivity.class, Callback.class)
                        .invoke(dialog, activity, callback);
            } catch (Throwable e) {
                e.printStackTrace();
                if (!completed[0]) {
                    completed[0] = true;
                    result.error(e);
                }
            }
        });
    }

    @Override
    public void openDoh(B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            ChoiceDialog.showSingle(homeHostFragment(), R.string.setting_doh,
                    getDohList(), getDohIndex(), which -> {
                        Doh doh = VodConfig.get().getDoh().get(which);
                        OkHttp.dns().setDoh(doh);
                        Setting.putDoh(doh.toString());
                        completeOnce(result, completed);
                    });
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void toggleIncognito() {
        Setting.putIncognito(!Setting.isIncognito());
    }

    @Override
    public void openAbout() {
        // Mirrors SettingFragment.onVersion (C1 flag split).
        if (Setting.isFlutterC1Enabled()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_ABOUT);
            activity.finish();
            return;
        }
        AboutDialog.show(activity, () -> Updater.create().force().start(activity));
    }

    @Override
    public void openSubtitle() {
        // Mirrors SettingFragment.onSubtitle (C1 flag split).
        if (Setting.isFlutterC1Enabled()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_SUBTITLE);
            activity.finish();
            return;
        }
        openHomeAt(6);
    }

    @Override
    public void toggleDownloadEnabled() {
        // Mirrors SettingFragment.setDownloadEnabled.
        Setting.putDownloadEnabled(!Setting.isDownloadEnabled());
        if (!Setting.isDownloadEnabled()) {
            Setting.putHiddenSettingsEnabled(false);
            Notify.show(R.string.hidden_settings_disabled);
        }
    }

    @Override
    public void openNativePage(Long page) {
        int targetPage = page == null ? -1 : page.intValue();
        openHomeAt(targetPage);
        activity.finish();
    }

    /** B5 openSearchEntry pattern; routing lives in FlutterNav.openHomeAt. */
    private void openHomeAt(int position) {
        FlutterNav.openHomeAt(activity, position);
    }

    @Override
    public void episodeAction(String id) {
        DownloadTask found = null;
        for (DownloadTask task : VideoDownloadManager.get().getTasks()) {
            if (id != null && id.equals(task.id)) {
                found = task;
                break;
            }
        }
        if (found == null) return;
        switch (found.status) {
            case DONE:
                playDownloaded(found);
                break;
            case FAILED:
                if (VideoDownloadManager.get().enqueue(found.dramaName, found.siteKey,
                        found.flag, found.episodeName, found.episodeUrl, found.poster)) {
                    Notify.show(R.string.download_retry_started);
                }
                break;
            case PAUSED:
                if (VideoDownloadManager.get().resumeTask(found.id)) {
                    Notify.show(R.string.download_resumed);
                }
                break;
            case QUEUED:
            case RESOLVING:
            case DOWNLOADING:
                if (VideoDownloadManager.get().pauseTask(found.id)) {
                    Notify.show(R.string.download_paused);
                }
                break;
            default:
                break;
        }
    }

    private void playDownloaded(DownloadTask task) {
        if (task == null || !task.isDone()) return;
        if (task.filePath == null || !new File(task.filePath).exists()) {
            Notify.show(R.string.download_file_missing);
            return;
        }
        VideoActivity.start(activity, "file://" + task.filePath);
    }

    @Override
    public void deleteDrama(String dramaName, B7Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // Mirrors SettingFragment.confirmDeleteDrama.
            new WebHtvAlertDialogBuilder(activity, R.style.Theme_WebHTV_Dialog)
                    .setTitle(R.string.download_delete_title)
                    .setMessage(activity.getString(R.string.download_delete_message, dramaName))
                    .setPositiveButton(R.string.download_delete, (dialog, which) ->
                            VideoDownloadManager.get().deleteDrama(dramaName, ok -> {
                                Notify.show(ok ? R.string.download_deleted
                                        : R.string.download_delete_failed);
                                completeOnce(result, completed);
                            }))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .show();
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void setPending(B7Messages.VoidResult result, boolean[] completed) {
        pendingResult = result;
        pendingCompleted = completed;
    }

    private void completePending() {
        if (pendingResult != null && pendingCompleted != null) {
            completeOnce(pendingResult, pendingCompleted);
        }
        pendingResult = null;
        pendingCompleted = null;
    }

    private static void completeOnce(B7Messages.VoidResult result, boolean[] completed) {
        if (!completed[0]) {
            completed[0] = true;
            result.success();
        }
    }

    private B7HomeHostFragment homeHostFragment() {
        FragmentManager fm = activity.getSupportFragmentManager();
        Fragment existing = fm.findFragmentByTag(TAG_HOME_HOST);
        if (existing instanceof B7HomeHostFragment host) {
            // After a rotation the FragmentManager restores the fragment
            // without its host reference; re-attach it every time.
            host.setHost(this);
            return host;
        }
        B7HomeHostFragment host = new B7HomeHostFragment();
        host.setHost(this);
        fm.beginTransaction().add(host, TAG_HOME_HOST).commitNow();
        return host;
    }

    /**
     * Completes the @async call when a dialog fragment of the given class
     * is destroyed (any dismiss path). Used for dialogs with no change
     * callback of their own (Appearance, LocalSource, CacheManagement):
     * the watcher is registered before the dialog is shown so a quick
     * dismiss cannot be missed, and unregisters itself when it fires.
     */
    private void watchDialogDismiss(FragmentManager fm, String dialogClassName,
            B7Messages.VoidResult result, boolean[] completed) {
        watchDialogDismiss(fm, dialogClassName, result, completed, null);
    }

    private void watchDialogDismiss(FragmentManager fm, String dialogClassName,
            B7Messages.VoidResult result, boolean[] completed, Runnable onDestroyed) {
        FragmentManager.FragmentLifecycleCallbacks callbacks =
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentDestroyed(@NonNull FragmentManager manager,
                            @NonNull Fragment fragment) {
                        if (!dialogClassName.equals(fragment.getClass().getName())) return;
                        manager.unregisterFragmentLifecycleCallbacks(this);
                        if (onDestroyed != null) onDestroyed.run();
                        completeOnce(result, completed);
                    }
                };
        fm.registerFragmentLifecycleCallbacks(callbacks, false);
    }

    void onConfigPicked(Config config) {
        applyConfig(config);
    }

    void onSitePicked(Site item) {
        VodConfig.get().setHome(item);
        completePending();
    }

    void onLivePicked(Live item) {
        LiveConfig.get().setHome(item);
        completePending();
    }

    /**
     * Headless parent fragment for the reflectively opened mobile-only
     * dialogs (see {@link #openHistoryDialog}, {@link #openVodHome},
     * {@link #openLiveHome}). It carries no UI and lives for the host
     * Activity's lifetime; the dialogs call the listener methods on it
     * (HistoryDialog casts its parent fragment to ConfigListener, and
     * likewise SiteDialog / LiveDialog). Public static with an implicit
     * no-arg constructor so the FragmentManager can restore it after
     * process death (its host is null then, and the calls are no-ops).
     */
    public static class B7HomeHostFragment extends Fragment
            implements ConfigListener, SiteListener, LiveListener {

        private B7HostApiImpl host;

        void setHost(B7HostApiImpl host) {
            this.host = host;
        }

        @Override
        public void setConfig(Config config) {
            if (host != null) host.onConfigPicked(config);
        }

        @Override
        public void setSite(Site item) {
            if (host != null) host.onSitePicked(item);
        }

        @Override
        public void setLive(Live item) {
            if (host != null) host.onLivePicked(item);
        }
    }
}
