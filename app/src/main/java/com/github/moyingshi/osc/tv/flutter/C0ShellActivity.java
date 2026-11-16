package com.github.moyingshi.osc.tv.flutter;

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
 * B8 C0: minimal host Activity for a {@link FlutterFragment} (add-to-app
 * shell validation). Declared only in the mobile manifest, behind a
 * clearly labeled experimental launcher entry; see docs/b8-c0-shell.md.
 *
 * <p>Engine strategy (per the official FlutterFragment docs): this Activity
 * owns one cached {@link FlutterEngine}. The engine is created (if absent)
 * before {@code super.onCreate()} so the FragmentManager can restore the
 * fragment after process death; the Pigeon handler is registered on the
 * engine's messenger before Dart starts executing. The engine is destroyed
 * only when the Activity is really finishing, not on rotation.
 *
 * <p>OS signals are forwarded to the fragment exactly as the official docs
 * show, with null guards for the window before the fragment is attached.
 */
public class C0ShellActivity extends AppCompatActivity {

    private static final String TAG_FLUTTER_FRAGMENT = "b8_c0_flutter_fragment";
    private static final String ENGINE_ID = "b8_c0_engine";

    private FlutterFragment flutterFragment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Before super.onCreate(): the FragmentManager restores the
        // FlutterFragment during super.onCreate() after process death, and
        // the fragment looks the cached engine up by ID at that point.
        ensureFlutterEngine();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_b8_c0);
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
        FlutterEngine engine = new FlutterEngine(this);
        // Register the Pigeon handler before Dart starts executing.
        C0Messages.C0HostApi.setUp(engine.getDartExecutor(), new C0HostApiImpl());
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
    protected void onUserLeaveHint() {
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
