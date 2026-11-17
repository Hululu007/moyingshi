package com.github.moyingshi.osc.tv.flutter;

import androidx.appcompat.app.AppCompatActivity;

import io.flutter.plugin.common.BinaryMessenger;

import java.util.HashMap;
import java.util.Map;

/**
 * B8 B20: registrar indirection for the Hk miniapp manager API
 * (plan §4.8 option 5).
 *
 * <p>The Hk APIs ({@code HkStore}, {@code HkCloudImport},
 * {@code HkCloudExport}, {@code HkImportPicker}, ...) live in the
 * mobile source set, so no main source set class — including
 * {@link C1ScreenActivity} and {@link C0ShellActivity} — can reference
 * {@link B20HostApiImpl} at compile time. The main activities reach
 * this registrar only by reflection
 * ({@code Class.forName("com.github.moyingshi.osc.tv.flutter.B20Registrar")});
 * on the leanback build this class does not exist and registration is
 * skipped silently. The class name and the {@code register} /
 * {@code unregister} method names survive R8: proguard-rules.pro
 * keeps {@code com.github.moyingshi.osc.tv.**}.
 *
 * <p>One impl per engine (keyed by messenger): the C0 and C1 engines
 * each get their own impl bound to their own Activity. The first
 * {@link #register} call creates the impl and runs the Pigeon
 * {@code setUp}; later calls only rebind the existing impl to the
 * current Activity ({@link B20HostApiImpl#rebind}) — the cached
 * engine outlives its Activity (rotation), but the impl's SAF picker
 * launchers must be registered on a live Activity. Rebinding keeps
 * the worker, the busy flag and the pick state alive across
 * rotation, so an import running through a rotation still completes
 * normally instead of timing out on the watchdog.
 *
 * <p>{@link #unregister} drops the impl for an engine when its host
 * Activity finishes (engine destroy); the impl's worker is shut down
 * and the dead Activity reference is released.
 */
public final class B20Registrar {

    private static final Map<BinaryMessenger, B20HostApiImpl> IMPLS = new HashMap<>();

    private B20Registrar() {
    }

    /**
     * Registers the B20 API on the given messenger (first call per
     * engine), or rebinds the existing impl to the current Activity
     * (later calls, e.g. after rotation).
     */
    public static void register(BinaryMessenger messenger, AppCompatActivity activity) {
        B20HostApiImpl impl = IMPLS.get(messenger);
        if (impl == null) {
            impl = new B20HostApiImpl(activity);
            B20Messages.B20HostApi.setUp(messenger, impl);
            IMPLS.put(messenger, impl);
        } else {
            impl.rebind(activity);
        }
    }

    /** Drops the impl for the given messenger (engine destroy). */
    public static void unregister(BinaryMessenger messenger) {
        B20HostApiImpl old = IMPLS.remove(messenger);
        if (old != null) old.dispose();
    }
}
