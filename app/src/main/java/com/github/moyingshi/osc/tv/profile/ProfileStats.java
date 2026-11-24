package com.github.moyingshi.osc.tv.profile;

import android.text.TextUtils;

import com.github.moyingshi.osc.tv.bean.History;
import com.github.moyingshi.osc.tv.bean.Keep;
import com.github.moyingshi.osc.tv.db.AppDatabase;
import com.github.moyingshi.osc.tv.following.Following;
import com.github.moyingshi.osc.tv.following.FollowingAgenda;
import com.github.moyingshi.osc.tv.following.FollowingStore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的页 10 张卡片的数据，一次后台取数算完；Fragment 只负责摆出来。
 * 时间口径：本周从周一 0 点起，本月从 1 号 0 点起，最近 7 天按 7×24 小时算。
 * 在看 = 有进度且没播完的历史（position 在 0 和 duration 之间）。
 */
public final class ProfileStats {

    public static final class NameCount {
        public final String name;
        public final String pic;
        public final int count;

        NameCount(String name, String pic, int count) {
            this.name = name;
            this.pic = pic;
            this.count = count;
        }
    }

    public static final class GenreCount {
        public final String name;
        public final int count;

        GenreCount(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    public final int followingCount;
    public final int unwatchedTotal;
    public final int endedCount;
    public final Following nextAiring;
    public final List<Following> upcomingThisMonth;
    public final int keepCount;
    public final List<Keep> recentKeeps;
    public final int historyCount;
    public final List<History> recentHistory;
    public final List<History> watching;
    public final int monthTitleCount;
    public final int monthPlayCount;
    public final String monthTopTitle;
    public final int monthTopCount;
    public final int movieCount;
    public final int seriesCount;
    public final List<GenreCount> topGenres;
    public final NameCount trend1;
    public final List<NameCount> trend2;

    private ProfileStats(int followingCount, int unwatchedTotal, int endedCount, Following nextAiring,
                         List<Following> upcomingThisMonth, int keepCount, List<Keep> recentKeeps,
                         int historyCount, List<History> recentHistory, List<History> watching,
                         int monthTitleCount, int monthPlayCount, String monthTopTitle, int monthTopCount,
                         int movieCount, int seriesCount, List<GenreCount> topGenres,
                         NameCount trend1, List<NameCount> trend2) {
        this.followingCount = followingCount;
        this.unwatchedTotal = unwatchedTotal;
        this.endedCount = endedCount;
        this.nextAiring = nextAiring;
        this.upcomingThisMonth = upcomingThisMonth;
        this.keepCount = keepCount;
        this.recentKeeps = recentKeeps;
        this.historyCount = historyCount;
        this.recentHistory = recentHistory;
        this.watching = watching;
        this.monthTitleCount = monthTitleCount;
        this.monthPlayCount = monthPlayCount;
        this.monthTopTitle = monthTopTitle;
        this.monthTopCount = monthTopCount;
        this.movieCount = movieCount;
        this.seriesCount = seriesCount;
        this.topGenres = topGenres;
        this.trend1 = trend1;
        this.trend2 = trend2;
    }

    /** 必须在后台线程调用：读库 + 聚合。 */
    public static ProfileStats load() {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long monthEnd = today.plusMonths(1).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1).atStartOfDay(zone).toInstant().toEpochMilli();
        long sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000;

        // 追更
        List<Following> following = FollowingStore.list();
        int unwatchedTotal = 0;
        int endedCount = 0;
        Following nextAiring = null;
        List<Following> upcoming = new ArrayList<>();
        for (Following item : following) {
            unwatchedTotal += Math.max(0, item.unwatchedCount);
            if (FollowingAgenda.isEnded(item)) endedCount++;
            if (item.nextAirAt > now) {
                if (nextAiring == null || item.nextAirAt < nextAiring.nextAirAt) nextAiring = item;
                if (item.nextAirAt >= monthStart && item.nextAirAt < monthEnd) upcoming.add(item);
            }
        }
        upcoming.sort((a, b) -> Long.compare(a.nextAirAt, b.nextAirAt));

        // 收藏
        List<Keep> keeps = AppDatabase.get().getKeepDao().findAll();
        keeps.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
        List<Keep> recentKeeps = keeps.subList(0, Math.min(4, keeps.size()));

        // 历史（走展示口径：全局历史开关 + 跨季聚合，与历史页一致）
        List<History> history = History.getForDisplay();
        List<History> recentHistory = history.subList(0, Math.min(4, history.size()));
        List<History> watching = new ArrayList<>();
        for (History item : history) {
            if (item.getDuration() > 0 && item.getPosition() > 0 && item.getPosition() < item.getDuration()) {
                watching.add(item);
                if (watching.size() >= 4) break;
            }
        }

        // 本月：去重片名数 + 播放次数 + 看得最多的那部
        Map<String, Integer> monthCounts = new LinkedHashMap<>();
        Map<String, String> monthPics = new HashMap<>();
        int monthPlayCount = 0;
        for (History item : history) {
            if (item.getCreateTime() < monthStart) continue;
            monthPlayCount++;
            String name = item.getVodName();
            if (TextUtils.isEmpty(name)) continue;
            monthCounts.put(name, monthCounts.getOrDefault(name, 0) + 1);
            if (!monthPics.containsKey(name)) monthPics.put(name, item.getVodPic());
        }
        String monthTopTitle = null;
        int monthTopCount = 0;
        for (Map.Entry<String, Integer> entry : monthCounts.entrySet()) {
            if (entry.getValue() > monthTopCount) {
                monthTopCount = entry.getValue();
                monthTopTitle = entry.getKey();
            }
        }

        // 题材：按历史的类型名分组；电影/电视剧另计总数
        Map<String, Integer> genreCounts = new LinkedHashMap<>();
        int movieCount = 0;
        int seriesCount = 0;
        for (History item : history) {
            String type = item.getTypeName();
            if (TextUtils.isEmpty(type)) continue;
            genreCounts.put(type, genreCounts.getOrDefault(type, 0) + 1);
            if (type.contains("电影")) movieCount++;
            else if (type.contains("电视") || type.contains("剧")) seriesCount++;
        }
        List<GenreCount> topGenres = new ArrayList<>();
        List<Map.Entry<String, Integer>> sortedGenres = new ArrayList<>(genreCounts.entrySet());
        sortedGenres.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(3, sortedGenres.size()); i++) {
            topGenres.add(new GenreCount(sortedGenres.get(i).getKey(), sortedGenres.get(i).getValue()));
        }

