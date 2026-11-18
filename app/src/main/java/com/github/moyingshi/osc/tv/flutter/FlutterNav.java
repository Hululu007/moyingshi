package com.github.moyingshi.osc.tv.flutter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.fragment.app.FragmentActivity;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.playback.HistoryResumePayload;
import com.github.moyingshi.osc.tv.ui.activity.FollowingActivity;
import com.github.moyingshi.osc.tv.utils.Util;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLEncoder;

/**
 * Flavor-safe navigation to the screens the native mobile UI used to own
 * (B8 delete-native plan, docs/b8-delete-native-plan.md, phase 1).
 *
 * <p>Every method has two branches, gated by {@link Util#isMobile()}:
 * <ul>
 *   <li>mobile — the Flutter screen on the single C1 engine
 *       ({@link C1ScreenActivity} + route). The native target activity is
 *       deleted in phase 4, so mobile never references it, not even in a
 *       dead branch: a direct reference would break the mobile build.</li>
 *   <li>TV (leanback) — the native activity, reached by reflection on its
 *       fully-qualified class name. Leanback keeps parallel classes with
 *       the same FQCN ({@code HomeActivity}, {@code SearchActivity},
 *       {@code HistoryActivity}, {@code KeepActivity}, {@code FileActivity}),
 *       so the reflective call lands on the leanback implementation with
 *       identical arguments. Nothing here references a mobile-only class.</li>
 * </ul>
 *
 * <p>Finish rules (mirroring the native call they replace):
 * <ul>
 *   <li>{@link #openHomeAt} / {@link #goHome} — the native form was
 *       {@code CLEAR_TOP | SINGLE_TOP} to the home activity, which finished
 *       the caller; the mobile form is start-then-finish.</li>
 *   <li>{@link #openHistory} / {@link #openKeep} — finish only when the
 *       caller is itself a C1 screen (the existing flag-on branches already
 *       did start-then-finish); a native caller keeps the plain-start
 *       semantics of {@code HistoryActivity.start} / {@code KeepActivity.start}.</li>
 *   <li>{@link #openSearch} — never finishes: the native
 *       {@code SearchActivity.start} stacks the search page on top, and the
 *       B8/B12/B13/B18 call sites rely on Back returning to the calling
 *       Flutter screen.</li>
 *   <li>{@link #direct} / {@link #directFromHistory} — finish only when the
 *       caller is a C1 screen. The native {@code direct()} refused C1
 *       callers (G2 engine race) and fell back to the native results page;
 *       the router's C1 form is start-then-finish instead, so the G2
 *       exception is gone and the relaunch mechanism owns the assertion
 *       (plan §6 R4).</li>
 * </ul>
 *
 * <p>Route query encoding is copied verbatim from the mobile
 * {@code SearchActivity} (which this router replaces): same parameter
 * names, order and URL-encoding, so the Dart pages see identical input.
 * Any encoding failure falls back to the reflective native call, exactly
 * like the native {@code start()} / {@code direct()} fall-through.
 */
public final class FlutterNav {

    /** FQCN of the mobile/leanback home activity (parallel classes). */
    public static final String HOME_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.HomeActivity";
    /** FQCN of the mobile/leanback search activity (parallel classes). */
    public static final String SEARCH_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.SearchActivity";
    /** FQCN of the mobile/leanback history activity (parallel classes). */
    public static final String HISTORY_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.HistoryActivity";
    /** FQCN of the mobile/leanback favorites activity (parallel classes). */
    public static final String KEEP_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.KeepActivity";
    /** FQCN of the mobile/leanback file-browser activity (parallel classes). */
    public static final String FILE_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.FileActivity";
    /** FQCN of the mobile/leanback video player activity (parallel classes). */
    public static final String VIDEO_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.VideoActivity";
    /** FQCN of the mobile/leanback live TV activity (parallel classes). */
    public static final String LIVE_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.LiveActivity";

