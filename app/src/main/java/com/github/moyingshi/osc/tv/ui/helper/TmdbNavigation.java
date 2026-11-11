package com.github.moyingshi.osc.tv.ui.helper;

import android.app.Activity;
import android.text.TextUtils;

import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.bean.Result;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.TmdbItem;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.ui.activity.SearchActivity;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class TmdbNavigation {

    private TmdbNavigation() {
    }

    public interface Opener {
        void open(Site site, Vod match);
    }

    public static void open(Activity activity, TmdbItem item, Site currentSite) {
        open(activity, item, currentSite, null, null);
    }

    public static void open(Activity activity, TmdbItem item, Site currentSite, Opener opener, Runnable afterStart) {
        open(activity, item, currentSite, opener, afterStart, () -> true);
    }

    public static void open(Activity activity, TmdbItem item, Site currentSite, Opener opener, Runnable afterStart,
            BooleanSupplier active) {
        if (activity == null || item == null || TextUtils.isEmpty(item.getTitle()) || !allowed(active)) return;
        Site site = searchable(currentSite) ? currentSite : null;
        if (site == null) {
            if (!allowed(active)) return;
            openGlobal(activity, item);
            run(afterStart);
            return;
        }
        Notify.show(activity.getString(R.string.detail_work_searching, item.getTitle()));
        Task.execute(() -> {
            Vod match = searchCurrentSite(item.getTitle(), site);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || !allowed(active)) return;
                if (match == null) {
                    Notify.show(activity.getString(R.string.detail_work_global_searching, item.getTitle()));
                    openGlobal(activity, item);
                } else if (opener != null) {
                    opener.open(site, match);
                } else {
                    VideoActivity.start(activity, site.getKey(), match.getId(), match.getName(), match.getPic(), null);
                }
                run(afterStart);
            });
        });
    }

    public static Vod searchCurrentSite(String keyword, Site site) {
        if (!searchable(site)) return null;
        try {
            Result result = SiteApi.searchContent(site, keyword, false, "1");
            return bestVod(result != null ? result.getList() : new ArrayList<>(), keyword);
        } catch (Throwable e) {
            return null;
        }
    }

    static Vod bestVod(List<Vod> items, String keyword) {
        if (items == null || items.isEmpty()) return null;
        Vod best = null;
        int score = Integer.MIN_VALUE;
        for (Vod item : items) {
            int current = scoreVod(item, keyword);
            if (current > score) {
                score = current;
                best = item;
            }
        }
        return score > 0 ? best : null;
    }

    private static int scoreVod(Vod item, String keyword) {
        if (item == null) return Integer.MIN_VALUE;
        String normalizedKeyword = normalizeTitle(keyword);
        String name = normalizeTitle(item.getName());
        if (name.isEmpty()) return Integer.MIN_VALUE;
        if (name.equals(normalizedKeyword)) return 300;
        if (name.contains(normalizedKeyword) || normalizedKeyword.contains(name)) return 220;
        String remarks = normalizeTitle(item.getRemarks());
        if (!remarks.isEmpty() && (remarks.contains(normalizedKeyword) || normalizedKeyword.contains(remarks))) return 120;
        return 0;
    }

    private static String normalizeTitle(String text) {
        return text == null || text.isEmpty() ? "" : text.replaceAll("[\\s·•・._\\-/\\\\|()（）\\[\\]【】《》<>]+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean searchable(Site site) {
        return site != null && !site.isEmpty() && site.isSearchable();
    }

    private static void openGlobal(Activity activity, TmdbItem item) {
        SearchActivity.direct(activity, item.getTitle(), null, item.getPosterUrl(), item.getBackdropUrl());
    }

    private static boolean allowed(BooleanSupplier active) {
        return active == null || active.getAsBoolean();
    }

    private static void run(Runnable runnable) {
        if (runnable != null) runnable.run();
    }
}
