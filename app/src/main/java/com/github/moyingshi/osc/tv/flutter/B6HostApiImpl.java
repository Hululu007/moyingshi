package com.github.moyingshi.osc.tv.flutter;

import android.content.Intent;
import android.provider.Settings;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.impl.SpeedListener;
import com.github.moyingshi.osc.tv.impl.UaListener;
import com.github.moyingshi.osc.tv.player.lut.LutSetting;
import com.github.moyingshi.osc.tv.player.mpv.MpvConfigStore;
import com.github.moyingshi.osc.tv.setting.LiveSetting;
import com.github.moyingshi.osc.tv.setting.PlaybackPerformanceSetting;
import com.github.moyingshi.osc.tv.setting.PlayerButtonSetting;
import com.github.moyingshi.osc.tv.setting.PlayerSetting;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.ui.dialog.ChoiceDialog;
import com.github.moyingshi.osc.tv.ui.dialog.LutDialog;
import com.github.moyingshi.osc.tv.ui.dialog.MpvConfigDialog;
import com.github.moyingshi.osc.tv.ui.dialog.PlaybackPerformanceDialog;
import com.github.moyingshi.osc.tv.ui.dialog.PlayerButtonConfigDialog;
import com.github.moyingshi.osc.tv.ui.dialog.PlayerKernelDialog;
import com.github.moyingshi.osc.tv.ui.dialog.VideoAspectModeDialog;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.lang.reflect.Proxy;
import java.text.DecimalFormat;

import is.xyz.mpv.MPVLib;

/**
 * B8 B6: host implementation of the mobile player settings page
 * (SettingPlayerFragment, mobile source set) as a member of the
 * `/settings` family. See docs/b8-b6-player.md.
 *
 * <p>One snapshot ({@link #getPlayerSettings}) carries all 23 visible
 * rows plus the host-computed visibility flags (pad-only, MPV-kernel
 * only, caption availability). The 16 performance rows the native page
 * hides via hidePerformanceRows() are not in the snapshot; they are
 * managed through the exo4kCompat performance dialog. Toggles and the
 * pad-live cycle are synchronous; the 13 native dialogs are {@code
 * @async} and complete only when the dialog reports a change, so Dart
 * never awaits them (B3 V1 pattern).
 *
 * <p>Three dialogs live in the mobile source set and are opened
 * reflectively through a headless fragment ({@link
 * B6PlayerHostFragment}): UaDialog and SpeedDialog (their only entry is
 * show(Fragment), and they call setUa/setSpeed on their parent
 * fragment), and PlayerOsdDialog (its Callback is a mobile-only nested
 * interface, so it is implemented with a {@link Proxy}, B3 V5 pattern).
 * LutDialog is a main-source-set class, but its Fragment overload is
 * used through the same headless fragment because the Activity overload
 * needs a PlayerManager instance, which this impl does not hold. The
 * other dialogs are main-source-set classes with FragmentActivity
 * overloads and are called directly.
 *
 * <p>Two option arrays exist only in mobile res
 * (select_background, select_pad_live_mode); they are resolved by name
 * at runtime (see {@link #mobileStringArray}), mirroring C2's
 * mobileString(), instead of through R, because a direct R reference
 * would break the leanback build. All other strings/arrays used here
 * are main-res.
 *
 * <p>Two dialog quirks, both mirrored from the native fragment:
 * SpeedDialog calls setSpeed on both its positive and its negative
 * button, so openSpeed always completes on close; PlayerOsdDialog
 * calls onApply on every toggle, so openOsd completes on the first
 * toggle while later toggles still write immediately (final state is
 * re-fetched on resume).
 */
public class B6HostApiImpl implements B6Messages.B6HostApi {

    private static final String TAG_B6_HOST = "b6_player_host";

    private final FragmentActivity activity;

    public B6HostApiImpl(FragmentActivity activity) {
        this.activity = activity;
    }

