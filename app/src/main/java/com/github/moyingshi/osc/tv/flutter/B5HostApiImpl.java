package com.github.moyingshi.osc.tv.flutter;


import androidx.fragment.app.FragmentActivity;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.event.RefreshEvent;
import com.github.moyingshi.osc.tv.setting.AppBranding;
import com.github.moyingshi.osc.tv.setting.AutoBackupPolicy;
import com.github.moyingshi.osc.tv.following.FollowingScheduler;
import com.github.moyingshi.osc.tv.following.FollowingSettings;
import com.github.moyingshi.osc.tv.setting.GroupRuleConfig;
import com.github.moyingshi.osc.tv.setting.PlayerSetting;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.activity.AppBrandingActivity;
import com.github.moyingshi.osc.tv.ui.dialog.GroupRuleDialog;
import com.github.moyingshi.osc.tv.ui.dialog.SliderNumberDialog;
import com.github.moyingshi.osc.tv.ui.dialog.SpeedSettingDialog;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.Util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * B8 B5: host side of the personal settings page and the settings
 * search screen (docs/b8-b5-personal.md). Mirrors B4HostApiImpl: a
 * keyed row snapshot for the personal page, one-shot @async dialog
 * methods, and fire-and-forget navigation. The search index comes from
 * the mobile-only SettingSearchIndex via reflection (see §2.9 of the
 * task doc); every other class referenced here is in the main source
 * set, so no reflection is needed for the personal page itself.
 *
 * <p>Note the two kinds of dialog completion, same as B3/B4 (see
 * docs/b8-b3-settings-a.md §2.5): change-only dialogs complete only
 * when a value was picked, and Dart never awaits any dialog call (it
 * attaches a {@code .then} refetch), so a dialog closed without a
 * change simply completes nothing.</p>
 */
public class B5HostApiImpl implements B5Messages.B5HostApi {
    private final FragmentActivity activity;

