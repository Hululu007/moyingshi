package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;
import android.text.TextUtils;

import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;

import java.util.regex.Pattern;

/**
 * 海阔小程序播放入口（第 5 步）：详情页点选集后，按详情切分结果拼出 Vod，
 * 调现有 VideoActivity.startHk 进播放器。只做组装与转交，不解析地址、
 * 不碰播放器内部。
 * <p>
 * 只放 mobile 源集，TV/leanback 不可见。
 */
public final class HkPlayer {

    private HkPlayer() {
    }

    private static final Pattern HASH_MARKER = Pattern.compile("#[^#]*#");

    /**
     * 点选集进播放。detailUrl 是详情页地址，用作 Vod id（同一规则下按剧区分，
     * 历史键稳定）。playFrom/playUrl 按 Vod.setFlags() 的切分规则拼：
     * $$$ 分线路、# 分集、第一个 $ 分集名/地址。
     */
    public static void play(Activity activity, HkRule rule, HkDetailHelper.Detail detail,
                            HkDetailHelper.Line line, HkCard episode, String detailUrl) {
        if (activity == null || rule == null || detail == null || line == null || episode == null) return;
        Vod vod = buildVod(rule, detail, detailUrl);
        VideoActivity.startHk(activity, vod, line.title, episode.getTitle(), episode.getUrl());
    }

    /**
     * 拼详情 Vod（包内可见，供 HkNonVideo 给阅读器复用；与 play() 同款组装，
     * Vod id 是详情页地址，线路/选集按 $$$ / # / $ 切分，历史键稳定）。
     */
    static Vod buildVod(HkRule rule, HkDetailHelper.Detail detail, String detailUrl) {
        Vod vod = new Vod();
        vod.setSite(HkStore.siteOf(rule));
        vod.setId(detailUrl == null ? "" : detailUrl);
        vod.setName(detail.info.title);
        vod.setPic(detail.info.pic);
        vod.setContent(detail.info.desc);
        vod.setPlayFrom(joinLines(detail));
        vod.setPlayUrl(joinEpisodes(detail));
        vod.setFlags();
        return vod;
    }

    private static String joinLines(HkDetailHelper.Detail detail) {
        StringBuilder sb = new StringBuilder();
        for (HkDetailHelper.Line line : detail.lines) {
            if (sb.length() > 0) sb.append("$$$");
            sb.append(line.title == null ? "" : line.title);
        }
        return sb.toString();
    }

    private static String joinEpisodes(HkDetailHelper.Detail detail) {
        StringBuilder sb = new StringBuilder();
        for (HkDetailHelper.Line line : detail.lines) {
            if (sb.length() > 0) sb.append("$$$");
            StringBuilder lineSb = new StringBuilder();
            for (HkCard ep : line.episodes) {
                if (lineSb.length() > 0) lineSb.append('#');
                String name = ep.getTitle();
                if (TextUtils.isEmpty(name)) name = "";
                lineSb.append(name).append('$').append(stripHashMarkers(ep.getUrl()));
            }
            sb.append(lineSb);
        }
        return sb.toString();
    }

    /** 剥 #...# 标记段：Flag.splitEpisodes 会在 # 处切分，带标记的选集地址会被切碎。 */
    private static String stripHashMarkers(String url) {
        if (TextUtils.isEmpty(url)) return "";
        return HASH_MARKER.matcher(url.trim()).replaceAll("");
    }
}
