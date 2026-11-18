package com.github.moyingshi.osc.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.databinding.ActivityWebHomeShellBinding;
import com.github.moyingshi.osc.tv.flutter.C1ScreenActivity;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.theme.ThemeController;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.utils.Util;
import com.github.moyingshi.osc.tv.web.HomeWebController;
import com.github.moyingshi.osc.tv.web.WebHomeChrome;
import com.github.moyingshi.osc.tv.web.WebHomeChromeOptions;
import com.github.moyingshi.osc.tv.web.WebHomeChromeStartup;
import com.github.moyingshi.osc.tv.web.WebHomeTarget;
import com.github.moyingshi.osc.tv.web.WebHomeViewport;
import com.google.gson.JsonObject;

/**
 * Minimal native fallback shell for WebHome home sites (delete-native plan
 * §6 R1 / §7 D6, option b). The Flutter module has no WebView plugin, so a
 * WebHome-themed home site cannot render in the Flutter home; this single
 * Activity hosts the existing {@link HomeWebController} plus the minimal
 * chrome the controller's Listener contract needs, and nothing else.
 *
 * <p>Entry is WebHome home sites only: the B11 host opens the shell through
 * {@link #start(Activity, Site)} when the Flutter home snapshot reports
 * {@code isWebHome} (the same {@link WebHomeTarget#canLoad} predicate the
 * native home used for its P6 fallback). Non-WebHome sites never reach
 * this Activity, and the native {@code VodFragment}/{@code HomeActivity}
 * home form is untouched.
 *
 * <p>Backing out of the shell returns to the Flutter home underneath. A
 * dismissed-identity guard stops the Flutter home from reopening the shell
 * for the same site until the site (or its theme target) changes: without
 * it, the home's resume reload would reopen the shell the user just left.
 * Rotation does not set the guard (the manifest declares orientation
 * {@code configChanges}, so the Activity is not recreated) and starting
 * the shell for a different identity clears it.
 *
 * <p>Flavor safety: this class lives in the main source set and references
 * only main-source classes, so the leanback build compiles it. It is
 * declared in the mobile manifest only, and the only mobile-only API it
 * touches ({@code SiteDialog}) is reached reflectively behind a
 * {@link Util#isMobile()} gate, mirroring the B11 host dialog pattern.
 */
public class WebHomeShellActivity extends BaseActivity implements HomeWebController.Listener {

    private static final String EXTRA_SITE_KEY = "siteKey";
    private static final String STATE_MODE = "webHomeChromeMode";
    private static final String STATE_PREVIOUS_MODE = "webHomeChromePreviousMode";

    /** SiteDialog, opened reflectively: its mobile/leanback APIs differ. */
    private static final String DIALOG_SITE = "com.github.moyingshi.osc.tv.ui.dialog.SiteDialog";

    /** Identity of the site whose shell the user backed out of; "" when none. */
    private static String sDismissedIdentity = "";

    /** True while a shell instance is alive; stops a second shell stacking. */
    private static boolean sOpen;

    private ActivityWebHomeShellBinding mBinding;
    private HomeWebController controller;
    private String siteKey = "";
    private String identity = "";
    private WindowInsetsCompat insets;
    private WebHomeChromeOptions options;
    private WebHomeViewport viewport;
    private String mode;
    private String previousMode;
    private int safeBottomMax;

