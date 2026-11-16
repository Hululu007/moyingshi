package com.github.moyingshi.osc.tv.flutter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.github.moyingshi.osc.tv.R;

import io.flutter.embedding.android.FlutterFragment;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.dart.DartExecutor;

/**
 * B8 C1: host Activity for the migrated screens (About, subtitle settings).
 *
 * <p>Same cached-engine pattern as {@link C0ShellActivity}: this Activity
 * owns one cached {@link FlutterEngine}, created (if absent) before
 * {@code super.onCreate()} so the FragmentManager can restore the fragment
 * after process death; the Pigeon handler is registered before Dart starts.
 * The engine is destroyed only when the Activity is really finishing.
 *
 * <p>Difference from C0: the initial route comes from the launch intent
 * ({@link #EXTRA_ROUTE}, {@link #ROUTE_ABOUT} or {@link #ROUTE_SUBTITLE}).
 * Dart asks the host for it in main() via
 * {@code C1HostApi.getInitialRoute()} instead of relying on the cached
 * engine fragment's initialRoute API; see docs/b8-c1-about-subtitle.md.
 *
 * <p>Mobile only: declared in the mobile manifest, no launcher entry.
 * Entry points are the mobile SettingFragment rows, behind the C1 flag
 * (Setting.isFlutterC1Enabled(), default false = native screens).
 */
public class C1ScreenActivity extends AppCompatActivity {

    public static final String EXTRA_ROUTE = "b8_c1_route";
    public static final String ROUTE_ABOUT = "/about";
    public static final String ROUTE_SUBTITLE = "/subtitle";

    private static final String TAG_FLUTTER_FRAGMENT = "b8_c1_flutter_fragment";
    private static final String ENGINE_ID = "b8_c1_engine";

    private FlutterFragment flutterFragment;

    /** Launches a C1 screen; callers check the C1 flag first. */
    public static void start(Activity activity, String route) {
        Intent intent = new Intent(activity, C1ScreenActivity.class);
        intent.putExtra(EXTRA_ROUTE, route);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Before super.onCreate(): the FragmentManager restores the
        // FlutterFragment during super.onCreate() after process death, and
        // the fragment looks the cached engine up by ID at that point.
        ensureFlutterEngine();
        super.onCreate(savedInstanceState);
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

    private void ensureFlutterEngine() {
        if (FlutterEngineCache.getInstance().get(ENGINE_ID) != null) {
            return;
        }
        String route = getIntent().getStringExtra(EXTRA_ROUTE);
        if (route == null) route = ROUTE_ABOUT;
        FlutterEngine engine = new FlutterEngine(this);
        // Register the Pigeon handler before Dart starts executing.
        C1Messages.C1HostApi.setUp(engine.getDartExecutor(), new C1HostApiImpl(this, route));
        engine.getDartExecutor().executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault());
        FlutterEngineCache.getInstance().put(ENGINE_ID, engine);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A cached engine outlives its fragment; destroy it only when this
        // Activity is really finishing (rotation keeps it for reuse).
        if (isFinishing()) {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(ENGINE_ID);
            if (engine != null) {
                FlutterEngineCache.getInstance().remove(ENGINE_ID);
                engine.destroy();
            }
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (flutterFragment != null) {
            flutterFragment.onPostResume();
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
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
