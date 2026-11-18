package com.github.moyingshi.osc.tv.flutter;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.github.catvod.net.OkHttp;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.config.LiveConfig;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.api.config.WallConfig;
import com.github.moyingshi.osc.tv.bean.Config;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.event.ConfigEvent;
import com.github.moyingshi.osc.tv.event.ServerEvent;
import com.github.moyingshi.osc.tv.following.FollowingScheduler;
import com.github.moyingshi.osc.tv.impl.Callback;
import com.github.moyingshi.osc.tv.lab.LabFilePicker;
import com.github.moyingshi.osc.tv.player.Source;
import com.github.moyingshi.osc.tv.server.Server;
import com.github.moyingshi.osc.tv.setting.AutoBackupPolicy;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.utils.CrashRestartMode;
import com.github.moyingshi.osc.tv.utils.FileChooser;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.PermissionUtil;
import com.github.moyingshi.osc.tv.utils.PreviousProcessCrashRecovery;
import com.github.moyingshi.osc.tv.utils.UrlUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import io.flutter.embedding.android.FlutterFragment;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.StandardMethodCodec;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B8 C1/C2/B2/B3/B4: host Activity for the migrated screens (About, subtitle
 * settings, History, Keep, and the settings family).
 *
 * <p>Same cached-engine pattern as {@link C0ShellActivity}: this Activity
 * owns one cached {@link FlutterEngine}, created (if absent) before
 * {@code super.onCreate()} so the FragmentManager can restore the fragment
 * after process death; the Pigeon handler is registered before Dart starts.
 * The engine is destroyed only when the Activity is really finishing.
 *
 * <p>Difference from C0: the initial route comes from the launch intent
 * ({@link #EXTRA_ROUTE}, {@link #ROUTE_ABOUT}, {@link #ROUTE_SUBTITLE},
 * {@link #ROUTE_HISTORY}, {@link #ROUTE_KEEP} or {@link #ROUTE_SETTINGS}
 * with a {@code ?member=} query). Dart asks the host for
 * it in main() via {@code C1HostApi.getInitialRoute()} instead of relying
 * on the cached engine fragment's initialRoute API; see
 * docs/b8-c1-about-subtitle.md. The C2 history screen, the B2 keep
 * screen and the B3 settings family share this engine and route mechanism
 * (one C1 engine screen alive at a time; see docs/b8-c2-history.md,
 * docs/b8-b2-keep.md and docs/b8-b3-settings-a.md). The B7 settings home
 * is the family's initial member (see docs/b8-b7-settings-home.md). The
 * B8 category list shares this engine too (route {@link #ROUTE_CATEGORY}
 * with a {@code ?key=&typeId=&type=} query; see docs/b8-b8-category.md).
 * The B9 search input page shares this engine too (route
 * {@link #ROUTE_SEARCH} with a {@code ?keyword=&siteKey=&pic=&wallPic=}
 * query; see docs/b8-b9-search.md). The B10 aggregate search page
 * shares this engine too (route {@link #ROUTE_COLLECT} with a
 * {@code ?keyword=&currentSite=&siteKey=&group=&pic=&wallPic=&hrCid=&hrKey=&hrTargetCid=}
 * query, or pushed from the B9 page inside the family; see
 * docs/b8-b10-collect.md). The B11 home shell shares this engine too
 * (route {@link #ROUTE_HOME}, no parameters; see
 * docs/b8-b11-home.md). The B14 following page shares this engine too
 * (route {@link #ROUTE_FOLLOWING}, no parameters; see
 * docs/b8-b14-following.md). The B15 local-file browser shares this
 * engine too (route {@link #ROUTE_LOCAL} with a
 * {@code ?selectDir=&readMode=} query; see docs/b8-b15-local.md) as
 * does the app-branding page (route {@link #ROUTE_BRANDING}, no
 * parameters). The B16 viewing-report page shares this engine too
 * (route {@link #ROUTE_REPORT} with an optional {@code ?range=} query;
 * see docs/b8-b16-report.md). The B17 TMDB person page shares this
 * engine too (route {@link #ROUTE_PERSON} with a
 * {@code ?personId=&name=&subtitle=&profile=&department=&biography=&siteKey=&detailMode=}
 * query; see docs/b8-b17-person.md). The B18 widget browse page shares
 * this engine too (route {@link #ROUTE_WIDGETS} with a
 * {@code ?widgetId=} query; see docs/b8-b18-widget.md). The B19 plugin
 * manager page shares this engine too (route {@link #ROUTE_PLUGINS},
 * no parameters; see docs/b8-b19-plugin.md). The B20 Hk miniapp
 * manager page shares this engine too (route
 * {@link #ROUTE_HK_MANAGER}, no parameters; see docs/b8-b20-hk.md)
 * — its impl lives in the mobile source set and is registered
 * through the B20Registrar reflection indirection (plan §4.8
 * option 5), because the Hk APIs are mobile-only. The B21 lab
 * list + detail pages share this engine too (route
 * {@link #ROUTE_LAB}, no parameters; the detail page is an
 * in-family page, not a second route; see docs/b8-b21-lab.md).
 * The B22 short-drama theater grid and history list share this
 * engine too (routes {@link #ROUTE_DRAMA_THEATER} and
 * {@link #ROUTE_DRAMA_HISTORY}, no parameters, two independent
 * routes; the native ShortDramaActivity stays the entry and
 * play-stream host, see docs/b8-b22-drama.md).
 *
 * <p>Mobile only: declared in the mobile manifest. Since B8
 * delete-native phase 3 it is the launcher home (see
 * docs/b8-delete-native-plan.md §3): the launcher aliases target it,
 * it owns the startup/teardown sequence the native HomeActivity used
 * to run, and a launch intent without {@link #EXTRA_ROUTE} shows
 * {@link #ROUTE_HOME}. Every migrated screen still enters through
 * {@link #start} with an explicit route.
 *
 * <p>B8 relaunch mechanism (see docs/b8-c1-relaunch-mechanism.md): one
 * cached engine serves many routes, but Dart reads the initial route only
 * once, when the engine is created. A second C1ScreenActivity started
 * while the engine lives would otherwise show the first screen's route
 * (the G2 race), and a finishing activity's onDestroy would destroy an
 * engine a newer screen already attached to. So the foreground activity
 * asserts its route to Dart over a raw MethodChannel (Dart resets the
 * root Navigator, ignoring a repeated assertion of the same route), and
 * the engine is destroyed only when the last live C1ScreenActivity
 * finishes. Existing call sites need no changes.
 */
public class C1ScreenActivity extends AppCompatActivity {

    public static final String EXTRA_ROUTE = "b8_c1_route";
    /** Literal value of the mobile {@code HomeActivity.EXTRA_NAV_POSITION} (plan §2.3, M14). */
    private static final String EXTRA_NAV_POSITION = "nav_position";
    public static final String ROUTE_ABOUT = "/about";
    public static final String ROUTE_SUBTITLE = "/subtitle";
    public static final String ROUTE_HISTORY = "/history";
    public static final String ROUTE_KEEP = "/keep";
    /** Settings family route; append "?member=home|tmdb|ai|ad|danmaku|enhance|personal|search|player". */
    public static final String ROUTE_SETTINGS = "/settings";
    /** Category list route; append "?key=&typeId=&type=&hrCid=&hrKey=&hrTargetCid=" (type = URL-encoded Class JSON). */
    public static final String ROUTE_CATEGORY = "/category";
    /** Search input page route; append "?keyword=&siteKey=&pic=&wallPic=" (all values URL-encoded). */
    public static final String ROUTE_SEARCH = "/search";
    /** Aggregate search results route; append "?keyword=&currentSite=&siteKey=&group=&pic=&wallPic=&hrCid=&hrKey=&hrTargetCid=" (all values URL-encoded). */
    public static final String ROUTE_COLLECT = "/collect";
    /** Home shell route (no parameters). */
    public static final String ROUTE_HOME = "/home";
    /** Following page route (no parameters). */
    public static final String ROUTE_FOLLOWING = "/following";
    /** Local-file browser route; append "?selectDir=&readMode=" (both URL-encoded booleans). */
    public static final String ROUTE_LOCAL = "/local";
    /** App-branding page route (no parameters). */
    public static final String ROUTE_BRANDING = "/branding";
    /** Viewing-report page route; append an optional "?range=" (a ViewingReportRange name, default ALL). */
    public static final String ROUTE_REPORT = "/report";
    /** TMDB person page route; append "?personId=&name=&subtitle=&profile=&department=&biography=&siteKey=&detailMode=" (all values URL-encoded). */
    public static final String ROUTE_PERSON = "/person";
    /** Widget browse page route; append "?widgetId=" (URL-encoded). */
    public static final String ROUTE_WIDGETS = "/widgets";
    /** Plugin manager page route (no parameters). */
    public static final String ROUTE_PLUGINS = "/plugins";
    /** Hk miniapp manager page route (no parameters). */
    public static final String ROUTE_HK_MANAGER = "/hk-manager";
    /** Lab list + detail family route (no parameters; the detail page is an in-family page). */
    public static final String ROUTE_LAB = "/lab";
    /** Short-drama theater grid route (no parameters; scheme B, see docs/b8-b22-drama.md). */
    public static final String ROUTE_DRAMA_THEATER = "/drama-theater";
    /** Short-drama history list route (no parameters; scheme B, see docs/b8-b22-drama.md). */
    public static final String ROUTE_DRAMA_HISTORY = "/drama-history";

    private static final String TAG = "C1ScreenActivity";
    private static final String TAG_FLUTTER_FRAGMENT = "b8_c1_flutter_fragment";
    private static final String ENGINE_ID = "b8_c1_engine";

    /**
     * B8 relaunch mechanism: a raw MethodChannel (no Pigeon contract —
     * the C1 contract is frozen and this is a host-to-Dart push, not a
     * request/response call) over which the foreground activity tells
     * Dart which route to show. The Dart handler lives in
     * flutter_module/lib/main.dart and resets the root Navigator.
     */
    private static final String RELAUNCH_CHANNEL = "com.github.moyingshi.osc.tv/c1_relaunch";
    private static final String RELAUNCH_METHOD_SHOW_ROUTE = "showRoute";

    /**
     * B8 relaunch mechanism: live C1ScreenActivity instances. The cached
     * engine is destroyed only when the last one finishes (see
     * onDestroy): in start-then-finish navigation the new Activity is
     * created before the old one is destroyed, so destroying the engine
     * in the old Activity's onDestroy would kill the new screen. Weak
     * keys so a missed remove() can never leak an Activity; removal in
     * onDestroy is still explicit. Touched only on the main thread.
     */
    private static final Set<C1ScreenActivity> sLiveActivities =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * B8 relaunch mechanism: the route last asserted to Dart — the
     * creation route of the current engine, or the last route sent via
     * {@link #assertRouteIfNeeded()}. Null when no engine exists.
     */
    private static String sAssertedRoute;

    /**
     * B8 relaunch mechanism: ownership token of the Activity that last
     * asserted the route. A new Activity instance re-asserts even for
     * the same route string (rotation), and Dart ignores the repeat;
     * without the token, a buried Activity returning to the foreground
     * could not tell that a newer screen redirected Dart elsewhere.
     */
    private static Object sRouteOwnerToken;

    /**
     * B8 delete-native phase 3 (M1): the config load sequence runs once
     * per process, however many C1 screens are created.
     */
    private static final AtomicBoolean sConfigLoadStarted = new AtomicBoolean(false);

    private FlutterFragment flutterFragment;

    /** This Activity's route (from {@link #EXTRA_ROUTE}, default {@link #ROUTE_HOME}). */
    private String mRoute;

    /** Per-instance ownership token for the route assertion (see {@link #assertRouteIfNeeded()}). */
    private final Object mRouteToken = new Object();

    /** B19 plugin manager impl, kept so the directory-picker launcher result can reach it. */
    private B19HostApiImpl b19Impl;

    /**
     * B8 B19: the directory-picker launcher for the plugin manager
     * page. Registered as a field (same shape as
     * PluginManagerActivity.mDirectoryLauncher): the host impl asks
     * this Activity to launch the FileChooser directory picker, and
     * the result is forwarded to the B19 impl, which imports the
     * picked directory on its worker. Only the B19 page uses it.
     */
    private final ActivityResultLauncher<Intent> pluginDirectoryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    (ActivityResult result) -> {
                        if (b19Impl != null) b19Impl.onDirectoryPicked(result);
                    });

    /** Launches the FileChooser directory picker for the B19 page (native FileChooser fallback branch). */
    public void launchPluginDirectoryPicker() {
        FileChooser.from(pluginDirectoryLauncher).showDirectory();
    }

    /**
     * B8 B21: the directory-picker launcher for the lab page's
     * local-source scan (native FileChooser fallback branch, same
     * shape as the B19 launcher above). The result is forwarded to
     * the B21 impl bound to this engine, which runs the background
     * scan. Only the B21 page uses it.
     */
    private final ActivityResultLauncher<Intent> labLocalSourceLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    (ActivityResult result) -> {
                        FlutterEngine cached = FlutterEngineCache.getInstance().get(ENGINE_ID);
                        if (cached != null) {
                            B21HostApiImpl.onLocalSourceDirPicked(cached.getDartExecutor(), result);
                        }
                    });

    /** Launches the FileChooser directory picker for the B21 page (native FileChooser fallback branch). */
    public void launchLabLocalSourceDir() {
        FileChooser.from(labLocalSourceLauncher).showDirectory();
    }

    /** Launches a C1/C2/B2 screen; callers check the matching flag first. */
    public static void start(Activity activity, String route) {
        Intent intent = new Intent(activity, C1ScreenActivity.class);
        intent.putExtra(EXTRA_ROUTE, route);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // B8 delete-native phase 3 (M10): the manifest declares
        // Theme.Splash; switch to the app theme before super.onCreate(),
        // exactly like the native home activity did.
        setTheme(R.style.Theme_App);
        mRoute = getIntent().getStringExtra(EXTRA_ROUTE);
        if (mRoute == null) mRoute = ROUTE_HOME;
        // B8 relaunch: remember whether the engine already existed
        // before ensureFlutterEngine() runs; a reused engine is already
        // showing another route and must be redirected below.
        boolean engineReused = FlutterEngineCache.getInstance().get(ENGINE_ID) != null;
        // Before super.onCreate(): the FragmentManager restores the
        // FlutterFragment during super.onCreate() after process death, and
        // the fragment looks the cached engine up by ID at that point.
        ensureFlutterEngine();
        super.onCreate(savedInstanceState);
        sLiveActivities.add(this);
        // B8 delete-native phase 3 (plan §2.1): this Activity is the
        // launcher home now, so it owns the startup sequence the native
        // HomeActivity used to run (M1/M3/M4/M5/M6/M7/M8), in the same
        // order. The config load is once-guarded; the scheduler calls
        // are idempotent; the warm-up is once-per-process internally.
        warmUpHk();
        PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this));
        FollowingScheduler.ensurePeriodic(this);
        FollowingScheduler.enqueueDueNow(this);
        initConfig();
        EventBus.getDefault().register(this);
        if (savedInstanceState == null && getIntent().hasExtra(EXTRA_NAV_POSITION)) {
            checkAction(getIntent());
        }
        if (engineReused) {
            // Early hint: tell Dart to switch routes before the
            // fragment's first frame if possible. Deliberately
            // unrecorded — onPostResume() performs the recorded
            // assertion, so a message lost while Dart was still
            // starting is sent again there (Dart ignores the repeat).
            sendRouteToDart(mRoute);
        }
        // B8 host rebind (see docs/b8-host-rebind-fix.md): on a reused
        // engine the impls registered at engine creation still hold the
        // first Activity instance. Rebind all of them to this live
        // instance so native dialogs and pickers keep working after
        // navigation or rotation. On a fresh engine
        // ensureFlutterEngine() already registered everything above,
        // so this runs only when the engine was reused.
        if (engineReused) {
            FlutterEngine cached = FlutterEngineCache.getInstance().get(ENGINE_ID);
            if (cached != null) registerHostApis(cached.getDartExecutor());
        }
        setContentView(R.layout.activity_b8_c1);
        FragmentManager fragmentManager = getSupportFragmentManager();
        flutterFragment = (FlutterFragment) fragmentManager.findFragmentByTag(TAG_FLUTTER_FRAGMENT);
        if (flutterFragment == null) {
            flutterFragment = FlutterFragment.withCachedEngine(ENGINE_ID).build();
            fragmentManager.beginTransaction()
                    .add(R.id.fragment_container, flutterFragment, TAG_FLUTTER_FRAGMENT)
                    .commit();
        }
    }

    // ------------------------------------------------------------------
    // B8 delete-native phase 3: home lifecycle moved from the native
    // HomeActivity (docs/b8-delete-native-plan.md §2). The code below
    // mirrors HomeActivity's onCreate/initView/initConfig/handlers,
    // minus the native navigation (setNavigation, fragment switching),
    // the glass nav and the WebHome chrome, which die with the native
    // UI. The StateEvent posts are gone too (VodFragment was the only
    // subscriber); the Flutter screens re-fetch snapshots on resume.
    // ------------------------------------------------------------------

    /**
     * M7: Hk miniapp startup warm-up. HkWarmup is a mobile-only class,
     * so it is reached by FQCN-string reflection (B20 precedent); on
     * leanback this is a silent no-op. Failures are swallowed: the
     * warm-up must never block startup.
     */
    private void warmUpHk() {
        try {
            Class<?> warmup = Class.forName("com.github.moyingshi.osc.tv.api.hk.HkWarmup");
            warmup.getMethod("warmUpOnStartup").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // Leanback build: no Hk warm-up.
        } catch (Throwable e) {
            Log.w(TAG, "HkWarmup failed", e);
        }
    }

    /** M1 + M8: config load, gated by the crash-recovery decision. */
    private void initConfig() {
        PreviousProcessCrashRecovery.checkBeforeConfig(this, this::loadConfig);
    }

    /**
     * M1: the config load sequence (VodConfig + LiveConfig + WallConfig),
     * run once per process. The crash-restart branch mirrors HomeActivity:
     * the pending intent is handled and no load runs.
     */
    private void loadConfig() {
        if (CrashRestartMode.consume()) {
            // Crash restart: no load runs in this process (HomeActivity
            // semantics — it loaded once per process). Mark the guard so
            // a later C1 creation cannot load either; the one-shot flag
            // ("skip_config_once") means the process's one load.
            sConfigLoadStarted.set(true);
            checkAction(getIntent());
            return;
        }
        // Once guard (plan §2.1): rotation no longer recreates this
        // Activity (orientation configChanges), and the guard covers
        // every other re-entry. Auxiliary D3 predicate: skip when both
        // configs already hold data (loaded through another entry in
        // this process); the VodConfig predicate is the same one
        // VodConfig.isLoaded() uses (getSites() is null-safe).
        if (!sConfigLoadStarted.compareAndSet(false, true)
                || (!VodConfig.get().getSites().isEmpty() && LiveConfig.hasLoadedLives())) {
            return;
        }
        VodConfig.get().config(Config.vod()).load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    /** M1: load callback — pending-intent handling + error toast only. */
    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void noConfig() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
                Notify.show(msg);
            }
        };
    }

    /** M5 + M8: intent handling, gated by the crash-recovery decision. */
    private void checkAction(Intent intent) {
        PreviousProcessCrashRecovery.checkBeforeConfig(this, () -> handleAction(intent));
    }

    /**
     * M5: the four intent inputs, moved from HomeActivity.handleAction.
     * nav_position maps to a C1 route (M14, FlutterNav) and is asserted
     * to Dart; SEND/VIEW push video; ACTION_SEARCH opens the Flutter
     * search page (FlutterNav.openSearch never finishes the caller).
     */
    private void handleAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            mRoute = FlutterNav.homeRouteForPosition(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
            assertRouteIfNeeded();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            FlutterNav.pushVideo(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) FlutterNav.openSearch(this, keyword);
        }
    }

    /** M5: VIEW data routing, moved verbatim from HomeActivity.checkType. */
    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            FlutterNav.pushVideo(this, intent.getData().toString());
        }
    }

    /** M5: .m3u/file live entry, moved verbatim from HomeActivity.loadLive. */
    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    /** M5: opens live TV (FlutterNav reflection; LiveActivity is flavor-parallel). */
    private void openLive() {
        FlutterNav.startLive(this);
    }

    /**
     * M6: only the foreground C1 screen handles bus events. Stacked C1
     * screens (the scheme-B stack) are all registered, so an ungated
     * handler would push video / open search twice; the route-assertion
     * owner token identifies the foreground Activity (see
     * {@link #assertRouteIfNeeded()}).
     */
    private boolean isForegroundC1() {
        return sRouteOwnerToken == mRouteToken;
    }

    /**
     * M6: ConfigEvent, moved from HomeActivity. Only the BOOT branch
     * survives: VOD/COMMON only refreshed the native navigation (the
     * Flutter screens re-fetch their snapshots on resume); BOOT opens
     * live TV on mobile boot-to-live.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (!isForegroundC1()) return;
        if (event.type() == ConfigEvent.Type.BOOT) {
            FlutterNav.startLive(this);
        }
    }

    /**
     * M6: ServerEvent, moved from HomeActivity. PUSH pushes video;
     * SEARCH opens the Flutter search page.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (!isForegroundC1()) return;
        if (event.type() == ServerEvent.Type.PUSH) FlutterNav.pushVideo(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) FlutterNav.openSearch(this, event.text());
    }

    /**
     * B8 host rebind (see docs/b8-host-rebind-fix.md): registers every
     * Pigeon HostApi impl on the given messenger, bound to this Activity
     * instance. Called from two places:
     * <ul>
     * <li>{@link #ensureFlutterEngine()} on engine creation, before Dart
     * starts executing (the original registration site); and</li>
     * <li>{@link #onCreate} on every creation against a reused engine,
     * so the impls never keep holding the first Activity instance after
     * navigation or rotation — a stopped/destroyed Activity breaks every
     * call that touches its FragmentManager (native dialogs) or its
     * activity-result launchers, silently (the impls swallow the
     * exception and answer the Pigeon error, which Dart ignores).</li>
     * </ul>
     * The B20/B21/B22 register methods keep one impl per engine and only
     * update its Activity reference; the other impls are stateless
     * request handlers, so a fresh instance per rebind is equivalent
     * (the rotation-during-dialog edge case is recorded in the doc).
     * This also refreshes {@link #b19Impl}, so the plugin directory
     * picker result reaches the live impl on reused engines too.
     */
    private void registerHostApis(@NonNull BinaryMessenger messenger) {
        // C1: mRoute is set by onCreate() before either call site; Dart
        // reads it via C1HostApi.getInitialRoute() only when the isolate
        // starts, so a rebind's fresh route value is harmless.
        C1Messages.C1HostApi.setUp(messenger, new C1HostApiImpl(this, mRoute));
        // B8 C2: the history screen shares this engine, so register
        // C2HostApi too. See docs/b8-c2-history.md.
        C2Messages.C2HostApi.setUp(messenger, new C2HostApiImpl(this));
        // B8 B2: the keep screen shares this engine, so register
        // B2HostApi too. See docs/b8-b2-keep.md.
        B2Messages.B2HostApi.setUp(messenger, new B2HostApiImpl(this));
        // B8 B3: the settings family shares this engine, so register
        // B3HostApi too. See docs/b8-b3-settings-a.md.
        B3Messages.B3HostApi.setUp(messenger, new B3HostApiImpl(this));
        // B8 B4: the enhance settings page joins the family, so register
        // B4HostApi too. See docs/b8-b4-enhance.md.
        B4Messages.B4HostApi.setUp(messenger, new B4HostApiImpl(this));
        // B8 B5: the personal settings page and the settings search join
        // the family, so register B5HostApi too. See docs/b8-b5-personal.md.
        B5Messages.B5HostApi.setUp(messenger, new B5HostApiImpl(this));
        // B8 B7: the settings home is the family's initial member, so
        // register B7HostApi too. See docs/b8-b7-settings-home.md.
        B7Messages.B7HostApi.setUp(messenger, new B7HostApiImpl(this));
        // B8 B6: the player settings page joins the family, so register
        // B6HostApi too. See docs/b8-b6-player.md.
        B6Messages.B6HostApi.setUp(messenger, new B6HostApiImpl(this));
        // B8 B8: the category list shares this engine, so register
        // B8HostApi too. See docs/b8-b8-category.md.
        B8Messages.B8HostApi.setUp(messenger, new B8HostApiImpl(this));
        // B8 B9: the search input page shares this engine, so register
        // B9HostApi too. See docs/b8-b9-search.md.
        B9Messages.B9HostApi.setUp(messenger, new B9HostApiImpl(this));
        // B8 B10: the aggregate search page shares this engine, so
        // register B10HostApi too. See docs/b8-b10-collect.md.
        B10Messages.B10HostApi.setUp(messenger, new B10HostApiImpl(this));
        // B8 B11: the home shell shares this engine, so register
        // B11HostApi too. See docs/b8-b11-home.md.
        B11Messages.B11HostApi.setUp(messenger, new B11HostApiImpl(this));
        // B8 B12: the home blocks A share this engine (no new route —
        // they live inside /home), so register B12HostApi too. See
        // docs/b8-b12-home-a.md.
        B12Messages.B12HostApi.setUp(messenger, new B12HostApiImpl(this));
        // B8 B13: the home blocks B (JS widget sections) share this
        // engine too (no new route — they live inside /home), so
        // register B13HostApi as well. See docs/b8-b13-home-b.md.
        B13Messages.B13HostApi.setUp(messenger, new B13HostApiImpl(this));
        // B8 B14: the following page shares this engine too (route
        // ROUTE_FOLLOWING, no parameters), so register B14HostApi as
        // well. See docs/b8-b14-following.md.
        B14Messages.B14HostApi.setUp(messenger, new B14HostApiImpl(this));
        // B8 B15: the local-file browser and the app-branding page
        // share this engine too (routes ROUTE_LOCAL with a
        // ?selectDir=&readMode= query and ROUTE_BRANDING), so register
        // B15HostApi as well. See docs/b8-b15-local.md.
        B15Messages.B15HostApi.setUp(messenger, new B15HostApiImpl(this));
        // B8 B16: the viewing-report page shares this engine too (route
        // ROUTE_REPORT with an optional ?range= query), so register
        // B16HostApi as well. See docs/b8-b16-report.md.
        B16Messages.B16HostApi.setUp(messenger, new B16HostApiImpl(this));
        // B8 B17: the TMDB person page shares this engine too (route
        // ROUTE_PERSON with a ?personId=&name=&...&detailMode= query),
        // so register B17HostApi as well. See docs/b8-b17-person.md.
        B17Messages.B17HostApi.setUp(messenger, new B17HostApiImpl(this));
        // B8 B18: the widget browse page shares this engine too (route
        // ROUTE_WIDGETS with a ?widgetId= query), so register
        // B18HostApi as well. See docs/b8-b18-widget.md.
        B18Messages.B18HostApi.setUp(messenger, new B18HostApiImpl(this));
        // B8 B19: the plugin manager page shares this engine too (route
        // ROUTE_PLUGINS, no parameters), so register B19HostApi as
        // well. The impl instance is kept for the directory-picker
        // launcher result. See docs/b8-b19-plugin.md.
        b19Impl = new B19HostApiImpl(this);
        B19Messages.B19HostApi.setUp(messenger, b19Impl);
        // B8 B20: the Hk miniapp manager page shares this engine too
        // (route ROUTE_HK_MANAGER, no parameters). Its impl lives in
        // the mobile source set (Hk APIs are mobile-only), so it is
        // registered through the B20Registrar reflection indirection
        // (plan §4.8 option 5); on leanback this is a silent no-op.
        // See docs/b8-b20-hk.md.
        registerB20(messenger);
        // B8 B21: the lab list + detail pages share this engine too
        // (route ROUTE_LAB, no parameters; the detail page is an
        // in-family page). The impl lives in the main source set, so
        // it registers directly; the static registry keeps one impl
        // per engine and rebinds it on rotation (B20 shape).
        // See docs/b8-b21-lab.md.
        B21HostApiImpl.register(messenger, this);
        // B8 B22: the short-drama theater grid and history list share
        // this engine too (routes ROUTE_DRAMA_THEATER and
        // ROUTE_DRAMA_HISTORY, no parameters, two independent
        // routes). Same static-registry shape as B21 (one impl per
        // engine, rebind on rotation). See docs/b8-b22-drama.md.
        B22HostApiImpl.register(messenger, this);
    }

    private void ensureFlutterEngine() {
        if (FlutterEngineCache.getInstance().get(ENGINE_ID) != null) {
            return;
        }
        FlutterEngine engine = new FlutterEngine(this);
        // Register the Pigeon handlers before Dart starts executing.
        registerHostApis(engine.getDartExecutor());
        engine.getDartExecutor().executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault());
        FlutterEngineCache.getInstance().put(ENGINE_ID, engine);
        // B8 relaunch: the fresh engine will show mRoute (Dart reads it
        // via getInitialRoute), so record it as asserted by this
        // Activity; later assertions compare against it.
        sAssertedRoute = mRoute;
        sRouteOwnerToken = mRouteToken;
    }

    /**
     * B8 relaunch mechanism: fire-and-forget route push to Dart. Used
     * for the early hint in onCreate() on engine reuse; the recorded
     * assertion lives in {@link #assertRouteIfNeeded()}. A no-op when
     * no engine exists.
     */
    private void sendRouteToDart(@NonNull String route) {
        FlutterEngine engine = FlutterEngineCache.getInstance().get(ENGINE_ID);
        if (engine == null) return;
        new MethodChannel(engine.getDartExecutor(), RELAUNCH_CHANNEL, StandardMethodCodec.INSTANCE)
                .invokeMethod(RELAUNCH_METHOD_SHOW_ROUTE, route);
    }

    /**
     * B8 relaunch mechanism: makes Dart show this Activity's route when
     * Dart is not already showing it for this Activity.
     *
     * <p>Sends when the asserted route differs from this Activity's
     * route (a newer screen redirected Dart, or this Activity is new
     * on a reused engine) or when another Activity instance owns the
     * assertion (rotation: the new instance re-asserts the same route
     * string, and Dart ignores the repeat, preserving family Navigator
     * depth). Skips when this Activity already owns the assertion for
     * its own route, so a plain resume after a native dialog or
     * sub-flow never disturbs the screen.
     */
    private void assertRouteIfNeeded() {
        if (mRoute.equals(sAssertedRoute) && sRouteOwnerToken == mRouteToken) {
            return;
        }
        sAssertedRoute = mRoute;
        sRouteOwnerToken = mRouteToken;
        sendRouteToDart(mRoute);
    }

    /**
     * B8 B20: registers the Hk miniapp manager API through the mobile
     * registrar indirection. The registrar class exists only in the
     * mobile source set; on leanback Class.forName fails and this is
     * a silent no-op. The first call per engine creates the impl and
     * runs the Pigeon setUp; later calls (onCreate after rotation)
     * only rebind it to the current Activity.
     */
    private void registerB20(@NonNull BinaryMessenger messenger) {
        try {
            Class<?> registrar = Class.forName("com.github.moyingshi.osc.tv.flutter.B20Registrar");
            registrar.getMethod("register", BinaryMessenger.class, AppCompatActivity.class)
                    .invoke(null, messenger, this);
        } catch (ClassNotFoundException ignored) {
            // Leanback build: no Hk miniapp manager, nothing to register.
        } catch (Exception e) {
            Log.w(TAG, "B20 register failed", e);
        }
    }

    /** B8 B20: drops the impl for this engine (engine destroy). No-op on leanback. */
    private void unregisterB20(@NonNull BinaryMessenger messenger) {
        try {
            Class<?> registrar = Class.forName("com.github.moyingshi.osc.tv.flutter.B20Registrar");
            registrar.getMethod("unregister", BinaryMessenger.class)
                    .invoke(null, messenger);
        } catch (ClassNotFoundException ignored) {
            // Leanback build: nothing was registered.
        } catch (Exception e) {
            Log.w(TAG, "B20 unregister failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // M6: manual unregister (this Activity is not a BaseActivity
        // subclass, so there is no automatic registration lifecycle).
        EventBus.getDefault().unregister(this);
        // M8: drop crash-recovery steps parked for this Activity, before
        // anything below can run them against a dead screen (mirrors
        // HomeActivity.onDestroy, which releases unconditionally first).
        PreviousProcessCrashRecovery.release(this);
        sLiveActivities.remove(this);
        // A cached engine outlives its fragment; destroy it only when
        // this Activity is really finishing AND no other C1 screen is
        // alive. In start-then-finish navigation the new Activity is
        // created (and added to sLiveActivities) before the old one is
        // destroyed, so destroying the engine here would kill the new
        // screen (G2). Rotation keeps the engine (isFinishing() is
        // false) as before.
        if (isFinishing() && sLiveActivities.isEmpty()) {
            // M2 + M9: home teardown, moved from HomeActivity.onDestroy
            // in the same order, under the same guard as the engine
            // destroy: rotation (orientation configChanges) and
            // start-then-finish navigation must never run it. The load
            // guard resets here: the configs below are cleared, so the
            // next home creation in this process must load again
            // (launcher re-entry after exit); without the reset the
            // once-guard would leave the relaunched home empty.
            sConfigLoadStarted.set(false);
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
            FlutterEngine engine = FlutterEngineCache.getInstance().get(ENGINE_ID);
            if (engine != null) {
                // B8 B20: drop the Hk impl for this engine (its worker
                // is shut down, the dead Activity reference released).
                unregisterB20(engine.getDartExecutor());
                // B8 B21: drop the lab impl for this engine too.
                B21HostApiImpl.unregister(engine.getDartExecutor());
                // B8 B22: drop the short-drama impl for this engine too.
                B22HostApiImpl.unregister(engine.getDartExecutor());
                FlutterEngineCache.getInstance().remove(ENGINE_ID);
                engine.destroy();
            }
            // B8 relaunch: no engine, no assertion; the next engine
            // records its creation route in ensureFlutterEngine().
            sAssertedRoute = null;
            sRouteOwnerToken = null;
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // B8 relaunch: a buried Activity coming back to the foreground
        // re-asserts its route — a newer C1 screen may have redirected
        // Dart to another route while this one was underneath (the
        // scheme-B stack: Flutter home, native drama page, Flutter
        // drama page). A no-op when this Activity already owns the
        // assertion for its own route.
        assertRouteIfNeeded();
        if (flutterFragment != null) {
            flutterFragment.onPostResume();
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // B8 delete-native phase 3 (§3.3): singleTop re-entry. The
        // action is handled like HomeActivity.onNewIntent (crash-gated);
        // the route is updated from EXTRA_ROUTE, or reset to HOME for a
        // launcher re-entry. VIEW/SEND/SEARCH intents keep the current
        // route: they push another activity on top, and the screen
        // underneath must not reset. A nav_position extra is owned by
        // handleAction (it may run later, behind the crash gate), so it
        // suppresses the HOME default here.
        boolean hasNavPosition = intent.hasExtra(EXTRA_NAV_POSITION);
        checkAction(intent);
        String route = intent.getStringExtra(EXTRA_ROUTE);
        if (route != null) {
            mRoute = route;
        } else if (!hasNavPosition
                && (Intent.ACTION_MAIN.equals(intent.getAction()) || intent.getAction() == null)) {
            mRoute = ROUTE_HOME;
        }
        assertRouteIfNeeded();
        if (flutterFragment != null) {
            flutterFragment.onNewIntent(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (flutterFragment != null) {
            flutterFragment.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (flutterFragment != null) {
            flutterFragment.onRequestPermissionsResult(requestCode, permissions, grantResults);
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (flutterFragment != null) {
            flutterFragment.onActivityResult(requestCode, resultCode, data);
        }
        // B8 B21: the lab file picker (LabFilePicker SAF codes from
        // 3000) and the lab import / local-config picks (B21
        // REQUEST_IMPORT / REQUEST_LOCAL_CONFIG) both arrive here as
        // classic results; forward them to the B21 impl bound to this
        // engine. LabFilePicker ignores unknown codes.
        LabFilePicker.onActivityResult(requestCode, resultCode, data);
        FlutterEngine cached = FlutterEngineCache.getInstance().get(ENGINE_ID);
        if (cached != null) {
            B21HostApiImpl.onActivityResult(cached.getDartExecutor(), requestCode, resultCode, data);
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (flutterFragment != null) {
            flutterFragment.onUserLeaveHint();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (flutterFragment != null) {
            flutterFragment.onTrimMemory(level);
        }
    }
}
