package com.github.moyingshi.osc.tv.flutter;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.github.moyingshi.osc.tv.R;

import io.flutter.embedding.android.FlutterFragment;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BinaryMessenger;

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

    private static final String TAG = "C0ShellActivity";
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
        // B8 B20: rebind the Hk miniapp manager impl to this Activity
        // instance (same rotation reason as C1ScreenActivity). No-op
        // on leanback.
        FlutterEngine cached = FlutterEngineCache.getInstance().get(ENGINE_ID);
        if (cached != null) registerB20(cached.getDartExecutor());
        // B8 B21: rebind the lab impl to this Activity instance (same
        // rotation reason as B20).
        if (cached != null) B21HostApiImpl.register(cached.getDartExecutor(), this);
        // B8 B22: rebind the short-drama impl to this Activity
        // instance (same rotation reason as B20/B21).
        if (cached != null) B22HostApiImpl.register(cached.getDartExecutor(), this);
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
        // B8 C1: the C0 screen also hosts the C1 flag switch, so this engine
        // registers C1HostApi too (route "/" — only the flag methods are
        // ever called on this engine). See docs/b8-c1-about-subtitle.md.
        C1Messages.C1HostApi.setUp(engine.getDartExecutor(), new C1HostApiImpl(this, "/"));
        // B8 C2: the C0 screen also hosts the C2 flag switch, so this engine
        // registers C2HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-c2-history.md.
        C2Messages.C2HostApi.setUp(engine.getDartExecutor(), new C2HostApiImpl(this));
        // B8 B2: the C0 screen also hosts the B2 flag switch, so this engine
        // registers B2HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b2-keep.md.
        B2Messages.B2HostApi.setUp(engine.getDartExecutor(), new B2HostApiImpl(this));
        // B8 B3: the C0 screen also hosts the B3 flag switch, so this engine
        // registers B3HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b3-settings-a.md.
        B3Messages.B3HostApi.setUp(engine.getDartExecutor(), new B3HostApiImpl(this));
        // B8 B4: the C0 screen also hosts the B4 flag switch, so this engine
        // registers B4HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b4-enhance.md.
        B4Messages.B4HostApi.setUp(engine.getDartExecutor(), new B4HostApiImpl(this));
        // B8 B5: the C0 screen also hosts the B5 flag switch, so this engine
        // registers B5HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b5-personal.md.
        B5Messages.B5HostApi.setUp(engine.getDartExecutor(), new B5HostApiImpl(this));
        // B8 B7: the C0 screen also hosts the B7 flag switch, so this engine
        // registers B7HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b7-settings-home.md.
        B7Messages.B7HostApi.setUp(engine.getDartExecutor(), new B7HostApiImpl(this));
        // B8 B6: the C0 screen also hosts the B6 flag switch, so this engine
        // registers B6HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b6-player.md.
        B6Messages.B6HostApi.setUp(engine.getDartExecutor(), new B6HostApiImpl(this));
        // B8 B8: the C0 screen also hosts the B8 flag switch, so this engine
        // registers B8HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b8-category.md.
        B8Messages.B8HostApi.setUp(engine.getDartExecutor(), new B8HostApiImpl(this));
        // B8 B9: the C0 screen also hosts the B9 flag switch, so this engine
        // registers B9HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b9-search.md.
        B9Messages.B9HostApi.setUp(engine.getDartExecutor(), new B9HostApiImpl(this));
        // B8 B10: the C0 screen also hosts the B10 flag switch, so this engine
        // registers B10HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b10-collect.md.
        B10Messages.B10HostApi.setUp(engine.getDartExecutor(), new B10HostApiImpl(this));
        // B8 B11: the C0 screen also hosts the B11 flag switch, so this engine
        // registers B11HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b11-home.md.
        B11Messages.B11HostApi.setUp(engine.getDartExecutor(), new B11HostApiImpl(this));
        // B8 B12: the C0 screen also hosts the B12 flag switch, so this engine
        // registers B12HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b12-home-a.md.
        B12Messages.B12HostApi.setUp(engine.getDartExecutor(), new B12HostApiImpl(this));
        // B8 B13: the C0 screen also hosts the B13 flag switch, so this engine
        // registers B13HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b13-home-b.md.
        B13Messages.B13HostApi.setUp(engine.getDartExecutor(), new B13HostApiImpl(this));
        // B8 B14: the C0 screen also hosts the B14 flag switch, so this engine
        // registers B14HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b14-following.md.
        B14Messages.B14HostApi.setUp(engine.getDartExecutor(), new B14HostApiImpl(this));
        // B8 B15: the C0 screen also hosts the B15 flag switch, so this engine
        // registers B15HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b15-local.md.
        B15Messages.B15HostApi.setUp(engine.getDartExecutor(), new B15HostApiImpl(this));
        // B8 B16: the C0 screen also hosts the B16 flag switch, so this engine
        // registers B16HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b16-report.md.
        B16Messages.B16HostApi.setUp(engine.getDartExecutor(), new B16HostApiImpl(this));
        // B8 B17: the C0 screen also hosts the B17 flag switch, so this engine
        // registers B17HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b17-person.md.
        B17Messages.B17HostApi.setUp(engine.getDartExecutor(), new B17HostApiImpl(this));
        // B8 B18: the C0 screen also hosts the B18 flag switch, so this engine
        // registers B18HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b18-widget.md.
        B18Messages.B18HostApi.setUp(engine.getDartExecutor(), new B18HostApiImpl(this));
        // B8 B19: the C0 screen also hosts the B19 flag switch, so this engine
        // registers B19HostApi too (only the flag methods are ever called
        // on this engine). See docs/b8-b19-plugin.md.
        B19Messages.B19HostApi.setUp(engine.getDartExecutor(), new B19HostApiImpl(this));
        // B8 B20: the C0 screen also hosts the B20 flag switch, so this
        // engine registers B20HostApi too (only the flag methods are
        // ever called on this engine). The impl lives in the mobile
        // source set, so it is registered through the B20Registrar
        // reflection indirection (plan §4.8 option 5); on leanback
        // this is a silent no-op. See docs/b8-b20-hk.md.
        registerB20(engine.getDartExecutor());
        // B8 B21: the C0 screen also hosts the B21 flag switch, so this
        // engine registers B21HostApi too (only the flag methods are
        // ever called on this engine). See docs/b8-b21-lab.md.
        B21HostApiImpl.register(engine.getDartExecutor(), this);
        // B8 B22: the C0 screen also hosts the B22 flag switch, so this
        // engine registers B22HostApi too (only the flag methods are
        // ever called on this engine). See docs/b8-b22-drama.md.
        B22HostApiImpl.register(engine.getDartExecutor(), this);
        engine.getDartExecutor().executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault());
        FlutterEngineCache.getInstance().put(ENGINE_ID, engine);
    }

    /**
     * B8 B20: registers the Hk miniapp manager API through the mobile
     * registrar indirection (same shape as C1ScreenActivity).
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
        // A cached engine outlives its fragment; destroy it only when this
        // Activity is really finishing (rotation keeps it for reuse).
        if (isFinishing()) {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(ENGINE_ID);
            if (engine != null) {
                // B8 B20: drop the Hk impl for this engine.
                unregisterB20(engine.getDartExecutor());
                // B8 B21: drop the lab impl for this engine too.
                B21HostApiImpl.unregister(engine.getDartExecutor());
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