    /** Literal value of the mobile {@code HomeActivity.EXTRA_NAV_POSITION}. */
    private static final String NAV_POSITION_EXTRA = "nav_position";

    private FlutterNav() {
    }

    /**
     * {@code nav_position} (0–10, plan §2.3 M14) mapped to a C1 route.
     * Position -1 (a null page from the settings search / native-page
     * entry) resolves to the settings home, like the native
     * {@code selectNavigation(-1)} resolving to the setting item; any
     * other unknown position falls back to the home route.
     */
    public static String homeRouteForPosition(int position) {
        switch (position) {
            case 0:
                return C1ScreenActivity.ROUTE_HOME;
            case 1:
                return C1ScreenActivity.ROUTE_SETTINGS;
            case 2:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=player";
            case 3:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=enhance";
            case 4:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=danmaku";
            case 5:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=personal";
            case 6:
                return C1ScreenActivity.ROUTE_SUBTITLE;
            case 7:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=tmdb";
            case 8:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=ai";
            case 9:
                return C1ScreenActivity.ROUTE_SETTINGS + "?member=ad";
            case 10:
                return C1ScreenActivity.ROUTE_SEARCH;
            case -1:
                return C1ScreenActivity.ROUTE_SETTINGS;
            default:
                return C1ScreenActivity.ROUTE_HOME;
        }
    }

    /**
     * Intent for the home screen: mobile → C1 {@code ROUTE_HOME};
     * TV → the leanback home activity. Used where the caller only needs
     * the intent (crash-restart fallback, task-root navigation).
     */
    public static Intent homeIntent(Context context) {
        if (Util.isMobile()) {
            return new Intent(context, C1ScreenActivity.class)
                    .putExtra(C1ScreenActivity.EXTRA_ROUTE, C1ScreenActivity.ROUTE_HOME);
        }
        try {
            return new Intent(context, (Class<?>) Class.forName(HOME_ACTIVITY_CLASS));
        } catch (Throwable e) {
            e.printStackTrace();
            return new Intent(context, C1ScreenActivity.class)
                    .putExtra(C1ScreenActivity.EXTRA_ROUTE, C1ScreenActivity.ROUTE_HOME);
        }
    }

