package com.github.moyingshi.osc.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
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
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.databinding.ActivityHomeBinding;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.drama.DramaSites;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.event.RefreshEvent;
import com.github.moyingshi.osc.tv.event.ServerEvent;
import com.github.moyingshi.osc.tv.event.StateEvent;
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
import com.github.moyingshi.osc.tv.utils.ResUtil;
import com.github.moyingshi.osc.tv.utils.UrlUtil;
import com.github.moyingshi.osc.tv.utils.Util;
import com.github.moyingshi.osc.tv.web.WebHomeChrome;
import com.github.moyingshi.osc.tv.web.WebHomeChromeStartup;
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
    /** 底栏滚动折叠态：首页内容上滑累计超阈值时收起底栏（与 navShown 并联进 applyBottomBar）。 */
    private boolean navScrollHidden;
    /** 折叠防抖累计（px）：同一方向滚动才累加，换向清零；上滑 24dp 收起、下滑 12dp 出现。 */
    private int navScrollAccumPx;
    /** applyBottomBar 上次算出的 show 逻辑态（动画进行中也已更新，实际可见性在动画结束时才落定）。 */
    private boolean navBarShown = true;
    /** applyBottomBar 上次的玻璃形态；形态切换时立即切视图，不做滑动。 */
    private boolean navBarGlass;
    /** 首页悬浮按钮（navHomeFab）的逻辑态：只在底栏被滚动收起且实际已 GONE 时才出现。 */
    private boolean navHomeFabShown;
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
        mBinding.navHomeFab.setOnClickListener(v -> change(0));
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
        if (position != 3) returnVodFromEnhance = false;
        setNavigationVisible(true);
        if (position < 2) selectNavigation(position);
        else changeFragment(position);
    }

    public void setNavigationVisible(boolean visible) {
        navShown = visible;
        if (visible) {
            // 切 tab / 点 tab / 回首页：清掉滚动折叠态（悬浮按钮由 applyBottomBar 同步收起）。
            navScrollHidden = false;
            navScrollAccumPx = 0;
        }
        applyBottomBar();
    }

    /** 底栏是否逻辑上显示（供 WebHomeChromeController.applyLayout 判断：滚动收起时它不得把普通底栏强行拉回 VISIBLE）。 */
    @Override
    public boolean isBottomBarShown() {
        return navShown && !navScrollHidden;
    }

    /**
     * 首页内容竖向滚动回调（滚动源见 VodFragment/TypeFragment：当前可见页的列表与 WebView）。
     * 上滑累计超 24dp 收起底栏，下滑累计超 12dp 或回到顶部时恢复；小幅抖动不来回闪。
     */
    public void onContentScrolled(int dy, boolean atTop) {
        if (mBinding == null || !navShown) return;
        if (atTop) {
            navScrollAccumPx = 0;
            if (navScrollHidden) setNavScrollHidden(false);
            return;
        }
        if (dy > 0) {
            if (navScrollAccumPx < 0) navScrollAccumPx = 0;
            if (navScrollHidden) return;
            navScrollAccumPx += dy;
            if (navScrollAccumPx >= ResUtil.dp2px(24)) setNavScrollHidden(true);
        } else if (dy < 0) {
            if (navScrollAccumPx > 0) navScrollAccumPx = 0;
            if (!navScrollHidden) return;
            navScrollAccumPx += dy;
            if (navScrollAccumPx <= -ResUtil.dp2px(12)) setNavScrollHidden(false);
        }
    }

    private void setNavScrollHidden(boolean hidden) {
        if (navScrollHidden == hidden) return;
        navScrollHidden = hidden;
        navScrollAccumPx = 0;
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
        boolean show = navShown && !navScrollHidden && !chromeHides;
        View active = glass ? mBinding.glassNav : mBinding.navigation;
        View inactive = glass ? mBinding.navigation : mBinding.glassNav;
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();
        if (glass != navBarGlass) {
            // 玻璃开/关切换：立即切视图，不做滑动（形态变了，滑的参照也没了）。
            cancelNavBarAnimations();
            navBarGlass = glass;
            navBarShown = show;
            inactive.setVisibility(View.GONE);
            inactive.setTranslationY(0);
            active.setTranslationY(0);
            active.setVisibility(show ? View.VISIBLE : View.GONE);
            applyContainerAbove(params, show, glass);
            finishApplyBottomBar(params);
            return;
        }
        if (show == navBarShown) {
            // 逻辑态未变：保证实际可见性与逻辑一致（如动画中途被打断、或初次布局）。
            inactive.setVisibility(View.GONE);
            if (active.getVisibility() != (show ? View.VISIBLE : View.GONE) || active.getTranslationY() != 0) {
                cancelNavBarAnimations();
                active.setTranslationY(0);
                active.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            applyContainerAbove(params, show, glass);
            finishApplyBottomBar(params);
            return;
        }
        navBarShown = show;
        inactive.setVisibility(View.GONE);
        inactive.setTranslationY(0);
        if (show) {
            // 出现：先恢复 VISIBLE + ABOVE 规则，再从位移滑回 0。
            cancelNavBarAnimations();
            applyContainerAbove(params, true, glass);
            mBinding.container.setLayoutParams(params);
            active.setVisibility(View.VISIBLE);
            active.setTranslationY(getNavBarHeight(active, glass));
            active.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> active.setTranslationY(0)).start();
            finishApplyBottomBar(params);
        } else {
            // 收起：220ms 滑到底后再置 GONE 并去掉 ABOVE 规则（内容随即占满）。
            // 注意：动画落定前不触发 insets pass——WebHomeChromeController.applyLayout()
            // 会按逻辑态把普通底栏直接置 GONE，动画会被拦腰截断；落定后再补一次。
            cancelNavBarAnimations();
            active.animate().translationY(getNavBarHeight(active, glass)).setDuration(220).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (navBarShown || mBinding == null) return;
                        active.setVisibility(View.GONE);
                        active.setTranslationY(0);
                        RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();
                        applyContainerAbove(p, false, glass);
                        mBinding.container.setLayoutParams(p);
                        updateNavHomeFab();
                        mBinding.getRoot().requestApplyInsets();
                    }).start();
            // ABOVE 规则在动画结束时才去掉，这里只同步悬浮按钮（底栏还没 GONE，它不会出现）。
            updateNavHomeFab();
            syncGlassState();
        }
    }

    /** 玻璃形态下 container 永远不挂 ABOVE（玻璃栏浮在内容上）；普通形态只在底栏显示时挂。 */
    private void applyContainerAbove(RelativeLayout.LayoutParams params, boolean show, boolean glass) {
        if (glass || !show) params.removeRule(RelativeLayout.ABOVE);
        else params.addRule(RelativeLayout.ABOVE, R.id.navigation);
    }

    private void finishApplyBottomBar(RelativeLayout.LayoutParams params) {
        mBinding.container.setLayoutParams(params);
        // Container bottom padding for the floating glass bar is owned by
        // WebHomeChromeController.applyLayout(); just trigger an insets pass.
        mBinding.getRoot().requestApplyInsets();
        syncGlassState();
        updateNavHomeFab();
    }

    private void cancelNavBarAnimations() {
        mBinding.navigation.animate().cancel();
        mBinding.glassNav.animate().cancel();
    }

    private int getNavBarHeight(View bar, boolean glass) {
        int height = bar.getHeight();
        return height > 0 ? height : ResUtil.dp2px(glass ? 88 : 56);
    }

    /**
     * 首页悬浮按钮（navHomeFab）：只在底栏被滚动收起、且底栏实际已 GONE 时才出现；
     * 底栏一出现它就淡出。出现时带淡入 + 缩放（200ms），与底栏滑动同步。
     * 位置错开首页 fragment 里的 FAB（top/browse/link，56dp + 16dp 间距）：
     * 普通底栏时底边距 88dp；玻璃底栏时 container 有 88dp 底边距，fragment FAB 被顶到
     * 104dp 高处，底边距取 176dp，始终比它们高一格。
     */
    private void updateNavHomeFab() {
        if (mBinding == null) return;
        boolean glass = Setting.isLiquidGlass();
        boolean chromeHides = glass && isWebHomeChromeActive() && !WebHomeChrome.NORMAL.equals(getWebHomeChromeMode());
        View active = glass ? mBinding.glassNav : mBinding.navigation;
        boolean show = navScrollHidden && navShown && !chromeHides && active.getVisibility() == View.GONE;
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.navHomeFab.getLayoutParams();
        int margin = ResUtil.dp2px(glass ? 176 : 88);
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin;
            mBinding.navHomeFab.setLayoutParams(params);
        }
        if (show == navHomeFabShown) return;
        navHomeFabShown = show;
        View fab = mBinding.navHomeFab;
        fab.animate().cancel();
        if (show) {
            fab.setVisibility(View.VISIBLE);
            fab.setAlpha(0f);
            fab.setScaleX(0.8f);
            fab.setScaleY(0.8f);
            fab.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        fab.setAlpha(1f);
                        fab.setScaleX(1f);
                        fab.setScaleY(1f);
                    }).start();
        } else {
            fab.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(180).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (!navHomeFabShown) {
                            fab.setVisibility(View.GONE);
                            fab.setAlpha(1f);
                            fab.setScaleX(1f);
                            fab.setScaleY(1f);
                        }
                    }).start();
        }
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
            LabActivity.start(this);
            return false;
        }
        if (itemId == R.id.local_source) {
            if (Setting.isLabNav()) LabActivity.start(this);
            else LocalSourceDialog.create().show(this);
            return false;
        }
        if (itemId == R.id.vod) return changeFragment(0);
        if (itemId == R.id.drama) {
            ShortDramaActivity.start(this);
            return false;
        }
        if (itemId == R.id.live) return openLive();
        if (itemId == R.id.following) {
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