    public B5HostApiImpl(FragmentActivity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------
    // B5 feature flag
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Personal settings snapshot
    // ------------------------------------------------------------------

    @Override
    public B5Messages.B5PersonalSettings getPersonalSettings() {
        List<B5Messages.B5PersonalRow> rows = new ArrayList<>();
        // Layout order (fragment_setting_personal.xml) IS the display
        // order for this page, unlike the enhance page.
        rows.add(row("searchThread", R.string.setting_search_thread, () -> {
            int value = Setting.getSearchThread();
            return value > 0 ? String.valueOf(value) : activity.getString(R.string.setting_off);
        }));
        rows.add(row("following", R.string.setting_following, () -> getSwitch(FollowingSettings.isEnabled())));
        rows.add(row("autoBackup", R.string.setting_auto_backup, () ->
                getSwitch(AutoBackupPolicy.isEffective(Setting.isAutoBackup(), Setting.hasFileAccess()))));
        rows.add(row("playbackOverlay", R.string.setting_playback_overlay, () ->
                getSwitch(Setting.isPlaybackOverlayEnabled())));
        rows.add(row("playBackToDetail", R.string.setting_play_back_to_detail, () ->
                getSwitch(Setting.isPlayBackToDetail())));
        rows.add(row("episodeHistory", R.string.setting_episode_history, () ->
                getSwitch(Setting.isEpisodeHistory())));
        rows.add(row("globalHistory", R.string.setting_global_history, () ->
                arrayText(R.array.select_global_history_mode, Setting.getGlobalHistoryMode())));
        rows.add(row("interfaceFailover", R.string.setting_interface_failover, () ->
                arrayText(R.array.select_interface_failover_mode, Setting.getInterfaceFailoverMode())));
        rows.add(row("playSpeed", R.string.setting_play_speed, () ->
                String.format(Locale.US, "%.2f", PlayerSetting.getDefaultSpeed())));
        rows.add(row("groupRule", R.string.setting_group_rule, () -> activity.getString(
                R.string.setting_group_rule_summary, GroupRuleConfig.enabledCount(), GroupRuleConfig.totalCount())));
        rows.add(row("searchUi", R.string.setting_search_ui, () ->
                arrayText(R.array.select_search_ui, Setting.getSearchUi())));
        rows.add(row("searchColumn", R.string.setting_search_column, () ->
                arrayText(R.array.select_search_column, Setting.getSearchColumn())));
        rows.add(row("siteColumn", R.string.setting_site_column, () ->
                arrayText(R.array.select_site_column, Setting.getSiteColumn() - 1)));
        rows.add(row("searchResultSort", R.string.setting_search_result_sort, () ->
                arrayText(R.array.select_search_result_sort, Setting.getSearchResultSort())));
        rows.add(row("resetApp", R.string.setting_reset_app, () -> ""));
        rows.add(row("appBranding", R.string.setting_app_branding, () -> AppBranding.getSummary(activity)));
        rows.add(row("touchOptimization", R.string.setting_touch_optimization, () ->
                getSwitch(Setting.isTouchOptimized())));
        rows.add(row("liquidGlass", R.string.setting_liquid_glass, () ->
                getSwitch(Setting.isLiquidGlass())));
        rows.add(row("dramaCollected", R.string.setting_short_drama_collected, () ->
                getSwitch(Setting.isShortDramaCollected())));
        rows.add(row("glassBlur", R.string.setting_glass_blur, () ->
                String.valueOf(Setting.getGlassBlur())));
        rows.add(row("glassDistortion", R.string.setting_glass_distortion, () ->
                String.valueOf(Setting.getGlassDistortion())));
        rows.add(row("glassTranslucency", R.string.setting_glass_translucency, () ->
                Setting.getGlassTranslucency() + "%"));
        rows.add(row("glassDispersion", R.string.setting_glass_dispersion, () ->
                getSwitch(Setting.isGlassDispersion())));
        rows.add(row("glassReset", R.string.setting_glass_reset, () -> ""));
        return new B5Messages.B5PersonalSettings.Builder()
                .setScreenTitle(activity.getString(R.string.setting_personal))
                .setRows(rows)
                .build();
    }

    private B5Messages.B5PersonalRow row(String key, int titleRes, Supplier<String> text) {
        return safeRow(key, titleRes, text);
    }

    private B5Messages.B5PersonalRow safeRow(String key, int titleRes, Supplier<String> text) {
        try {
            String value = text.get();
            return new B5Messages.B5PersonalRow.Builder()
                    .setKey(key)
                    .setTitle(activity.getString(titleRes))
                    .setText(value == null ? "" : value)
                    .setHasError(false)
                    .build();
        } catch (Exception e) {
            return new B5Messages.B5PersonalRow.Builder()
                    .setKey(key)
                    .setTitle(activity.getString(titleRes))
                    .setText(activity.getString(R.string.error_config_get))
                    .setHasError(true)
                    .build();
        }
    }

    /** The personal page's switch text is setting_on/setting_off. */
    private String getSwitch(boolean enabled) {
        return activity.getString(enabled ? R.string.setting_on : R.string.setting_off);
    }

    /** Reads a mode array with a clamped index (B3 V10 same practice). */
    private String arrayText(int arrayRes, int index) {
        String[] values = activity.getResources().getStringArray(arrayRes);
        if (values.length == 0) return "";
        int i = Math.max(0, Math.min(index, values.length - 1));
        return values[i];
    }

    private int cycleNext(int arrayRes, int current) {
        int length = activity.getResources().getStringArray(arrayRes).length;
        if (length <= 0) return current;
        return (current + 1) % length;
    }

    // ------------------------------------------------------------------
    // Toggles (native side effects mirrored from SettingPersonalFragment)
    // ------------------------------------------------------------------

    @Override
    public void toggleFollowing() {
        boolean enabled = !FollowingSettings.isEnabled();
        FollowingSettings.setEnabled(enabled);
        if (enabled) {
            FollowingScheduler.ensurePeriodic(activity);
            FollowingScheduler.enqueueDueNow(activity);
        } else {
            FollowingScheduler.cancelAll(activity);
        }
        ConfigEvent.common();
        RefreshEvent.home();
    }

    @Override
    public void togglePlaybackOverlay() {
        Setting.putPlaybackOverlayEnabled(!Setting.isPlaybackOverlayEnabled());
        ConfigEvent.common();
    }

    @Override
    public void togglePlayBackToDetail() {
        Setting.putPlayBackToDetail(!Setting.isPlayBackToDetail());
        ConfigEvent.common();
    }

    @Override
    public void toggleEpisodeHistory() {
        Setting.putEpisodeHistory(!Setting.isEpisodeHistory());
        ConfigEvent.common();
    }

    @Override
    public void toggleTouchOptimization() {
        Setting.putTouchOptimized(!Setting.isTouchOptimized());
        ConfigEvent.common();
    }

    @Override
    public void toggleLiquidGlass() {
        Setting.putLiquidGlass(!Setting.isLiquidGlass());
        ConfigEvent.common();
        RefreshEvent.theme();
    }

    @Override
    public void toggleShortDramaCollected() {
        Setting.putShortDramaCollected(!Setting.isShortDramaCollected());
        ConfigEvent.common();
    }

    @Override
    public void toggleGlassDispersion() {
        Setting.putGlassDispersion(!Setting.isGlassDispersion());
        ConfigEvent.common();
    }

    // ------------------------------------------------------------------
    // Cycles
    // ------------------------------------------------------------------

    @Override
    public void cycleGlobalHistory() {
        Setting.putGlobalHistoryMode(cycleNext(R.array.select_global_history_mode, Setting.getGlobalHistoryMode()));
        ConfigEvent.common();
        RefreshEvent.history();
    }

    @Override
    public void cycleInterfaceFailover() {
        Setting.putInterfaceFailoverMode(cycleNext(R.array.select_interface_failover_mode, Setting.getInterfaceFailoverMode()));
        ConfigEvent.common();
    }

    @Override
    public void cycleSearchUi() {
        Setting.putSearchUi(cycleNext(R.array.select_search_ui, Setting.getSearchUi()));
        ConfigEvent.common();
    }

    @Override
    public void cycleSearchColumn() {
        Setting.putSearchColumn(cycleNext(R.array.select_search_column, Setting.getSearchColumn()));
        ConfigEvent.common();
    }

    @Override
    public void cycleSiteColumn() {
        Setting.putSiteColumn(Setting.getSiteColumn() == 1 ? 2 : 1);
        ConfigEvent.common();
    }

    @Override
    public void cycleSearchResultSort() {
        Setting.putSearchResultSort(cycleNext(R.array.select_search_result_sort, Setting.getSearchResultSort()));
        ConfigEvent.common();
    }

    @Override
    public void resetGlass() {
        Setting.putGlassBlur(5);
        Setting.putGlassDistortion(30);
        Setting.putGlassTranslucency(50);
        Setting.putGlassDispersion(true);
        ConfigEvent.common();
    }

    // ------------------------------------------------------------------
    // @async dialogs (change-only completion; see the class doc)
    // ------------------------------------------------------------------

    private void completeOnce(B5Messages.VoidResult result, boolean[] completed) {
        if (!completed[0]) {
            completed[0] = true;
            result.success();
        }
    }

    @Override
    public void openSearchThread(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        SliderNumberDialog.show(activity, R.string.setting_search_thread, Setting.getSearchThread(), 1, 100, value -> {
            Setting.putSearchThread(value);
            ConfigEvent.common();
            completeOnce(result, completed);
        });
    }

    @Override
    public void openPlaySpeed(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        SpeedSettingDialog.show(activity, R.string.setting_play_speed, PlayerSetting.getDefaultSpeed(), 0.5f, 5f, 0.25f, value -> {
            PlayerSetting.putDefaultSpeed(value);
            ConfigEvent.common();
            completeOnce(result, completed);
        });
    }

    @Override
    public void openGroupRule(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        GroupRuleDialog.create(activity).onChanged(() -> completeOnce(result, completed)).show();
    }

    @Override
    public void openGlassBlur(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        SliderNumberDialog.show(activity, R.string.setting_glass_blur, Setting.getGlassBlur(), 0, 40, value -> {
            Setting.putGlassBlur(value);
            ConfigEvent.common();
            completeOnce(result, completed);
        });
    }

    @Override
    public void openGlassDistortion(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        SliderNumberDialog.show(activity, R.string.setting_glass_distortion, Setting.getGlassDistortion(), 0, 30, value -> {
            Setting.putGlassDistortion(value);
            ConfigEvent.common();
            completeOnce(result, completed);
        });
    }

    @Override
    public void openGlassTranslucency(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        SliderNumberDialog.show(activity, R.string.setting_glass_translucency, Setting.getGlassTranslucency(), 0, 100, value -> {
            Setting.putGlassTranslucency(value);
            ConfigEvent.common();
            completeOnce(result, completed);
        });
    }

    @Override
    public void openAutoBackup(B5Messages.VoidResult result) {
        boolean[] completed = new boolean[]{false};
        if (AutoBackupPolicy.isEffective(Setting.isAutoBackup(), Setting.hasFileAccess())) {
            Setting.putAutoBackup(false);
            ConfigEvent.common();
            completeOnce(result, completed);
            return;
        }
        // Permission request (B4 V9 same pattern): the Activity overload
        // is used because the impl holds a FragmentActivity, not a
        // Fragment. On denial the host shows a toast and completes
        // nothing; Dart never awaits this call.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        PermissionUtil.requestFile(activity, granted -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (granted == null || !granted) {
                Notify.show(R.string.backup_permission_denied);
                return;
            }
            Setting.putAutoBackup(true);
            ConfigEvent.common();
            completeOnce(result, completed);
        });
    }

    // ------------------------------------------------------------------
    // Fire-and-forget (reset confirm / native nav)
    // ------------------------------------------------------------------

    @Override
    public void showResetAppDialog() {
        new WebHtvAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_reset_app)
                .setMessage(R.string.dialog_reset_app_data)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    boolean success = Util.resetApp();
                    if (!success) Notify.show(R.string.reset_app_failed);
                })
                .show();
    }

    @Override
    public void openAppBranding() {
        AppBrandingActivity.start(activity);
    }

    // ------------------------------------------------------------------
    // Settings search
    // ------------------------------------------------------------------

    @Override
    public B5Messages.B5SearchScreen getSettingSearch() {
        List<B5Messages.B5SearchEntry> entries = new ArrayList<>();
        try {
            // SettingSearchIndex is mobile-only (plan §4.8, fourth option:
            // reflection + ProGuard keep). Its public static all() returns
            // the full index; every entry's fields are public final.
            Class<?> indexClass = Class.forName("com.github.moyingshi.osc.tv.ui.fragment.SettingSearchIndex");
            Method allMethod = indexClass.getMethod("all");
            Object index = allMethod.invoke(null);
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) index;
            Field titleResField = indexClass.getField("titleRes");
            Field subtitleResField = indexClass.getField("subtitleRes");
            Field destResField = indexClass.getField("destRes");
            Field pageField = indexClass.getField("page");
            Field rowIdField = indexClass.getField("rowId");
            for (Object entry : raw) {
                int titleRes = titleResField.getInt(entry);
                int subtitleRes = subtitleResField.getInt(entry);
                int destRes = destResField.getInt(entry);
                int page = pageField.getInt(entry);
                String rowId = (String) rowIdField.get(entry);
                entries.add(new B5Messages.B5SearchEntry.Builder()
                        .setTitle(activity.getString(titleRes))
                        .setSubtitle(subtitleRes == 0 ? "" : activity.getString(subtitleRes))
                        .setDest(destRes == 0 ? "" : activity.getString(destRes))
                        .setPage((long) page)
                        .setRowId(rowId == null ? "" : rowId)
                        .setFlutterAvailable(isFlutterDestination(page))
                        .build());
            }
        } catch (Exception e) {
            // The search screen degrades to an empty index rather than
            // failing the whole snapshot; the personal page is unaffected.
            entries.clear();
        }
        return new B5Messages.B5SearchScreen.Builder()
                .setHintText(activity.getString(R.string.setting_search_hint))
                .setEmptyText(activity.getString(R.string.setting_search_empty))
                .setEntries(entries)
                .build();
    }

    /** True when the destination is a migrated family member (all of
     * them now live in Flutter). The player page (2) and the subtitle page
     * (6, a top-level C1 route, not a family member) always go through
     * the host. */
    private boolean isFlutterDestination(int page) {
        switch (page) {
            case 3:
            case 4:
            case 5:
            case 7:
            case 8:
            case 9:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void openSearchEntry(Long page, String rowId) {
        int targetPage = page == null ? -1 : page.intValue();
        String targetRowId = rowId == null ? "" : rowId;
        // The subtitle page (6) is a top-level C1 route, not a family
        // member: mobile opens the Flutter screen, TV the native page
        // (via the router).
        if (targetPage == 6) {
            if (Util.isMobile()) {
                C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_SUBTITLE);
            } else {
                openHomeAt(targetPage);
            }
            activity.finish();
            return;
        }
        if (targetRowId.isEmpty()) {
            openHomeAt(targetPage);
        } else {
            // Settings-home row deep link: the row id travels through
            // the B7 host static (M15); the settings home snapshot
            // carries it and Dart performs the row on load. The old
            // SettingFragment.sPendingRow reflection write is gone.
            B7HostApiImpl.setPendingRowId(targetRowId);
            openHomeAt(1);
        }
        activity.finish();
    }

    /**
     * Opens the home at a fragment position; routing lives in
     * {@link FlutterNav#openHomeAt(android.app.Activity, int)}.
     */
    private void openHomeAt(int position) {
        FlutterNav.openHomeAt(activity, position);
    }
}