    @Override
    public B6Messages.B6PlayerSettings getPlayerSettings() {
        String[] kernel = ResUtil.getStringArray(R.array.select_player_kernel);
        String[] scale = ResUtil.getStringArray(R.array.select_scale);
        String[] caption = ResUtil.getStringArray(R.array.select_caption);
        String[] failureFallback = ResUtil.getStringArray(R.array.select_player_failure_fallback);
        String[] badgePosition = ResUtil.getStringArray(R.array.select_media_badge_position);
        String[] background = mobileStringArray("select_background");
        String[] padLiveMode = mobileStringArray("select_pad_live_mode");
        String ua = Setting.getUa();
        return new B6Messages.B6PlayerSettings.Builder()
                .setScreenTitle(activity.getString(R.string.setting_player))
                .setPlayerButtonsTitle(activity.getString(R.string.player_button_config))
                .setPlayerButtonsText(activity.getString(R.string.player_button_config_summary,
                        PlayerButtonSetting.getVisibleCount(), PlayerButtonSetting.getTotalCount()))
                .setPadLiveVisible(ResUtil.isPad())
                .setPadLiveTitle(activity.getString(R.string.player_pad_live))
                .setPadLiveText(arrayText(padLiveMode, PlayerSetting.getPadLiveMode(), ""))
                .setExo4kCompatTitle(activity.getString(R.string.player_exo_4k_compat))
                .setExo4kCompatText(safeText(PlaybackPerformanceSetting::getSummary))
                .setKernelTitle(activity.getString(R.string.player_kernel))
                .setKernelText(arrayText(kernel, PlayerSetting.getPlayer(), ""))
                .setMpvVisible(PlayerSetting.getPlayer() == PlayerSetting.MPV)
                .setMpvConfigTitle(activity.getString(R.string.player_mpv_config))
                .setMpvConfigText(safeText(MpvConfigStore::summary))
                .setMpvRenderTitle(activity.getString(R.string.player_mpv_render))
                .setMpvRenderText(getMpvRenderText())
                .setBlurayMenuTitle(activity.getString(R.string.player_bluray_menu))
                .setBlurayMenuText(getSwitch(PlayerSetting.isBlurayMenu()))
                .setScaleTitle(activity.getString(R.string.player_scale))
                .setScaleText(arrayText(scale, PlayerSetting.getScale(), ""))
                .setLutTitle(activity.getString(R.string.player_lut))
                .setLutText(safeText(LutSetting::getSummary))
                .setCaptionVisible(PlayerSetting.hasCaption())
                .setCaptionTitle(activity.getString(R.string.player_caption))
                .setCaptionText(arrayText(caption, PlayerSetting.isCaption() ? 1 : 0, ""))
                .setOsdTitle(activity.getString(R.string.player_osd))
                .setOsdText(getOsdText())
                .setOsdMediaBadgePositionTitle(activity.getString(R.string.osd_media_badge_position))
                .setOsdMediaBadgePositionText(getBadgePositionText(badgePosition))
                .setAutoPlayTitle(activity.getString(R.string.player_auto_play))
                .setAutoPlayText(getSwitch(PlayerSetting.isAutoPlay()))
                .setAutoChangeTitle(activity.getString(R.string.player_auto_change))
                .setAutoChangeText(getSwitch(PlayerSetting.isAutoChange()))
                .setDeferFlagSwitchTitle(activity.getString(R.string.player_defer_flag_switch))
                .setDeferFlagSwitchText(getSwitch(PlayerSetting.isDeferFlagSwitchPlayback()))
                .setRememberBrightnessTitle(activity.getString(R.string.player_remember_brightness))
                .setRememberBrightnessText(getSwitch(PlayerSetting.isRememberBrightness()))
                .setLiveSourceFallbackTitle(activity.getString(R.string.player_live_source_fallback))
                .setLiveSourceFallbackText(getSwitch(LiveSetting.isSourceFallback()))
                .setFailureFallbackTitle(activity.getString(R.string.player_failure_fallback))
                .setFailureFallbackText(arrayText(failureFallback, PlayerSetting.getFailureFallback(), ""))
                .setSpeedTitle(activity.getString(R.string.player_speed))
                .setSpeedText(new DecimalFormat("0.#").format(PlayerSetting.getSpeed())
                        + activity.getString(R.string.times))
                .setMusicNotificationTitle(activity.getString(R.string.player_music_notification))
                .setMusicNotificationText(getSwitch(PlayerSetting.isMusicNotification()))
                .setAudioBookNotificationTitle(activity.getString(R.string.player_audio_book_notification))
                .setAudioBookNotificationText(getSwitch(PlayerSetting.isAudioBookNotification()))
                .setBackgroundTitle(activity.getString(R.string.player_background))
                .setBackgroundText(arrayText(background, PlayerSetting.getBackground(), ""))
                .setUaTitle(activity.getString(R.string.player_ua))
                .setUaText(ua == null ? "" : ua)
                .build();
    }