    /**
     * Replaces the B5/B7/B11 {@code openHomeAt(int)} helper: mobile opens
     * the C1 route for the position (start-then-finish); TV opens the
     * leanback home activity with the same flags and the
     * {@code nav_position} extra.
     */
    public static void openHomeAt(Activity activity, int position) {
        if (activity == null) return;
        if (Util.isMobile()) {
            C1ScreenActivity.start(activity, homeRouteForPosition(position));
            activity.finish();
            return;
        }
        try {
            Intent intent = new Intent(activity, (Class<?>) Class.forName(HOME_ACTIVITY_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(NAV_POSITION_EXTRA, position);
            activity.startActivity(intent);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Replaces the C2/B2 {@code goHome()}: mobile opens C1
     * {@code ROUTE_HOME} (start-then-finish); TV opens the leanback home
     * activity with the same flags the native {@code goHome()} used.
     */
    public static void goHome(Activity activity) {
        if (activity == null) return;
        if (Util.isMobile()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_HOME);
            activity.finish();
            return;
        }
        try {
            Intent intent = new Intent(activity, (Class<?>) Class.forName(HOME_ACTIVITY_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** Replaces {@code SearchActivity.start(Activity)}. */
    public static void openSearch(Activity activity) {
        openSearch(activity, "");
    }

    /** Replaces {@code SearchActivity.start(Activity, String)}. */
    public static void openSearch(Activity activity, String keyword) {
        openSearch(activity, keyword, null, null, null);
    }

    /** Replaces {@code SearchActivity.start(Activity, String, String)}. */
    public static void openSearch(Activity activity, String keyword, String siteKey) {
        openSearch(activity, keyword, siteKey, null, null);
    }

    /**
     * Replaces {@code SearchActivity.start(Activity, String, String,
     * String, String)}: mobile opens C1 {@code ROUTE_SEARCH} with the
     * same URL-encoded query; TV reflects the native {@code start}.
     * Never finishes the caller (the search page stacks on top).
     */
    public static void openSearch(Activity activity, String keyword, String siteKey, String pic, String wallPic) {
        if (activity == null) return;
        if (Util.isMobile()) {
            try {
                String route = C1ScreenActivity.ROUTE_SEARCH
                        + "?keyword=" + URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8")
                        + "&siteKey=" + URLEncoder.encode(siteKey == null ? "" : siteKey, "UTF-8")
                        + "&pic=" + URLEncoder.encode(pic == null ? "" : pic, "UTF-8")
                        + "&wallPic=" + URLEncoder.encode(wallPic == null ? "" : wallPic, "UTF-8");
                C1ScreenActivity.start(activity, route);
            } catch (Throwable e) {
                e.printStackTrace();
                reflectSearchStart(activity, keyword, siteKey, pic, wallPic);
            }
            return;
        }
        reflectSearchStart(activity, keyword, siteKey, pic, wallPic);
    }

    /** Replaces {@code SearchActivity.direct(Activity, String)}. */
    public static void direct(Activity activity, String keyword) {
        direct(activity, keyword, null, null, null);
    }

    /** Replaces {@code SearchActivity.direct(Activity, String, String)}. */
    public static void direct(Activity activity, String keyword, String siteKey) {
        direct(activity, keyword, siteKey, null, null);
    }

    /**
     * Replaces {@code SearchActivity.direct(Activity, String, String,
     * String, String)}: mobile opens C1 {@code ROUTE_COLLECT} with the
     * same URL-encoded query (currentSite=false, empty group, hrCid/hrKey/
     * hrTargetCid -1/empty/-1); TV reflects the native {@code direct}.
     * A C1 caller is finished (start-then-finish replaces the native G2
     * exception); any other caller is left alive under the new screen.
     */
    public static void direct(Activity activity, String keyword, String siteKey, String pic, String wallPic) {
        if (activity == null) return;
        if (Util.isMobile()) {
            try {
                String route = C1ScreenActivity.ROUTE_COLLECT
                        + "?keyword=" + URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8")
                        + "&currentSite=false"
                        + "&siteKey=" + URLEncoder.encode(siteKey == null ? "" : siteKey, "UTF-8")
                        + "&group="
                        + "&pic=" + URLEncoder.encode(pic == null ? "" : pic, "UTF-8")
                        + "&wallPic=" + URLEncoder.encode(wallPic == null ? "" : wallPic, "UTF-8")
                        + "&hrCid=-1&hrKey=&hrTargetCid=-1";
                C1ScreenActivity.start(activity, route);
                if (activity instanceof C1ScreenActivity) activity.finish();
            } catch (Throwable e) {
                e.printStackTrace();
                reflectDirect(activity, keyword, siteKey, pic, wallPic);
            }
            return;
        }
        reflectDirect(activity, keyword, siteKey, pic, wallPic);
    }

    /** Replaces {@code SearchActivity.directFromHistory(Activity, History)}. */
    public static void directFromHistory(Activity activity, History history) {
        if (activity == null || history == null) return;
        directFromHistory(activity, history, history.getVodName(), VodConfig.getCid());
    }

    /** Replaces {@code SearchActivity.directFromHistory(Activity, History, String)}. */
    public static void directFromHistory(Activity activity, History history, String keyword) {
        if (activity == null || history == null) return;
        directFromHistory(activity, history, keyword, VodConfig.getCid());
    }

    /**
     * Replaces {@code SearchActivity.directFromHistory(Activity, History,
     * String, int)}: same route query as the native form (keyword falls
     * back to the history vod name; hrCid/hrKey/hrTargetCid carry the
     * history-resume payload). Finish rule is the same as {@link #direct}.
     */
    public static void directFromHistory(Activity activity, History history, String keyword, int targetCid) {
        if (activity == null || history == null) return;
        if (Util.isMobile()) {
            try {
                String route = C1ScreenActivity.ROUTE_COLLECT
                        + "?keyword=" + URLEncoder.encode(
                                keyword == null || keyword.isEmpty() ? history.getVodName() : keyword, "UTF-8")
                        + "&currentSite=false"
                        + "&siteKey="
                        + "&group="
                        + "&pic=" + URLEncoder.encode(history.getVodPic() == null ? "" : history.getVodPic(), "UTF-8")
                        + "&wallPic=" + URLEncoder.encode(history.getWallPic() == null ? "" : history.getWallPic(), "UTF-8")
                        + "&hrCid=" + history.getCid()
                        + "&hrKey=" + URLEncoder.encode(HistoryResumePayload.encode(history), "UTF-8")
                        + "&hrTargetCid=" + targetCid;
                C1ScreenActivity.start(activity, route);
                if (activity instanceof C1ScreenActivity) activity.finish();
            } catch (Throwable e) {
                e.printStackTrace();
                reflectDirectFromHistory(activity, history, keyword, targetCid);
            }
            return;
        }
        reflectDirectFromHistory(activity, history, keyword, targetCid);
    }

    /**
     * Replaces the {@code HistoryActivity.start} fallback branches
     * (B11 openHistory): mobile opens C1 {@code ROUTE_HISTORY}; TV
     * reflects the native {@code HistoryActivity.start}. Finishes a C1
     * caller (the existing flag-on branches already did
     * start-then-finish); a native caller is left alive.
     */
    public static void openHistory(Activity activity) {
        if (activity == null) return;
        if (Util.isMobile()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_HISTORY);
            if (activity instanceof C1ScreenActivity) activity.finish();
            return;
        }
        try {
            Class<?> type = Class.forName(HISTORY_ACTIVITY_CLASS);
            type.getMethod("start", Activity.class).invoke(null, activity);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Replaces the {@code KeepActivity.start} fallback branches (B11 menu
     * "keep", HomeWebBridge openKeep): mobile opens C1 {@code ROUTE_KEEP};
     * TV reflects the native {@code KeepActivity.start}. Finish rule is
     * the same as {@link #openHistory}.
     */
    public static void openKeep(Activity activity) {
        if (activity == null) return;
        if (Util.isMobile()) {
            C1ScreenActivity.start(activity, C1ScreenActivity.ROUTE_KEEP);
            if (activity instanceof C1ScreenActivity) activity.finish();
            return;
        }
        try {
            Class<?> type = Class.forName(KEEP_ACTIVITY_CLASS);
            type.getMethod("start", Activity.class).invoke(null, activity);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Replaces the B4 {@code openLocalReader} flag-off branch: mobile
     * opens C1 {@code ROUTE_LOCAL} in read mode (start-then-finish, like
     * the flag-on branch); TV opens the leanback file activity with the
     * {@code read_mode} extra.
     */
    public static void openLocalReader(Activity activity) {
        if (activity == null) return;
        if (Util.isMobile()) {
            C1ScreenActivity.start(activity,
                    C1ScreenActivity.ROUTE_LOCAL + "?selectDir=false&readMode=true");
            activity.finish();
            return;
        }
        try {
            Intent intent = new Intent(activity, (Class<?>) Class.forName(FILE_ACTIVITY_CLASS));
            intent.putExtra("read_mode", true);
            activity.startActivity(intent);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Replaces {@code FileChooser.localBrowserIntent}: mobile returns the
     * C1 {@code ROUTE_LOCAL} intent (browser mode); TV returns the
     * leanback file-activity intent with the {@code select_dir} extra.
     * The B15 flag check lives here now — mobile is unconditionally C1.
     */
    public static Intent localBrowserIntent(boolean selectDir) {
        if (Util.isMobile()) {
            return new Intent(App.get(), C1ScreenActivity.class)
                    .putExtra(C1ScreenActivity.EXTRA_ROUTE, C1ScreenActivity.ROUTE_LOCAL
                            + "?selectDir=" + selectDir + "&readMode=false");
        }
        try {
            Intent intent = new Intent(App.get(), (Class<?>) Class.forName(FILE_ACTIVITY_CLASS));
            if (selectDir) intent.putExtra("select_dir", true);
            return intent;
        } catch (Throwable e) {
            e.printStackTrace();
            return new Intent(App.get(), C1ScreenActivity.class)
                    .putExtra(C1ScreenActivity.EXTRA_ROUTE, C1ScreenActivity.ROUTE_LOCAL
                            + "?selectDir=" + selectDir + "&readMode=false");
        }
    }

    /**
     * Notification landing for a following update (plan §6 R7, decision
     * D7: mobile lands on the Flutter following page). Mobile returns a
     * C1 {@code ROUTE_FOLLOWING} intent; TV returns the native
     * {@code FollowingActivity} intent for the item. Note the Dart
     * {@code /following} route takes no query string (main.dart registers
     * only the literal route), so the identityKey is deliberately not
     * appended on mobile — the list opens and the user taps the item.
     */
    public static Intent followingIntent(Context context, String identityKey) {
        if (Util.isMobile()) {
            return new Intent(context, C1ScreenActivity.class)
                    .putExtra(C1ScreenActivity.EXTRA_ROUTE, C1ScreenActivity.ROUTE_FOLLOWING);
        }
        return FollowingActivity.intent(context, identityKey);
    }

    /**
     * Replaces {@code VideoActivity.push(FragmentActivity, String)} for
     * callers in the main source set (B8 delete-native phase 3: the C1
     * home's SEND/VIEW intent handling and the ServerEvent PUSH branch).
     * Both flavors keep a parallel VideoActivity with the same FQCN and
     * the same push signature, so one reflective call serves both — no
     * isMobile branch. A failure only logs: pushing video must never
     * crash the home screen.
     */
    public static void pushVideo(FragmentActivity activity, String text) {
        if (activity == null) return;
        try {
            Class<?> type = Class.forName(VIDEO_ACTIVITY_CLASS);
            type.getMethod("push", FragmentActivity.class, String.class)
                    .invoke(null, activity, text);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Replaces {@code LiveActivity.start(Context)} for callers in the
     * main source set (B8 delete-native phase 3: the C1 home's BOOT
     * branch and .m3u VIEW handling). Same shape as {@link #pushVideo}:
     * parallel classes, one reflective call, failures only logged.
     */
    public static void startLive(Context context) {
        if (context == null) return;
        try {
            Class<?> type = Class.forName(LIVE_ACTIVITY_CLASS);
            type.getMethod("start", Context.class).invoke(null, context);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // Reflective native calls (TV branch, and the mobile encoding-failure
    // fallback). Signatures mirror the leanback overloads exactly.
    // ------------------------------------------------------------------

    private static void reflectSearchStart(Activity activity, String keyword, String siteKey,
                                           String pic, String wallPic) {
        try {
            Class<?> type = Class.forName(SEARCH_ACTIVITY_CLASS);
            Method start = type.getMethod("start", Activity.class, String.class,
                    String.class, String.class, String.class);
            start.invoke(null, activity, keyword, siteKey, pic, wallPic);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void reflectDirect(Activity activity, String keyword, String siteKey,
                                      String pic, String wallPic) {
        try {
            Class<?> type = Class.forName(SEARCH_ACTIVITY_CLASS);
            Method direct = type.getMethod("direct", Activity.class, String.class,
                    String.class, String.class, String.class);
            direct.invoke(null, activity, keyword, siteKey, pic, wallPic);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void reflectDirectFromHistory(Activity activity, History history,
                                                 String keyword, int targetCid) {
        try {
            Class<?> type = Class.forName(SEARCH_ACTIVITY_CLASS);
            Method direct = type.getMethod("directFromHistory", Activity.class,
                    History.class, String.class, int.class);
            direct.invoke(null, activity, history, keyword, targetCid);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