    /**
     * Opens the shell for a WebHome home site. Returns false (and starts
     * nothing) when the site cannot load a WebHome theme, when the shell
     * is already open, or when the user already backed out of the shell
     * for this exact theme identity.
     */
    public static boolean start(Activity activity, Site site) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (sOpen) return false;
        WebHomeTarget target = site == null ? null : WebHomeTarget.resolve(site);
        if (target == null) return false;
        String identity = target.identity(site.getKey());
        if (identity.equals(sDismissedIdentity)) return false;
        sDismissedIdentity = "";
        Intent intent = new Intent(activity, WebHomeShellActivity.class);
        intent.putExtra(EXTRA_SITE_KEY, site.getKey());
        activity.startActivity(intent);
        return true;
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityWebHomeShellBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        siteKey = value(getIntent().getStringExtra(EXTRA_SITE_KEY));
        Site site = VodConfig.get().getSite(siteKey);
        WebHomeTarget target = site == null ? null : WebHomeTarget.resolve(site);
        if (target == null) {
            finish();
            return;
        }
        identity = target.identity(site.getKey());
        sOpen = true;
        // Mirrors VodFragment.loadHome: remember the startup chrome for
        // this config + home site so the next cold start restores it.
        WebHomeChromeStartup.remember(VodConfig.get().getConfig(), site);
        initChrome(savedInstanceState);
        mBinding.getRoot().setFitsSystemWindows(false);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (view, windowInsets) -> {
            insets = windowInsets;
            dispatchViewport();
            return windowInsets;
        });
        controller = new HomeWebController(this, mBinding.web, this);
        controller.setViewport(getViewport());
        boolean accepted;
        try {
            accepted = controller.load(site);
        } catch (RuntimeException ignored) {
            accepted = false;
        }
        if (!accepted) finish();
        else ViewCompat.requestApplyInsets(mBinding.getRoot());
    }

    /** Chrome state init, mirroring WebHomeChromeController's constructor. */
    private void initChrome(Bundle savedInstanceState) {
        JsonObject startupChrome = WebHomeChromeStartup.restore(VodConfig.get().getConfig());
        if (!Setting.isWebHomeFullscreen()) {
            mode = WebHomeChrome.NORMAL;
            previousMode = WebHomeChrome.NORMAL;
            options = WebHomeChromeOptions.normal();
        } else if (savedInstanceState == null && startupChrome != null) {
            options = WebHomeChromeOptions.from(startupChrome, WebHomeChrome.EDGE);
            mode = WebHomeChrome.normalize(options.mode, WebHomeChrome.EDGE);
            previousMode = WebHomeChrome.IMMERSIVE.equals(mode) ? WebHomeChrome.NORMAL : mode;
        } else {
            mode = savedInstanceState == null ? WebHomeChrome.NORMAL
                    : WebHomeChrome.normalize(savedInstanceState.getString(STATE_MODE), WebHomeChrome.NORMAL);
            previousMode = savedInstanceState == null ? WebHomeChrome.NORMAL
                    : WebHomeChrome.normalize(savedInstanceState.getString(STATE_PREVIOUS_MODE), WebHomeChrome.NORMAL);
            options = WebHomeChromeOptions.from(null, mode);
        }
        viewport = WebHomeViewport.EMPTY.withChrome(mode, isImmersiveMode());
        applySystemBars();
        applyCutoutMode();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_MODE, mode);
        outState.putString(STATE_PREVIOUS_MODE, previousMode);
    }

    @Override
    public void onWebLoading() {
        mBinding.loading.setVisibility(View.VISIBLE);
    }

    @Override
    public void onWebReady() {
        mBinding.loading.setVisibility(View.GONE);
        if (controller != null) controller.requestFocus("shell-ready");
    }

    @Override
    public void onWebError() {
        // Native falls back to the grid; here the Flutter home underneath
        // is the fallback. The dismissed guard is set in onDestroy, so
        // the home does not reopen the shell for this site immediately.
        finish();
    }

    @Override
    public void setToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            apply(WebHomeChromeOptions.normal());
            return;
        }
        apply(visible ? WebHomeChromeOptions.normal() : WebHomeChromeOptions.legacyImmersive());
    }

    @Override
    public void applyDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            apply(WebHomeChromeOptions.normal());
            return;
        }
        apply(WebHomeChromeOptions.from(
                WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site), WebHomeChrome.EDGE));
    }

    @Override
    public void setChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            apply(WebHomeChromeOptions.normal());
            return;
        }
        if (isStartupChrome(payload)) {
            WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        }
        apply(WebHomeChromeOptions.from(payload, mode));
    }

    @Override
    public void restoreChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            apply(WebHomeChromeOptions.normal());
            return;
        }
        apply(WebHomeChromeOptions.from(null, previousMode));
    }

    @Override
    public WebHomeViewport getViewport() {
        return viewport == null ? WebHomeViewport.EMPTY : viewport;
    }

    @Override
    public void openVod() {
        // The theme asks for the VOD home: the Flutter home underneath is it.
        finish();
    }

    @Override
    public void openSite() {
        openSiteDialog();
    }

    @Override
    public void openSetting() {
        // Native switched HomeActivity to its settings tab; here the
        // Flutter settings screen is the equivalent (start-then-finish).
        C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_SETTINGS);
        finish();
    }

    @Override
    protected void onBackInvoked() {
        // Native order (HomeActivity): chrome restore first, then the
        // WebView history, then leave. Leaving reveals the Flutter home.
        if (consumeChromeBack()) return;
        if (controller == null || !controller.handleBack()) finish();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        safeBottomMax = 0;
        ViewCompat.requestApplyInsets(mBinding.getRoot());
        dispatchViewport();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isImmersiveMode()) Util.hideSystemUI(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) controller.onResume();
    }

    @Override
    protected void onPause() {
        if (controller != null) controller.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sOpen = false;
        if (isFinishing() && !identity.isEmpty()) sDismissedIdentity = identity;
        if (isImmersiveMode()) Util.showSystemUI(this);
        if (controller != null) controller.destroy();
        super.onDestroy();
    }

    private void apply(WebHomeChromeOptions next) {
        if (!Setting.isWebHomeFullscreen()) next = WebHomeChromeOptions.normal();
        String nextMode = WebHomeChrome.normalize(next.mode, WebHomeChrome.NORMAL);
        if (WebHomeChrome.IMMERSIVE.equals(nextMode) && !WebHomeChrome.IMMERSIVE.equals(mode)) previousMode = mode;
        if (!WebHomeChrome.IMMERSIVE.equals(nextMode)) previousMode = nextMode;
        mode = nextMode;
        options = next;
        applySystemBars();
        applyCutoutMode();
        dispatchViewport();
    }

    private boolean consumeChromeBack() {
        if (!isImmersiveMode()) return false;
        if (WebHomeChromeOptions.RESTORE_NONE.equals(options.restoreAffordance)) return false;
        restoreChrome();
        return true;
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(options.topScrim);
        window.setNavigationBarColor(options.bottomScrim);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(useDarkIcons(options.statusBarStyle));
        controller.setAppearanceLightNavigationBars(useDarkIcons(options.navigationBarStyle));
        if (isImmersiveMode()) Util.hideSystemUI(this);
        else Util.showSystemUI(this);
    }

    private void applyCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.layoutInDisplayCutoutMode = WebHomeChrome.hidesNativeChrome(mode)
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        getWindow().setAttributes(params);
    }

    private void dispatchViewport() {
        viewport = buildViewport();
        if (controller != null) controller.setViewport(viewport);
    }

    private WebHomeViewport buildViewport() {
        WebHomeViewport current = WebHomeViewport.from(insets, mode, safeBottomMax);
        safeBottomMax = Math.max(safeBottomMax, current.getSafeBottom());
        return WebHomeViewport.from(insets, mode, safeBottomMax);
    }

    private boolean isImmersiveMode() {
        return WebHomeChrome.IMMERSIVE.equals(mode);
    }

    private boolean useDarkIcons(String style) {
        if (WebHomeChromeOptions.STYLE_DARK.equals(style)) return true;
        if (WebHomeChromeOptions.STYLE_LIGHT.equals(style)) return false;
        return !ThemeController.isNight(this);
    }

    private static boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Site switcher from the theme (native: SiteDialog over VodFragment).
     * The dialog is mobile-only with flavor-split APIs, so it is opened
     * reflectively like the B11 host dialogs; when it dismisses after a
     * site change, the shell finishes and the Flutter home reloads for
     * the new site (reopening the shell if it is a WebHome site too).
     */
    private void openSiteDialog() {
        if (!Util.isMobile()) return;
        FragmentManager fm = getSupportFragmentManager();
        FragmentManager.FragmentLifecycleCallbacks callbacks = new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentDestroyed(@NonNull FragmentManager manager, @NonNull Fragment fragment) {
                if (!DIALOG_SITE.equals(fragment.getClass().getName())) return;
                manager.unregisterFragmentLifecycleCallbacks(this);
                onSiteDialogDismissed();
            }
        };
        fm.registerFragmentLifecycleCallbacks(callbacks, false);
        try {
            java.lang.Class<?> dialogClass = java.lang.Class.forName(DIALOG_SITE);
            Object dialog = dialogClass.getMethod("create").invoke(null);
            dialog = dialogClass.getMethod("change").invoke(dialog);
            dialogClass.getMethod("show", FragmentManager.class, String.class).invoke(dialog, fm, null);
        } catch (Throwable e) {
            fm.unregisterFragmentLifecycleCallbacks(callbacks);
        }
    }

    private void onSiteDialogDismissed() {
        if (isFinishing() || isDestroyed()) return;
        Site home = VodConfig.get().getHome();
        if (home == null || !home.getKey().equals(siteKey)) finish();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