        // Trend1 本周最常看：本周播放次数最多的那部
        NameCount trend1 = topWatched(history, weekStart);
        // Trend2 最近热门：最近 7 天播放次数前 5
        List<NameCount> trend2 = topWatchedList(history, sevenDaysAgo, 5);

        return new ProfileStats(following.size(), unwatchedTotal, endedCount, nextAiring, upcoming,
                keeps.size(), recentKeeps, history.size(), recentHistory, watching,
                monthCounts.size(), monthPlayCount, monthTopTitle, monthTopCount,
                movieCount, seriesCount, topGenres, trend1, trend2);
    }

    private static NameCount topWatched(List<History> history, long since) {
        List<NameCount> top = topWatchedList(history, since, 1);
        return top.isEmpty() ? null : top.get(0);
    }

    private static List<NameCount> topWatchedList(List<History> history, long since, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> pics = new HashMap<>();
        Map<String, Long> latest = new HashMap<>();
        for (History item : history) {
            if (item.getCreateTime() < since) continue;
            String name = item.getVodName();
            if (TextUtils.isEmpty(name)) continue;
            counts.put(name, counts.getOrDefault(name, 0) + 1);
            if (!pics.containsKey(name)) pics.put(name, item.getVodPic());
            long at = item.getCreateTime();
            if (at > latest.getOrDefault(name, 0L)) latest.put(name, at);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            if (byCount != 0) return byCount;
            return Long.compare(latest.getOrDefault(b.getKey(), 0L), latest.getOrDefault(a.getKey(), 0L));
        });
        List<NameCount> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            result.add(new NameCount(entry.getKey(), pics.get(entry.getKey()), entry.getValue()));
        }
        return result;
    }
}
