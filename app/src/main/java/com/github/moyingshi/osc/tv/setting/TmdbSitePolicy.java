package com.github.moyingshi.osc.tv.setting;

import com.github.moyingshi.osc.tv.api.config.VodConfig;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.TmdbConfig;
import com.github.moyingshi.osc.tv.web.WebHomeInlineVodStore;

/** Resolves the real site identity behind synthetic playback routes before applying TMDB rules. */
public final class TmdbSitePolicy {

    private TmdbSitePolicy() {
    }

    public static boolean isEnabled(String key, String id) {
        return isEnabled(TmdbConfig.objectFrom(Setting.getTmdbConfig()), key, id);
    }

    public static boolean isEnabled(TmdbConfig config, String key, String id) {
        if (config == null) return false;
        Site site = resolve(key, id);
        String effectiveKey = site.isEmpty() ? key : site.getKey();
        String effectiveName = site.isEmpty() ? "" : site.getName();
        return config.isSiteEnabled(effectiveKey, effectiveName);
    }

    private static Site resolve(String key, String id) {
        if (WebHomeInlineVodStore.KEY.equals(key)) {
            Site inlineSite = WebHomeInlineVodStore.getOriginSite(id);
            if (!inlineSite.isEmpty()) return inlineSite;
        }
        return VodConfig.get().getSite(key);
    }
}