    // ------------------------------------------------------------------ toggles

    @Override
    public void toggleBlurayMenu() {
        PlayerSetting.putBlurayMenu(!PlayerSetting.isBlurayMenu());
    }

    @Override
    public void toggleCaption() {
        PlayerSetting.putCaption(!PlayerSetting.isCaption());
    }

    @Override
    public void toggleAutoPlay() {
        PlayerSetting.putAutoPlay(!PlayerSetting.isAutoPlay());
    }

    @Override
    public void toggleAutoChange() {
        PlayerSetting.putAutoChange(!PlayerSetting.isAutoChange());
    }

    @Override
    public void toggleDeferFlagSwitch() {
        PlayerSetting.putDeferFlagSwitchPlayback(!PlayerSetting.isDeferFlagSwitchPlayback());
    }

    @Override
    public void toggleRememberBrightness() {
        PlayerSetting.putRememberBrightness(!PlayerSetting.isRememberBrightness());
    }

    @Override
    public void toggleLiveSourceFallback() {
        LiveSetting.putSourceFallback(!LiveSetting.isSourceFallback());
    }

    @Override
    public void toggleMusicNotification() {
        PlayerSetting.putMusicNotification(!PlayerSetting.isMusicNotification());
    }

    @Override
    public void toggleAudioBookNotification() {
        PlayerSetting.putAudioBookNotification(!PlayerSetting.isAudioBookNotification());
    }

    @Override
    public void cyclePadLiveMode() {
        String[] modes = mobileStringArray("select_pad_live_mode");
        if (modes.length == 0) return;
        int index = (PlayerSetting.getPadLiveMode() + 1) % modes.length;
        PlayerSetting.putPadLiveMode(index);
    }

    // ------------------------------------------------------------------ dialogs

