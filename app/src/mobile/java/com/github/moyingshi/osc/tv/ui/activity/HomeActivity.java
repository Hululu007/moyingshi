package com.github.moyingshi.osc.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.viewbinding.ViewBinding;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.LiveConfig;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.api.config.WallConfig;
import com.github.moyingshi.osc.tv.api.hk.HkStore;
import com.github.moyingshi.osc.tv.api.hk.HkWarmup;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.databinding.ActivityHomeBinding;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.drama.DramaSites;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.event.RefreshEvent;
import com.github.moyingshi.osc.tv.event.ServerEvent;
import com.github.moyingshi.osc.tv.event.StateEvent;
import com.github.moyingshi.osc.tv.flutter.C1ScreenActivity;
import com.github.moyingshi.osc.tv.following.FollowingPlaybackBridge;
import com.github.moyingshi.osc.tv.following.FollowingSettings;
import com.github.moyingshi.osc.tv.following.FollowingScheduler;
import com.github.moyingshi.osc.tv.glass.GlassBackdropCapture;
import com.github.moyingshi.osc.tv.glass.GlassNav;
import com.github.moyingshi.osc.tv.glass.GlassNavItem;
import com.github.moyingshi.osc.tv.glass.GlassNavState;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.lab.LabActivity;
import com.github.moyingshi.osc.tv.player.Source;
import com.github.moyingshi.osc.tv.receiver.ShortcutReceiver;
import com.github.moyingshi.osc.tv.server.Server;
import com.github.moyingshi.osc.tv.service.PlaybackService;
import com.github.moyingshi.osc.tv.setting.AutoBackupPolicy;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.theme.ThemeController;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.ui.custom.FragmentStateManager;
import com.github.moyingshi.osc.tv.ui.dialog.LocalSourceDialog;
import com.github.moyingshi.osc.tv.ui.fragment.SettingEnhanceFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingAdFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingAiFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingTmdbFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingDanmakuFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingPersonalFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingPlayerFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingSearchFragment;
import com.github.moyingshi.osc.tv.ui.fragment.SettingSubtitleFragment;
import com.github.moyingshi.osc.tv.ui.fragment.VodFragment;
import com.github.moyingshi.osc.tv.utils.CrashRestartMode;
import com.github.moyingshi.osc.tv.utils.FileChooser;
import com.github.moyingshi.osc.tv.utils.MobileWindow;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.PreviousProcessCrashRecovery;
import com.github.moyingshi.osc.tv.utils.UrlUtil;
import com.github.moyingshi.osc.tv.utils.Util;
import com.github.moyingshi.osc.tv.web.WebHomeChrome;
import com.github.moyingshi.osc.tv.web.WebHomeChromeStartup;
import com.github.moyingshi.osc.tv.web.WebHomeTarget;
import com.github.moyingshi.osc.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener, WebHomeChromeController.Host {

    public static final String EXTRA_NAV_POSITION = "nav_position";
    private static final String STATE_RETURN_VOD_FROM_ENHANCE = "returnVodFromEnhance";
    private static final String STATE_CURRENT_POSITION = "currentPosition";

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private int currentPosition;
    private boolean returnVodFromEnhance;
    private GlassNavState glassState;
    private GlassBackdropCapture glassCapture;
    private boolean navShown = true;
    /** 上次刷新底栏时底栏实验室开关的值，回首页时变了就重刷底栏。 */
    private boolean lastLabNav = true;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
        // 海阔小程序启动预热（第 7 步之十）：进程内只跑一次，只起后台线程；
        // 没装规则时不跑；失败静默，不阻塞启动。
        HkWarmup.warmUpOnStartup();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        wideWindow = MobileWindow.isWide(this);
        returnVodFromEnhance = savedInstanceState != null && savedInstanceState.getBoolean(STATE_RETURN_VOD_FROM_ENHANCE);
        currentPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_CURRENT_POSITION, 0);
        mStartupConfig = Config.vod();
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState, WebHomeChromeStartup.restore(mStartupConfig));
        mBinding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> checkWindowShape(right - left, bottom - top));
        mBinding.navigation.setOnItemSelectedListener(this);
        setNavigation();
        setupGlassNav();
        PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this));
        FollowingScheduler.ensurePeriodic(this);
        FollowingScheduler.enqueueDueNow(this);
        initFragment(savedInstanceState);
        if (savedInstanceState == null && getIntent().hasExtra(EXTRA_NAV_POSITION)) checkAction(getIntent());
        initConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding.navigation.getMenu().findItem(R.id.following).isVisible() != FollowingSettings.isEnabled()
                || mBinding.navigation.getMenu().findItem(R.id.drama).isVisible() != (DramaSites.hasDramaSites() && Setting.isShortDramaCollected())
                || lastLabNav != Setting.isLabNav()) setNavigation();
        updateFollowingBadge();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_RETURN_VOD_FROM_ENHANCE, returnVodFromEnhance);
        outState.putInt(STATE_CURRENT_POSITION, currentPosition);
        if (mChrome != null) mChrome.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        PreviousProcessCrashRecovery.checkBeforeConfig(this, () -> handleAction(intent));
    }

    private void handleAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            change(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> SettingEnhanceFragment.newInstance();
            case 4 -> SettingDanmakuFragment.newInstance();
            case 5 -> SettingPersonalFragment.newInstance();
            case 6 -> SettingSubtitleFragment.newInstance();
            case 7 -> SettingTmdbFragment.newInstance();
            case 8 -> SettingAiFragment.newInstance();
            case 9 -> SettingAdFragment.newInstance();
            case 10 -> SettingSearchFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
        else restorePosition(currentPosition);
    }

    private void restorePosition(int position) {
        setNavigation();
        syncNavigationSelection();
        changeFragment(position <= 0 ? 0 : position);
    }

    private void initConfig() {
        PreviousProcessCrashRecovery.checkBeforeConfig(this, this::loadConfig);
    }

    private void loadConfig() {
        if (CrashRestartMode.consume()) {
            checkAction(getIntent());
            StateEvent.empty();
            return;
        }
        VodConfig.get().config(mStartupConfig == null ? Config.vod() : mStartupConfig).load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                setNavigation();
                checkAction(getIntent());
            }

            /**
             * 还没有配置任何内容源（重置应用、重装或恢复出厂后的首次启动）。
             *
             * <p>这不是失败：没有地址可请求、没有失败原因、重试也永远不会成功。改动前
             * {@code BaseConfig} 把它当 {@code error("")} 抛出来，首页便显示「内容源加载失败 /
             * 重试」——一个不存在的故障加一个点了没用的按钮。这里改发「未配置」状态：
             * 不接管首页，由 {@code VodFragment} 收起状态浮层、保留底部导航；添加接口仍走
             * 既有的「设置 → 点播」管理面板（{@code HistoryDialog} 的「添加」）。
             */
            @Override
            public void noConfig() {
                resetVodChrome();
                checkAction(getIntent());
                StateEvent.noConfig();
            }

            @Override
            public void error(String msg) {
                resetVodChrome();
                checkAction(getIntent());
                // 失败发 ERROR（带原因），不发 EMPTY（EMPTY 现在渲染全屏空态）：消费方收到
                // ERROR 只收起浮层与转圈、回到原生首页，顶栏与切换内容源照常可用，失败由吐司提示。
                StateEvent.error(msg);
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        // 底栏第 5 项是实验室/本地源二选一（开关在增强设置里，默认开显示实验室）：
        // 菜单 XML 保持 6 项不动，这里只换这一项的图标、标题和点击去向。
        MenuItem localSource = mBinding.navigation.getMenu().findItem(R.id.local_source);
        localSource.setVisible(true);
        boolean labNav = Setting.isLabNav();
        localSource.setIcon(labNav ? R.drawable.ic_nav_lab : R.drawable.ic_nav_local);
        localSource.setTitle(labNav ? R.string.app_lab : R.string.local_source);
        lastLabNav = labNav;
        mBinding.navigation.getMenu().findItem(R.id.drama).setVisible(DramaSites.hasDramaSites() && Setting.isShortDramaCollected());
        mBinding.navigation.getMenu().findItem(R.id.live).setVisible(LiveConfig.hasUrl());
        mBinding.navigation.getMenu().findItem(R.id.following).setVisible(FollowingSettings.isEnabled());
        updateFollowingBadge();
        syncNavigationSelection();
        syncGlassState();
    }

    /** 底栏实验室开关变化后立即刷新底栏（增强设置里切换时调用）。 */
    public void refreshNavigation() {
        setNavigation();
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        // B8 B7: the Flutter settings home replaces the native settings
        // fragment; see docs/b8-b7-settings-home.md. Position 1 is the
        // single choke point for every entry (bottom nav, back from a
        // sub-page, VodFragment.openSetting, nav_position intents). The
        // native fragment stays for process-death restore
        // (restorePosition calls changeFragment directly and stays
        // native).
        if (position == 1) {
            C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_SETTINGS);
            return;
        }
        if (position != 3) returnVodFromEnhance = false;
        setNavigationVisible(true);
        // B8 B11: the Flutter home shell replaces the native home unless
        // the site is a fallback (hk:// miniapp or WebHome theme). The
        // native home is shown underneath first (selection synced without
        // re-entering handleNavItem, then the fragment), because
        // HomeActivity.onDestroy() stops the server and clears VodConfig,
        // so it must NOT be finished; back from the Flutter home lands on
        // the native home. See docs/b8-b11-home.md. Process-death restore
        // (restorePosition calls changeFragment directly) stays native.
        if (position == 0 && !isFlutterHomeFallback()) {
            if (mBinding.navigation.getSelectedItemId() != R.id.vod) {
                mBinding.navigation.setOnItemSelectedListener(null);
                mBinding.navigation.setSelectedItemId(R.id.vod);
                mBinding.navigation.setOnItemSelectedListener(this);
            }
            changeFragment(0);
            C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_HOME);
            return;
        }
        if (position < 2) selectNavigation(position);
        else changeFragment(position);
    }

    /**
     * B8 B11: hk:// miniapp sites and WebHome-themed sites stay native —
     * the Flutter home must not own them. Checked here and again in the
     * B11 snapshot for site switches inside the Flutter home. The
     * global WebHome theme is off by default
     * (Setting.isWebHomeThemeEnabled), so canLoad() is true only when
     * the site has its own homePage or the user enabled a theme.
     */
    private boolean isFlutterHomeFallback() {
        Site home = VodConfig.get().getHome();
        return HkStore.isMiniapp(home) || WebHomeTarget.canLoad(home);
    }

    public void setNavigationVisible(boolean visible) {
        navShown = visible;
        applyBottomBar();
    }

    @Override
    public boolean isGlassBottomBarActive() {
        return Setting.isLiquidGlass();
    }

    private void setupGlassNav() {
        glassState = GlassNav.createState();
        glassState.setListener(new GlassNavState.Listener() {
            @Override
            public void onSelect(int id) {
                if (id == R.id.vod || id == R.id.setting) {
                    // Mirror the page choice into the hidden BottomNavigationView so
                    // its checked item (the single source of selection truth) follows
                    // glass taps; otherwise the glass selection pill never moves.
                    if (mBinding.navigation.getSelectedItemId() != id) {
                        mBinding.navigation.setOnItemSelectedListener(null);
                        mBinding.navigation.setSelectedItemId(id);
                        mBinding.navigation.setOnItemSelectedListener(HomeActivity.this);
                    }
                    glassState.setSelectedId(id);
                }
                handleNavItem(id);
            }

            @Override
            public void onLiveLongPress() {
                addShortcut(mBinding.glassNav);
            }
        });
        GlassNav.bind(mBinding.glassNav, glassState);
        glassCapture = GlassNav.createCapture(mBinding.getRoot(), mBinding.glassNav, glassState);
        glassCapture.start();
        applyBottomBar();
    }

    private void applyBottomBar() {
        if (mBinding == null) return;
        boolean glass = Setting.isLiquidGlass();
        boolean chromeHides = glass && isWebHomeChromeActive() && !WebHomeChrome.NORMAL.equals(getWebHomeChromeMode());
        boolean show = navShown && !chromeHides;
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();
        if (glass) {
            mBinding.navigation.setVisibility(View.GONE);
            mBinding.glassNav.setVisibility(show ? View.VISIBLE : View.GONE);
            params.removeRule(RelativeLayout.ABOVE);
        } else if (show) {
            mBinding.glassNav.setVisibility(View.GONE);
            params.addRule(RelativeLayout.ABOVE, R.id.navigation);
            mBinding.navigation.setVisibility(View.VISIBLE);
        } else {
            mBinding.glassNav.setVisibility(View.GONE);
            params.removeRule(RelativeLayout.ABOVE);
            mBinding.navigation.setVisibility(View.GONE);
        }
        mBinding.container.setLayoutParams(params);
        // Container bottom padding for the floating glass bar is owned by
        // WebHomeChromeController.applyLayout(); just trigger an insets pass.
        mBinding.getRoot().requestApplyInsets();
        syncGlassState();
    }

    private void syncGlassState() {
        if (glassState == null || mBinding == null) return;
        List<GlassNavItem> items = new ArrayList<>();
        addGlassItem(items, R.id.vod, R.drawable.ic_nav_vod, R.string.nav_vod, false);
        addGlassItem(items, R.id.live, R.drawable.ic_nav_live, R.string.nav_live, true);
        addGlassItem(items, R.id.drama, R.drawable.ic_nav_drama, R.string.nav_drama, false);
        addGlassItem(items, R.id.following, R.drawable.ic_nav_following, R.string.nav_following, false);
        addGlassItem(items, R.id.local_source, Setting.isLabNav() ? R.drawable.ic_nav_lab : R.drawable.ic_nav_local, Setting.isLabNav() ? R.string.app_lab : R.string.local_source, false);
        addGlassItem(items, R.id.setting, R.drawable.ic_nav_setting, R.string.nav_setting, false);
        glassState.setItems(items);
        glassState.setSelectedId(mBinding.navigation.getSelectedItemId());
        glassState.setFollowingId(R.id.following);
        com.google.android.material.badge.BadgeDrawable badge = mBinding.navigation.getBadge(R.id.following);
        glassState.setFollowingBadge(badge == null ? 0 : badge.getNumber());
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        glassState.setGlassBlurDp(Setting.getGlassBlur());
        glassState.setGlassDistortionDp(Setting.getGlassDistortion());
        glassState.setGlassTranslucency(Setting.getGlassTranslucency() / 100f);
        glassState.setGlassDispersion(Setting.isGlassDispersion());
        glassState.setDarkMode(night);
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorPrimary, value, true)) glassState.setAccentColor(value.data);
    }

    private void addGlassItem(List<GlassNavItem> items, int id, int icon, int label, boolean live) {
        MenuItem item = mBinding.navigation.getMenu().findItem(id);
        if (item != null && item.isVisible()) items.add(new GlassNavItem(id, icon, getString(label), live));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                setNavigation();
                break;
            case COMMON:
                setNavigation();
                applyBottomBar();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        return handleNavItem(item.getItemId());
    }

    public boolean handleNavItem(int itemId) {
        returnVodFromEnhance = false;
        setNavigationVisible(true);
        if (itemId == R.id.setting) return changeFragment(1);
        if (itemId == R.id.lab) {
            // B8 B21: mobile opens the Flutter lab page (stacks on top,
            // no finish — back returns here); TV stays on the native
            // LabActivity.
            if (Util.isMobile()) {
                C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_LAB);
            } else {
                LabActivity.start(this);
            }
            return false;
        }
        if (itemId == R.id.local_source) {
            if (Setting.isLabNav()) {
                if (Util.isMobile()) {
                    C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_LAB);
                } else {
                    LabActivity.start(this);
                }
            }
            else LocalSourceDialog.create().show(this);
            return false;
        }
        // B8 B11: same diversion as change(0); the selection is already
        // vod here (this branch runs because the item was selected), so
        // the native fragment is shown directly, then the Flutter home.
        if (itemId == R.id.vod) {
            if (!isFlutterHomeFallback()) {
                changeFragment(0);
                C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_HOME);
                return true;
            }
            return changeFragment(0);
        }
        if (itemId == R.id.drama) {
            ShortDramaActivity.start(this);
            return false;
        }
        if (itemId == R.id.live) return openLive();
        if (itemId == R.id.following) {
            // B8 B14: the following page is Flutter on mobile. The
            // feature-disabled gate mirrors the native Activity's
            // onCreate check; the leanback entries and the B11 home
            // bottom-bar item are untouched (B11 frozen). See
            // docs/b8-b14-following.md.
            if (FollowingSettings.isEnabled()) {
                C1ScreenActivity.start(this, C1ScreenActivity.ROUTE_FOLLOWING);
                return false;
            }
            FollowingActivity.start(this, null);
            return false;
        }
        return false;
    }

    private void updateFollowingBadge() {
        if (mBinding == null || mBinding.navigation.getMenu().findItem(R.id.following) == null) return;
        if (!FollowingSettings.isEnabled()) {
            mBinding.navigation.removeBadge(R.id.following);
            return;
        }
        applyFollowingBadge(FollowingPlaybackBridge.cachedUnreadCount());
        FollowingPlaybackBridge.refreshUnreadCountAsync(unread -> {
            if (!isFinishing() && !isDestroyed()) applyFollowingBadge(unread);
        });
    }

    private void applyFollowingBadge(int unread) {
        if (mBinding == null || mBinding.navigation.getMenu().findItem(R.id.following) == null) return;
        if (!FollowingSettings.isEnabled()) {
            mBinding.navigation.removeBadge(R.id.following);
            return;
        }
        if (unread > 0) mBinding.navigation.getOrCreateBadge(R.id.following).setNumber(unread);
        else mBinding.navigation.removeBadge(R.id.following);
        syncGlassState();
    }

    private void selectNavigation(int position) {
        int itemId = position == 0 ? R.id.vod : R.id.setting;
        if (mBinding.navigation.getSelectedItemId() == itemId) changeFragment(position);
        else mBinding.navigation.setSelectedItemId(itemId);
    }

    private void syncNavigationSelection() {
        int itemId = currentPosition == 0 ? R.id.vod : R.id.setting;
        if (mBinding.navigation.getSelectedItemId() == itemId) return;
        mBinding.navigation.setOnItemSelectedListener(null);
        mBinding.navigation.setSelectedItemId(itemId);
        mBinding.navigation.setOnItemSelectedListener(this);
        syncGlassState();
    }

    private boolean changeFragment(int position) {
        boolean changed = mManager.change(position);
        if (changed) {
            currentPosition = position;
            // 设置页（1 主设置 + 2-10 子页/搜索）开壁纸模糊（设置半径，默认 10dp），
            // 首页（0）改轻微 3dp 柔化壁纸背景；只在一切换处挂一次，全部设置屏都覆盖。
            if (position >= 1) ThemeController.applyWallpaperBlur(this);
            else ThemeController.applyWallpaperBlur(this, 3);
        }
        refreshWebHomeChromeLayout();
        return changed;
    }

    private boolean isSettingSubPageVisible() {
        return mManager.isVisible(2) || mManager.isVisible(3) || mManager.isVisible(4) || mManager.isVisible(5) || mManager.isVisible(6) || mManager.isVisible(7) || mManager.isVisible(8) || mManager.isVisible(9) || mManager.isVisible(10);
    }

    private void refreshWebHomeChromeLayout() {
        if (mChrome != null) mChrome.refreshLayout();
        applyBottomBar();
    }

    private void resetVodChrome() {
        if (mChrome != null) mChrome.setLegacyToolbar(true);
    }

    public void applyWebHomeDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.applyDefault(WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site));
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (isStartupChrome(payload)) WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        if (mChrome != null) mChrome.setChrome(payload);
    }

    private boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    public void restoreWebHomeChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.restore();
    }

    public void setWebHomeLegacyToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setLegacyToolbar(visible);
    }

    public void refreshWebHomeChromeState() {
        onWebHomeChromeChanged(getWebHomeChromeMode());
    }

    private JsonObject normalWebHomeChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", "normal");
        return object;
    }

    public void openVod() {
        resetVodChrome();
        setNavigationVisible(true);
        mBinding.navigation.setSelectedItemId(R.id.vod);
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.openVodHome();
    }

    public void openEnhanceFromVod() {
        returnVodFromEnhance = true;
        setNavigationVisible(true);
        changeFragment(3);
    }

    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(0);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        applyBottomBar();
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeChrome(mode);
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeViewport(viewport);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mChrome != null) mChrome.onConfigurationChanged();
        App.post(this::checkWindowShape, 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mChrome != null) mChrome.onWindowFocusChanged(hasFocus);
    }

    private void checkWindowShape() {
        checkWindowShape(MobileWindow.getWidth(this), MobileWindow.getHeight(this));
    }

    private void checkWindowShape(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean wide = width > height;
        if (wideWindow != wide) {
            wideWindow = wide;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (!mBinding.navigation.getMenu().findItem(R.id.vod).isVisible()) {
            setNavigation();
        } else if (returnVodFromEnhance && mManager.isVisible(3)) {
            returnVodFromEnhance = false;
            change(0);
        } else if (isSettingSubPageVisible()) {
            change(1);
        } else if (mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (glassCapture != null) glassCapture.stop();
        PreviousProcessCrashRecovery.release(this);
        if (mChrome != null) mChrome.destroy();
        LiveConfig.get().clear();
        VodConfig.get().clear("mobile-home-destroy");
        if (AutoBackupPolicy.shouldRun(
                Setting.isAutoBackup(),
                Setting.hasFileAccess(),
                isFinishing(),
                isChangingConfigurations())) {
            AppDatabase.autoBackup();
        }
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
