package com.github.moyingshi.osc.tv.flutter;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.load.model.GlideUrl;
import com.github.catvod.utils.Prefers;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.setting.Setting;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.utils.ColorGenerator;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.utils.TmdbProxy;

import io.flutter.plugin.common.BinaryMessenger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * B8 B22: host side of the short-drama theater / history tabs (scheme B,
 * see docs/b8-short-drama-scoping.md §2.2 and docs/b8-b22-drama.md).
 *
 * <p>Shape follows B21 (static per-messenger registry + rebind on
 * rotation, single worker thread + main-thread Completion with a
 * watchdog, one impl per engine). The rebind matters here: a card tap
 * finishes the C1 screen, and after a rotation the impl must hold the
 * live Activity instance, not the destroyed one the engine was created
 * with (the adjacent gap documented in docs/b8-c1-relaunch-mechanism.md
 * §1.4).
 *
 * <p>Everything that loads or navigates is 100% host-side, mirroring
 * ShortDramaActivity.loadSource / reloadHistory step for step; Dart
 * only renders the @async snapshots. The player never moves: a theater
 * card tap asks the native ShortDramaActivity underneath (reflectively,
 * B11 precedent — it is a mobile-only class) to jump its feed to the
 * tapped item, then finishes this Flutter screen. A history row tap
 * opens the native VideoActivity detail page. No EventChannel anywhere.
 *
 * <p>Flavor safety: this class lives in the main source set and must
 * compile for both flavors. DramaSites is mobile-only, so its
 * detection/selection logic is replicated below (plan §4.8招3, drift
 * warnings inline; B11 already replicates isDramaSite the same way).
 * All 13 drama_* strings are mobile-only, so the four this batch needs
 * are resolved at runtime via {@link #mobileString(String, int)} with
 * main-res fallbacks (the fallbacks are unreachable in practice: the
 * screen has no leanback entry, C1ScreenActivity is mobile-manifest
 * only, and ShortDramaActivity itself is mobile-only).
 */
public final class B22HostApiImpl implements B22Messages.B22HostApi {

    /** Every @async method must complete on all paths (plan §4.6). */
    private static final long LOAD_TIMEOUT_MS = 35_000L;

    /** Native grid: GridLayoutManager(this, 3), fixed. */
    private static final int THEATER_COLUMNS = 3;

    /** ShortDramaActivity, reflectively (mobile-only class, B11 precedent). */
    private static final String SHORT_DRAMA_ACTIVITY_CLASS =
            "com.github.moyingshi.osc.tv.ui.activity.ShortDramaActivity";

    /** DramaSites.PREF_SITE, replicated (mobile-only class, plan §4.8招3). */
    private static final String PREF_DRAMA_TAB_SITE = "drama_tab_site";

    /** DramaSites.KEYWORDS, replicated (mobile-only class, plan §4.8招3). */
    private static final String[] DRAMA_KEYWORDS = {"短剧", "漫短", "漫剧", "[短]"};

    /** One impl per engine (C0 and C1 engines each get their own). */
    private static final Map<BinaryMessenger, B22HostApiImpl> IMPLS = new HashMap<>();

    private volatile AppCompatActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;

    private B22HostApiImpl(AppCompatActivity activity) {
        this.activity = activity;
    }

    /**
     * Registers the B22 API on the given messenger (first call per
     * engine), or rebinds the existing impl to the current Activity
     * (later calls, e.g. after rotation). See B21HostApiImpl.
     */
    public static void register(BinaryMessenger messenger, AppCompatActivity activity) {
        B22HostApiImpl impl = IMPLS.get(messenger);
        if (impl == null) {
            impl = new B22HostApiImpl(activity);
            B22Messages.B22HostApi.setUp(messenger, impl);
            IMPLS.put(messenger, impl);
        } else {
            impl.rebind(activity);
        }
    }

    /** Drops the impl for the given messenger (engine destroy). */
    public static void unregister(BinaryMessenger messenger) {
        B22HostApiImpl old = IMPLS.remove(messenger);
        if (old != null) old.dispose();
    }

    /** Rebinds this impl to a new Activity instance (rotation). */
    public void rebind(AppCompatActivity activity) {
        this.activity = activity;
    }

    /** Shuts the worker down and releases the Activity (engine destroy). */
    public void dispose() {
        activity = null;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------------
    // Completion (B21 shape: watchdog + done guard, main thread only)
    // ------------------------------------------------------------------

    private final class Completion<T> {
        private final B22Messages.Result<T> result;
        private boolean done;
        private final Runnable timeout;

        Completion(B22Messages.Result<T> result, long timeoutMs, T timeoutValue) {
            this.result = result;
            this.timeout = () -> complete(timeoutValue);
            handler.postDelayed(this.timeout, timeoutMs);
        }

        /** Must be called on the main thread (Pigeon Result). */
        void complete(T value) {
            if (done) return;
            done = true;
            handler.removeCallbacks(timeout);
            result.success(value);
        }

        boolean isDone() {
            return done;
        }
    }

    private ExecutorService executorService() {
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        return executor;
    }

    private static String nn(@Nullable String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------
    // Feature flag
    // ------------------------------------------------------------------

    @Override
    public Boolean isB22Enabled() {
        return Setting.isFlutterB22Enabled();
    }

    @Override
    public void setB22Enabled(Boolean enabled) {
        Setting.putFlutterB22Enabled(Boolean.TRUE.equals(enabled));
    }

    // ------------------------------------------------------------------
    // Theater snapshot (ShortDramaActivity.loadSource, step for step)
    // ------------------------------------------------------------------

    @Override
    public void getTheaterScreen(B22Messages.Result<B22Messages.B22DramaTheaterScreen> result) {
        Completion<B22Messages.B22DramaTheaterScreen> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS, errorTheaterScreen());
        executorService().execute(() -> {
            B22Messages.B22DramaTheaterScreen snapshot = buildTheaterScreen();
            handler.post(() -> {
                if (completion.isDone()) return;
                completion.complete(snapshot);
            });
        });
    }

    /**
     * Mirrors ShortDramaActivity.loadSource: homeContent, then fetchPic,
     * then — when the list is empty and types exist — categoryContent
     * of the first type. Runs on the worker thread; the native code
     * swallows every exception and shows the no-content state, and so
     * does this: an exception yields an empty list, never an error
     * snapshot (only the watchdog produces one).
     */
    private B22Messages.B22DramaTheaterScreen buildTheaterScreen() {
        List<Site> sites = detectDramaSites();
        Site current = currentDramaSite(sites);
        if (current == null) {
            return theaterScreen(mobileString("drama_empty", R.string.widget_empty),
                    new ArrayList<>());
        }
        List<Vod> loaded = new ArrayList<>();
        try {
            Result result = SiteApi.homeContent(current);
            if (result != null) {
                try {
                    result = SiteApi.fetchPic(current, result);
                } catch (Exception ignored) {
                }
                if (result != null) loaded = result.getList();
                if (loaded.isEmpty() && result != null && !result.getTypes().isEmpty()) {
                    Result category = SiteApi.categoryContent(current.getKey(),
                            result.getTypes().get(0).getTypeId(), "1", false, new HashMap<>());
                    if (category != null) loaded = category.getList();
                }
            }
        } catch (Exception ignored) {
            loaded = new ArrayList<>();
        }
        List<B22Messages.B22DramaItem> items = new ArrayList<>();
        for (Vod vod : loaded) items.add(dramaItem(vod));
        return theaterScreen(mobileString("drama_no_content", R.string.widget_empty), items);
    }

    private B22Messages.B22DramaTheaterScreen theaterScreen(String stateText,
            List<B22Messages.B22DramaItem> items) {
        AppCompatActivity act = activity;
        String title = act == null ? "" : mobileString("drama_theater", R.string.app_name);
        String retry = act == null ? "" : act.getString(R.string.error_retry);
        return new B22Messages.B22DramaTheaterScreen.Builder()
                .setTitle(title)
                .setStateText(stateText)
                .setRetryLabel(retry)
                .setLoadErrorText("")
                .setColumnCount((long) THEATER_COLUMNS)
                .setItems(items)
                .build();
    }

    /** Watchdog value: the timeout text, an empty list, and no state text. */
    private B22Messages.B22DramaTheaterScreen errorTheaterScreen() {
        AppCompatActivity act = activity;
        String title = act == null ? "" : mobileString("drama_theater", R.string.app_name);
        String retry = act == null ? "" : act.getString(R.string.error_retry);
        String timeout = act == null ? "" : act.getString(R.string.error_play_timeout);
        return new B22Messages.B22DramaTheaterScreen.Builder()
                .setTitle(title)
                .setStateText("")
                .setRetryLabel(retry)
                .setLoadErrorText(timeout)
                .setColumnCount((long) THEATER_COLUMNS)
                .setItems(new ArrayList<>())
                .build();
    }

    private B22Messages.B22DramaItem dramaItem(Vod vod) {
        PicParts pic = resolvePic(nn(vod.getName()), nn(vod.getPic()));
        return new B22Messages.B22DramaItem.Builder()
                .setId(nn(vod.getId()))
                .setName(nn(vod.getName()))
                .setRemarks(nn(vod.getRemarks()))
                .setPicUrl(pic.url)
                .setPicReferer(pic.referer)
                .setPicCookie(pic.cookie)
                .setPicUserAgent(pic.userAgent)
                .setPicExtraHeadersJson(pic.extraHeadersJson)
                .setFallbackPicUrl(pic.fallbackUrl)
                .setPlaceholderText(pic.placeholderText)
                .setPlaceholderColor((long) pic.placeholderColor)
                .build();
    }

    // ------------------------------------------------------------------
    // History snapshot (ShortDramaActivity.reloadHistory + HistoryAdapter)
    // ------------------------------------------------------------------

    @Override
    public void getHistoryScreen(B22Messages.Result<B22Messages.B22DramaHistoryScreen> result) {
        Completion<B22Messages.B22DramaHistoryScreen> completion =
                new Completion<>(result, LOAD_TIMEOUT_MS, emptyHistoryScreen());
        executorService().execute(() -> {
            B22Messages.B22DramaHistoryScreen snapshot = buildHistoryScreen();
            handler.post(() -> {
                if (completion.isDone()) return;
                completion.complete(snapshot);
            });
        });
    }

    /**
     * Mirrors ShortDramaActivity.reloadHistory (History.get() filtered
     * by the drama-site key prefix) and HistoryAdapter.onBindViewHolder
     * (meta = site display name, plus " · " + remarks when remarks is
     * non-empty; time = "MM-dd HH:mm", empty when createTime is 0).
     * Runs on the worker thread (a Room read, like native).
     */
    private B22Messages.B22DramaHistoryScreen buildHistoryScreen() {
        List<Site> sites = detectDramaSites();
        List<History> histories = new ArrayList<>();
        try {
            for (History history : History.get()) {
                String key = history.getKey();
                if (key == null) continue;
                for (Site site : sites) {
                    if (key.startsWith(site.getKey() + AppDatabase.SYMBOL)) {
                        histories.add(history);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        List<B22Messages.B22DramaHistoryItem> items = new ArrayList<>();
        for (History history : histories) items.add(historyItem(history, sites, format));
        AppCompatActivity act = activity;
        String title = act == null ? "" : mobileString("drama_history", R.string.setting_home_history);
        return new B22Messages.B22DramaHistoryScreen.Builder()
                .setTitle(title)
                .setItems(items)
                .build();
    }

    private B22Messages.B22DramaHistoryScreen emptyHistoryScreen() {
        AppCompatActivity act = activity;
        String title = act == null ? "" : mobileString("drama_history", R.string.setting_home_history);
        return new B22Messages.B22DramaHistoryScreen.Builder()
                .setTitle(title)
                .setItems(new ArrayList<>())
                .build();
    }

    private B22Messages.B22DramaHistoryItem historyItem(History history, List<Site> sites,
            SimpleDateFormat format) {
        String[] parts = history.getKey() == null ? new String[0] : history.getKey().split(AppDatabase.SYMBOL);
        String siteKey = parts.length >= 1 ? parts[0] : "";
        String vodId = parts.length >= 2 ? parts[1] : "";
        String siteName = "";
        for (Site site : sites) {
            if (site.getKey().equals(siteKey)) siteName = site.getDisplayName();
        }
        if (TextUtils.isEmpty(siteName)) siteName = siteKey;
        String remarks = nn(history.getVodRemarks());
        String meta = TextUtils.isEmpty(remarks) ? siteName : siteName + " · " + remarks;
        String timeText = history.getCreateTime() > 0 ? format.format(new Date(history.getCreateTime())) : "";
        PicParts pic = resolvePic(nn(history.getVodName()), nn(history.getVodPic()));
        return new B22Messages.B22DramaHistoryItem.Builder()
                .setSiteKey(siteKey)
                .setVodId(vodId)
                .setVodName(nn(history.getVodName()))
                .setVodPic(nn(history.getVodPic()))
                .setMeta(meta)
                .setTimeText(timeText)
                .setPicUrl(pic.url)
                .setPicReferer(pic.referer)
                .setPicCookie(pic.cookie)
                .setPicUserAgent(pic.userAgent)
                .setPicExtraHeadersJson(pic.extraHeadersJson)
                .setFallbackPicUrl(pic.fallbackUrl)
                .setPlaceholderText(pic.placeholderText)
                .setPlaceholderColor((long) pic.placeholderColor)
                .build();
    }

    // ------------------------------------------------------------------
    // Navigation (host-side, like every other batch)
    // ------------------------------------------------------------------

    /**
     * Theater card tap: ask the native ShortDramaActivity underneath to
     * jump its feed to this item, then finish this Flutter screen so
     * the user lands on the native play stream. The jump request goes
     * through the static ShortDramaActivity.jumpToFeedItem (called
     * reflectively: the Activity is mobile-only, B11 precedent); the
     * native Activity consumes it in onResume. If reflection fails
     * the screen still finishes, landing on the current feed page.
     */
    @Override
    public void openFeedItem(String vodId, Long position) {
        try {
            java.lang.Class<?> clazz = java.lang.Class.forName(SHORT_DRAMA_ACTIVITY_CLASS);
            clazz.getMethod("jumpToFeedItem", String.class, int.class)
                    .invoke(null, vodId, position == null ? -1 : position.intValue());
        } catch (Throwable e) {
            e.printStackTrace();
        }
        AppCompatActivity act = activity;
        if (act != null && !act.isFinishing() && !act.isDestroyed()) act.finish();
    }

    /**
     * History row tap: open the native detail page, exactly like the
     * native history row (VideoActivity.start, 5 args). VideoActivity
     * exists in both flavors with the identical signature, so a direct
     * reference is safe (B2 precedent). The native row click requires
     * the split key to have at least 2 parts, i.e. a non-empty vodId;
     * an empty vodId is a no-op here too.
     */
    @Override
    public void openHistoryItem(String siteKey, String vodId,
            String vodName, String vodPic) {
        AppCompatActivity act = activity;
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        if (TextUtils.isEmpty(vodId)) return;
        VideoActivity.start(act, siteKey, vodId, vodName, vodPic);
    }

    // ------------------------------------------------------------------
    // DramaSites, replicated (mobile-only class, plan §4.8招3)
    // ------------------------------------------------------------------

    /**
     * DramaSites.isDramaSite, replicated verbatim: name/key contains a
     * drama keyword, or the site is enabled in the short-drama config.
     * DRIFT WARNING: if DramaSites.KEYWORDS / isDramaSite changes,
     * update this too (B11 replicates the same method the same way).
     */
    private boolean isDramaSite(Site site) {
        if (site == null || site.isEmpty()) return false;
        String name = site.getName();
        String key = site.getKey();
        for (String keyword : DRAMA_KEYWORDS) {
            if (!TextUtils.isEmpty(name) && name.contains(keyword)) return true;
            if (!TextUtils.isEmpty(key) && key.contains(keyword)) return true;
        }
        return Setting.isShortDramaSiteEnabled(key, name == null ? "" : name);
    }

    /**
     * DramaSites.detect, replicated verbatim.
     * DRIFT WARNING: keep in sync with DramaSites.detect().
     */
    private List<Site> detectDramaSites() {
        List<Site> result = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (isDramaSite(site)) result.add(site);
        return result;
    }

    /**
     * DramaSites.current, replicated verbatim: the remembered site
     * (Prefers "drama_tab_site"), else the first detected.
     * DRIFT WARNING: keep in sync with DramaSites.current(), including
     * the preference key.
     */
    private Site currentDramaSite(List<Site> sites) {
        if (sites == null || sites.isEmpty()) return null;
        String saved = Prefers.getString(PREF_DRAMA_TAB_SITE, "");
        if (!TextUtils.isEmpty(saved)) {
            for (Site site : sites) if (saved.equals(site.getKey())) return site;
        }
        return sites.get(0);
    }

    // ------------------------------------------------------------------
    // Mobile-only strings (B11 shape: runtime lookup + main-res fallback)
    // ------------------------------------------------------------------

    /**
     * Resolves a mobile-only string by name at runtime; the fallback is
     * a main-res string, used only when the name is missing (leanback,
     * where this screen has no entry). B11's mobileString, copied.
     */
    private String mobileString(String name, int fallbackResId) {
        AppCompatActivity act = activity;
        if (act == null) return "";
        int id = act.getResources().getIdentifier(name, "string", act.getPackageName());
        return id != 0 ? act.getString(id) : act.getString(fallbackResId);
    }

    // ------------------------------------------------------------------
    // Image resolution (B2 shape: ImgUtil.getUrl delegation, D3)
    // ------------------------------------------------------------------

    /** Resolved image parts for one card, mirroring ImgUtil.load() semantics. */
    private static final class PicParts {
        String url = "";
        String referer = "";
        String cookie = "";
        String userAgent = "";
        String extraHeadersJson = "";
        String fallbackUrl = "";
        String placeholderText = "！";
        int placeholderColor;
    }

    /**
     * Resolves one vodPic the way native loading does, without touching
     * ImgUtil: {@link ImgUtil#getUrl(String)} already parses the
     * {@code @Headers=} / {@code @Cookie=} / {@code @Referer=} /
     * {@code @User-Agent=} suffixes and the douban referer policy, and
     * its GlideUrl result exposes the clean URL plus the final header
     * table. Copied from B2HostApiImpl.resolvePic (per-batch revert
     * discipline: no shared helper); a single-item failure degrades
     * that item to the placeholder only, like native load().
     */
    private static PicParts resolvePic(String name, String rawPic) {
        PicParts parts = new PicParts();
        // TextDrawable parity: the first character (or "！"), colored by
        // ColorGenerator.get400() of that same character.
        parts.placeholderText = name.isEmpty() ? "！" : name.substring(0, 1);
        parts.placeholderColor = ColorGenerator.get400(parts.placeholderText);
        try {
            Object model = ImgUtil.getUrl(rawPic);
            if (model instanceof GlideUrl glideUrl) {
                parts.url = nn(glideUrl.toStringUrl());
                splitHeaders(glideUrl.getHeaders(), parts);
            } else if (model instanceof String dataUrl) {
                // "data:" URLs pass through getUrl() unchanged. Dart shows
                // the placeholder for them (Image.network cannot load
                // data: URIs); see docs/b8-b2-keep.md.
                parts.url = dataUrl;
            }
        } catch (Throwable e) {
            e.printStackTrace();
            parts.url = "";
        }
        try {
            String autoFallback = TmdbProxy.nextAutoImageUrl(nn(rawPic));
            if (!TextUtils.isEmpty(autoFallback)) {
                Object fallbackModel = ImgUtil.getUrl(autoFallback);
                if (fallbackModel instanceof GlideUrl glideFallback) {
                    parts.fallbackUrl = nn(glideFallback.toStringUrl());
                } else if (fallbackModel instanceof String fallbackString) {
                    parts.fallbackUrl = fallbackString;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return parts;
    }

    private static void splitHeaders(Map<String, String> headers, PicParts parts) {
        if (headers == null || headers.isEmpty()) return;
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = nn(entry.getValue());
            if ("Referer".equalsIgnoreCase(key)) parts.referer = value;
            else if ("Cookie".equalsIgnoreCase(key)) parts.cookie = value;
            else if ("User-Agent".equalsIgnoreCase(key)) parts.userAgent = value;
            else extra.put(nn(key), value);
        }
        if (!extra.isEmpty()) parts.extraHeadersJson = App.gson().toJson(extra);
    }
}