    @Override
    public void openPlayerButtons(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            PlayerButtonConfigDialog.show(activity, () -> completeOnce(result, completed));
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openExo4kCompat(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            PlaybackPerformanceDialog.show(activity, () -> completeOnce(result, completed));
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openKernel(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // PlayerKernelDialog maps the display index back to the kernel
            // constant and only calls the listener when it changed, so
            // picking the current kernel completes nothing.
            PlayerKernelDialog.show(activity, PlayerSetting.getPlayer(), target -> {
                PlayerSetting.putPlayer(target);
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
    public void openMpvConfig(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            MpvConfigDialog.show(activity, () -> completeOnce(result, completed));
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openMpvRender(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            ChoiceDialog.showSingle(activity, R.string.player_mpv_render,
                    ResUtil.getStringArray(R.array.select_mpv_render), PlayerSetting.getMpvRender(), which -> {
                        PlayerSetting.putMpvRender(which);
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
    public void openScale(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // Custom aspect ratio is handled inside the dialog (it opens
            // VideoAspectRatioDialog first, then reports the mode).
            VideoAspectModeDialog.show(activity, PlayerSetting.getScale(), mode -> {
                PlayerSetting.putScale(mode);
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
    public void openLut(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // Fragment overload: the Activity overload needs a
            // PlayerManager instance, which this impl does not hold.
            LutDialog.show(playerHostFragment(), () -> completeOnce(result, completed));
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openOsd(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            // PlayerOsdDialog lives in the mobile source set; its
            // Callback is a mobile-only nested interface, so it is
            // implemented with a Proxy (B3 V5 pattern). onApply fires on
            // every toggle, so the first toggle completes the call while
            // later toggles still write immediately.
            Class<?> dialogClass = Class.forName("com.github.moyingshi.osc.tv.ui.dialog.PlayerOsdDialog");
            Class<?> callbackClass = Class.forName("com.github.moyingshi.osc.tv.ui.dialog.PlayerOsdDialog$Callback");
            Object callback = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if ("onApply".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof boolean[] checked) {
                            PlayerSetting.putDisplayChecked(checked);
                            completeOnce(result, completed);
                        }
                        return null;
                    });
            dialogClass.getMethod("show", Fragment.class, String[].class, boolean[].class, callbackClass)
                    .invoke(null, playerHostFragment(), ResUtil.getStringArray(R.array.select_player_osd),
                            PlayerSetting.getDisplayChecked(), callback);
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openOsdMediaBadgePosition(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            ChoiceDialog.showSingle(activity, R.string.osd_media_badge_position,
                    ResUtil.getStringArray(R.array.select_media_badge_position),
                    PlayerSetting.getMediaBadgeChoice(), which -> {
                        PlayerSetting.putMediaBadgeChoice(which);
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
    public void openBackground(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            String[] options = mobileStringArray("select_background");
            if (options.length == 0) throw new IllegalStateException("select_background missing");
            ChoiceDialog.showSingle(activity, R.string.player_background, options,
                    PlayerSetting.getBackground(), which -> {
                        PlayerSetting.putBackground(which);
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
    public void openFailureFallback(B6Messages.VoidResult result) {
        boolean[] completed = new boolean[1];
        try {
            ChoiceDialog.showSingle(activity, R.string.player_failure_fallback,
                    ResUtil.getStringArray(R.array.select_player_failure_fallback),
                    PlayerSetting.getFailureFallback(), which -> {
                        PlayerSetting.putFailureFallback(which);
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
    public void openUa(B6Messages.VoidResult result) {
        // Async: UaDialog lives in the mobile source set and its only
        // entry is show(Fragment) — it calls setUa() on its parent
        // fragment (UaListener, main-visible). The headless fragment
        // below plays that parent role (B3 DanmakuApiDialog pattern).
        // Closing the dialog without confirming completes nothing, and
        // Dart does not await these calls.
        boolean[] completed = new boolean[1];
        try {
            B6PlayerHostFragment host = playerHostFragment();
            host.setPendingResult(result, completed);
            Class<?> dialogClass = Class.forName("com.github.moyingshi.osc.tv.ui.dialog.UaDialog");
            dialogClass.getMethod("show", Fragment.class).invoke(null, host);
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openSpeed(B6Messages.VoidResult result) {
        // Same headless-fragment pattern as openUa. Unlike the other
        // dialogs, SpeedDialog calls setSpeed on both its positive and
        // its negative button, so this call always completes on close
        // (with the new value or the original one).
        boolean[] completed = new boolean[1];
        try {
            B6PlayerHostFragment host = playerHostFragment();
            host.setPendingResult(result, completed);
            Class<?> dialogClass = Class.forName("com.github.moyingshi.osc.tv.ui.dialog.SpeedDialog");
            dialogClass.getMethod("show", Fragment.class).invoke(null, host);
        } catch (Throwable e) {
            e.printStackTrace();
            if (!completed[0]) {
                completed[0] = true;
                result.error(e);
            }
        }
    }

    @Override
    public void openSystemCaptioning() {
        // Mirrors SettingPlayerFragment.onCaption(): only when captions
        // are enabled; otherwise a long-press is a no-op.
        if (PlayerSetting.isCaption()) {
            activity.startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        }
    }

    // ------------------------------------------------------------------ helpers

    private String getSwitch(boolean value) {
        return activity.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    /** Clamps a stored index into an array, mirroring native direct indexing for valid states. */
    private static int clampIndex(int index, int length) {
        if (length <= 0) return 0;
        if (index < 0) return 0;
        if (index >= length) return length - 1;
        return index;
    }

    private static String arrayText(String[] items, int index, String fallback) {
        if (items == null || items.length == 0) return fallback;
        return items[clampIndex(index, items.length)];
    }

    private static void completeOnce(B6Messages.VoidResult result, boolean[] completed) {
        if (!completed[0]) {
            completed[0] = true;
            result.success();
        }
    }

    /** A summary that fails degrades to the host error text instead of failing the whole snapshot. */
    private String safeText(java.util.function.Supplier<String> supplier) {
        try {
            String text = supplier.get();
            return text == null ? "" : text;
        } catch (Throwable e) {
            e.printStackTrace();
            return activity.getString(R.string.error_config_get);
        }
    }

    /**
     * Mobile-only string-array resolved by name at runtime, mirroring
     * C2's mobileString(): a direct R reference would break the
     * leanback build. Returns an empty array when absent (leanback,
     * where this screen has no entry point).
     */
    private String[] mobileStringArray(String name) {
        int id = activity.getResources().getIdentifier(name, "array", activity.getPackageName());
        return id != 0 ? activity.getResources().getStringArray(id) : new String[0];
    }

    private String getMpvRenderText() {
        String[] mpvRender = ResUtil.getStringArray(R.array.select_mpv_render);
        int render = PlayerSetting.getMpvRender();
        String text = arrayText(mpvRender, render, "");
        if (render == PlayerSetting.MPV_RENDER_VULKAN && !MPVLib.isVulkanRendererAvailable(activity)) {
            text += " (" + activity.getString(R.string.mpv_render_native_unavailable) + ")";
        }
        return text;
    }

    private String getOsdText() {
        String[] items = ResUtil.getStringArray(R.array.select_player_osd);
        boolean[] checked = PlayerSetting.getDisplayChecked();
        StringBuilder builder = new StringBuilder();
        int count = Math.min(checked.length, items.length);
        for (int i = 0; i < count; i++) {
            if (!checked[i]) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(items[i]);
        }
        return builder.length() == 0 ? activity.getString(R.string.setting_off) : builder.toString();
    }

    private String getBadgePositionText(String[] badgePosition) {
        int which = PlayerSetting.getMediaBadgeChoice();
        if (badgePosition != null && which >= 0 && badgePosition.length > which) {
            return badgePosition[which];
        }
        return activity.getString(R.string.setting_off);
    }

    private B6PlayerHostFragment playerHostFragment() {
        FragmentManager fm = activity.getSupportFragmentManager();
        Fragment existing = fm.findFragmentByTag(TAG_B6_HOST);
        if (existing instanceof B6PlayerHostFragment host) return host;
        B6PlayerHostFragment host = new B6PlayerHostFragment();
        fm.beginTransaction().add(host, TAG_B6_HOST).commitNow();
        return host;
    }

    /**
     * Headless parent fragment for the mobile-only dialogs opened
     * reflectively (see {@link #openUa}, {@link #openSpeed},
     * {@link #openOsd}) and for LutDialog's Fragment overload (see
     * {@link #openLut}). It carries no UI and lives
     * for the host Activity's lifetime; UaDialog/SpeedDialog call
     * {@link UaListener#setUa(String)} /
     * {@link SpeedListener#setSpeed(float)} on it, which write the value
     * and complete the pending call. Public static with an implicit
     * no-arg constructor so the FragmentManager can restore it after
     * process death (its pending result is null then, and the call is a
     * no-op). Only one pending dialog at a time: the page opens dialogs
     * one by one from the UI thread.
     */
    public static class B6PlayerHostFragment extends Fragment implements UaListener, SpeedListener {
        private B6Messages.VoidResult pendingResult;
        private boolean[] pendingCompleted;

        void setPendingResult(B6Messages.VoidResult result, boolean[] completed) {
            pendingResult = result;
            pendingCompleted = completed;
        }

        @Override
        public void setUa(String ua) {
            Setting.putUa(ua);
            completePending();
        }

        @Override
        public void setSpeed(float speed) {
            PlayerSetting.putSpeed(speed);
            completePending();
        }

        private void completePending() {
            if (pendingResult != null && pendingCompleted != null && !pendingCompleted[0]) {
                pendingCompleted[0] = true;
                pendingResult.success();
            }
            pendingResult = null;
            pendingCompleted = null;
        }
    }
}
