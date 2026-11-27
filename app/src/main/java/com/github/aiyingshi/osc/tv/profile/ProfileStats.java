package com.github.aiyingshi.osc.tv.profile;

import android.text.TextUtils;

import com.github.aiyingshi.osc.tv.bean.History;
import com.github.aiyingshi.osc.tv.bean.Keep;
import com.github.aiyingshi.osc.tv.db.AppDatabase;
import com.github.aiyingshi.osc.tv.following.Following;
import com.github.aiyingshi.osc.tv.following.FollowingAgenda;
import com.github.aiyingshi.osc.tv.following.FollowingStore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的页 10 张卡片的数据，一次后台取数算完；Fragment 只负责摆出来。
 * 时间口径：本周（周一）从周一 0 点起，Arcane 周从周日 0 点起；本月从 1 号 0 点起，最近 7 天按自然日算。
 * 在看 = 有进度且没播完的历史（position 在 0 和 duration 之间）。
 */
public final class ProfileStats {

    public static final class NameCount {
        public final String name;
        public final String pic;
        public final int count;
        /** 该窗口内的已看小时（刷剧 TOP3 的条形长度按它画）。 */
        public final double hours;
        /** 是否电影（TOP3 条形颜色：电影紫、剧集橙；类型未知按剧集算）。 */
        public final boolean movie;

        NameCount(String name, String pic, int count, double hours, boolean movie) {
            this.name = name;
            this.pic = pic;
            this.count = count;
            this.hours = hours;
            this.movie = movie;
        }
    }

    /** 分类走势 tile：题材名（空类型名归为“其他”）+ 近 7 天每日小时 + 上 7 天总小时。 */
    public static final class GenreTrend {
        public final String name;
        public final double last7Hours;
        public final double prev7Hours;
        /** 近 7 天每日小时，index 0 = 6 天前，index 6 = 今天。 */
        public final double[] last7Daily;

        GenreTrend(String name, double last7Hours, double prev7Hours, double[] last7Daily) {
            this.name = name;
            this.last7Hours = last7Hours;
            this.prev7Hours = prev7Hours;
            this.last7Daily = last7Daily;
        }
    }

    /** 类型分布：题材 + 全量已看小时（堆叠条按它分段）。 */
    public static final class GenreHours {
        public final String name;
        public final double hours;

        GenreHours(String name, double hours) {
            this.name = name;
            this.hours = hours;
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
    /** 全部展示口径历史（观看活动面板用）。 */
    public final List<History> allHistory;
    public final int movieCount;
    public final int seriesCount;
    /** 今天 / 本周 / 本月小时与分布、近 70 天每日小时（Arcane 版式：点图、曲线、贡献格用）。 */
    public final double todayHours;
    public final int todayMovies;
    public final int todaySeries;
    public final double[] todayHourly;
    public final double weekHours;
    public final int weekMovies;
    public final int weekSeries;
    public final double[] weekDaily;
    public final double monthHours;
    public final int monthMovies;
    public final int monthSeries;
    public final double[] monthDailySeries;
    public final double[] monthDailyMovies;
    public final double lastMonthHours;
    public final List<NameCount> monthTop3;
    /** 近 70 天每日已看小时，index 0 = 69 天前，index 69 = 今天（贡献格行优先，5 行 × 14 列）。 */
    public final double[] dailyHours;
    /** 本周（周日–周六，Arcane 的周）：总小时、电影/剧集数、按周日=0 的每日小时。 */
    public final double weekHoursSun;
    public final int weekMoviesSun;
    public final int weekSeriesSun;
    public final double[] weekDailySun;
    /** 最近 7 天 / 上 7 天：总小时与最近 7 天每日小时（index 0 = 6 天前）。 */
    public final double last7Hours;
    public final double prev7Hours;
    public final double[] last7Daily;
    /** 分类走势 tile：近 7 天小时前 2 的题材（无数据时为空）。 */
    public final List<GenreTrend> genreTrends;
    /** 类型分布：全量小时前 3 的题材 + 全量题材总小时。 */
    public final List<GenreHours> genreHours;
    public final double genreHoursTotal;

    private ProfileStats(int followingCount, int unwatchedTotal, int endedCount, Following nextAiring,
                         List<Following> upcomingThisMonth, int keepCount, List<Keep> recentKeeps,
                         int historyCount, List<History> recentHistory, List<History> watching,
                         List<History> allHistory,
                         int movieCount, int seriesCount,
                         double todayHours, int todayMovies, int todaySeries, double[] todayHourly,
                         double weekHours, int weekMovies, int weekSeries, double[] weekDaily,
                         double monthHours, int monthMovies, int monthSeries,
                         double[] monthDailySeries, double[] monthDailyMovies,
                         double lastMonthHours, List<NameCount> monthTop3, double[] dailyHours,
                         double weekHoursSun, int weekMoviesSun, int weekSeriesSun, double[] weekDailySun,
                         double last7Hours, double prev7Hours, double[] last7Daily,
                         List<GenreTrend> genreTrends, List<GenreHours> genreHours, double genreHoursTotal) {
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
        this.allHistory = allHistory;
        this.movieCount = movieCount;
        this.seriesCount = seriesCount;
        this.todayHours = todayHours;
        this.todayMovies = todayMovies;
        this.todaySeries = todaySeries;
        this.todayHourly = todayHourly;
        this.weekHours = weekHours;
        this.weekMovies = weekMovies;
        this.weekSeries = weekSeries;
        this.weekDaily = weekDaily;
        this.monthHours = monthHours;
        this.monthMovies = monthMovies;
        this.monthSeries = monthSeries;
        this.monthDailySeries = monthDailySeries;
        this.monthDailyMovies = monthDailyMovies;
        this.lastMonthHours = lastMonthHours;
        this.monthTop3 = monthTop3;
        this.dailyHours = dailyHours;
        this.weekHoursSun = weekHoursSun;
        this.weekMoviesSun = weekMoviesSun;
        this.weekSeriesSun = weekSeriesSun;
        this.weekDailySun = weekDailySun;
        this.last7Hours = last7Hours;
        this.prev7Hours = prev7Hours;
        this.last7Daily = last7Daily;
        this.genreTrends = genreTrends;
        this.genreHours = genreHours;
        this.genreHoursTotal = genreHoursTotal;
    }

    /** 必须在后台线程调用：读库 + 聚合。 */
    public static ProfileStats load() {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long monthEnd = today.plusMonths(1).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1).atStartOfDay(zone).toInstant().toEpochMilli();
        long sunWeekStart = today.minusDays(today.getDayOfWeek().getValue() % 7).atStartOfDay(zone).toInstant().toEpochMilli();

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

        // 电影/剧集总数（全量历史按类型名计；类型分布另按小时算）
        int movieCount = 0;
        int seriesCount = 0;
        for (History item : history) {
            String type = item.getTypeName();
            if (type == null) continue;
            if (type.contains("电影")) movieCount++;
            else if (type.contains("电视") || type.contains("剧")) seriesCount++;
        }

        // 今天 / 本周 / 本月小时与分布 + 近 70 天每日小时 + 周日周 + 最近 7 天 + 题材小时
        long todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long lastMonthStart = today.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli();
        int daysInMonth = today.lengthOfMonth();
        double todayHours = 0;
        int todayMovies = 0;
        int todaySeries = 0;
        double[] todayHourly = new double[24];
        double weekHours = 0;
        int weekMovies = 0;
        int weekSeries = 0;
        double[] weekDaily = new double[7];
        double monthHours = 0;
        int monthMovies = 0;
        int monthSeries = 0;
        double[] monthDailySeries = new double[daysInMonth];
        double[] monthDailyMovies = new double[daysInMonth];
        double lastMonthHours = 0;
        double[] dailyHours = new double[70];
        LocalDate start70 = today.minusDays(69);
        double weekHoursSun = 0;
        int weekMoviesSun = 0;
        int weekSeriesSun = 0;
        double[] weekDailySun = new double[7];
        double last7Hours = 0;
        double prev7Hours = 0;
        double[] last7Daily = new double[7];
        Map<String, double[]> genreLast7 = new HashMap<>();
        Map<String, Double> genrePrev7 = new HashMap<>();
        Map<String, Double> genreAllHours = new HashMap<>();
        for (History item : history) {
            long at = item.getCreateTime();
            double hours = watchedHours(item);
            String type = item.getTypeName();
            boolean isMovie = type != null && type.contains("电影");
            boolean isSeries = type != null && (type.contains("电视") || type.contains("剧"));
            LocalDate date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
            int dayIndex = (int) ChronoUnit.DAYS.between(start70, date);
            if (dayIndex >= 0 && dayIndex < dailyHours.length) dailyHours[dayIndex] += hours;
            if (at >= todayStart) {
                todayHours += hours;
                if (isMovie) todayMovies++;
                else if (isSeries) todaySeries++;
                todayHourly[Instant.ofEpochMilli(at).atZone(zone).getHour()] += hours;
            }
            if (at >= weekStart) {
                weekHours += hours;
                if (isMovie) weekMovies++;
                else if (isSeries) weekSeries++;
                weekDaily[date.getDayOfWeek().getValue() - 1] += hours;
            }
            if (at >= sunWeekStart) {
                weekHoursSun += hours;
                if (isMovie) weekMoviesSun++;
                else if (isSeries) weekSeriesSun++;
                weekDailySun[date.getDayOfWeek().getValue() % 7] += hours;
            }
            long daysAgo = ChronoUnit.DAYS.between(date, today);
            String genre = TextUtils.isEmpty(type) ? "其他" : type;
            if (daysAgo >= 0 && daysAgo < 7) {
                last7Hours += hours;
                last7Daily[6 - (int) daysAgo] += hours;
                double[] g = genreLast7.get(genre);
                if (g == null) {
                    g = new double[7];
                    genreLast7.put(genre, g);
                }
                g[6 - (int) daysAgo] += hours;
            } else if (daysAgo >= 7 && daysAgo < 14) {
                prev7Hours += hours;
                genrePrev7.put(genre, genrePrev7.getOrDefault(genre, 0.0) + hours);
            }
            genreAllHours.put(genre, genreAllHours.getOrDefault(genre, 0.0) + hours);
            if (at >= monthStart && at < monthEnd) {
                monthHours += hours;
                if (isMovie) monthMovies++;
                else if (isSeries) monthSeries++;
                int day = date.getDayOfMonth() - 1;
                if (isMovie) monthDailyMovies[day] += hours;
                else if (isSeries) monthDailySeries[day] += hours;
            } else if (at >= lastMonthStart && at < monthStart) {
                lastMonthHours += hours;
            }
        }
        // 刷剧 TOP3：本月已看小时前 3（条形按小时画；并列时播放次数多者优先，再按最近一次）
        List<NameCount> monthTop3 = topWatchedList(history, monthStart, 3);
        // 分类走势：近 7 天小时前 2 的题材
        List<GenreTrend> genreTrends = new ArrayList<>();
        List<Map.Entry<String, double[]>> sortedTrendGenres = new ArrayList<>(genreLast7.entrySet());
        sortedTrendGenres.sort((a, b) -> Double.compare(sum(b.getValue()), sum(a.getValue())));
        for (Map.Entry<String, double[]> entry : sortedTrendGenres) {
            if (genreTrends.size() >= 2) break;
            double last7 = sum(entry.getValue());
            if (last7 <= 0) break;
            genreTrends.add(new GenreTrend(entry.getKey(), last7,
                    genrePrev7.getOrDefault(entry.getKey(), 0.0), entry.getValue()));
        }
        // 类型分布：全量小时前 3 的题材
        List<GenreHours> genreHours = new ArrayList<>();
        List<Map.Entry<String, Double>> sortedGenres = new ArrayList<>(genreAllHours.entrySet());
        sortedGenres.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double genreHoursTotal = 0;
        for (Map.Entry<String, Double> entry : genreAllHours.entrySet()) genreHoursTotal += entry.getValue();
        for (int i = 0; i < Math.min(3, sortedGenres.size()); i++) {
            if (sortedGenres.get(i).getValue() <= 0) break;
            genreHours.add(new GenreHours(sortedGenres.get(i).getKey(), sortedGenres.get(i).getValue()));
        }

        return new ProfileStats(following.size(), unwatchedTotal, endedCount, nextAiring, upcoming,
                keeps.size(), recentKeeps, history.size(), recentHistory, watching, history,
                movieCount, seriesCount,
                todayHours, todayMovies, todaySeries, todayHourly,
                weekHours, weekMovies, weekSeries, weekDaily,
                monthHours, monthMovies, monthSeries, monthDailySeries, monthDailyMovies,
                lastMonthHours, monthTop3, dailyHours,
                weekHoursSun, weekMoviesSun, weekSeriesSun, weekDailySun,
                last7Hours, prev7Hours, last7Daily,
                genreTrends, genreHours, genreHoursTotal);
    }

    /** 已看小时：有进度按进度算，看完按全长算；没进度记 0。 */
    private static double watchedHours(History item) {
        if (item.getDuration() <= 0 || item.getPosition() <= 0) return 0;
        return Math.min(item.getPosition(), item.getDuration()) / 3600000.0;
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double v : values) total += v;
        return total;
    }

    private static List<NameCount> topWatchedList(List<History> history, long since, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> pics = new HashMap<>();
        Map<String, Long> latest = new HashMap<>();
        Map<String, Double> hours = new HashMap<>();
        Map<String, Boolean> movies = new HashMap<>();
        for (History item : history) {
            if (item.getCreateTime() < since) continue;
            String name = item.getVodName();
            if (TextUtils.isEmpty(name)) continue;
            counts.put(name, counts.getOrDefault(name, 0) + 1);
            if (!pics.containsKey(name)) pics.put(name, item.getVodPic());
            if (!movies.containsKey(name)) {
                movies.put(name, item.getTypeName() != null && item.getTypeName().contains("电影"));
            }
            long at = item.getCreateTime();
            if (at > latest.getOrDefault(name, 0L)) latest.put(name, at);
            hours.put(name, hours.getOrDefault(name, 0.0) + watchedHours(item));
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int byHours = Double.compare(hours.getOrDefault(b.getKey(), 0.0), hours.getOrDefault(a.getKey(), 0.0));
            if (byHours != 0) return byHours;
            int byCount = Integer.compare(b.getValue(), a.getValue());
            if (byCount != 0) return byCount;
            return Long.compare(latest.getOrDefault(b.getKey(), 0L), latest.getOrDefault(a.getKey(), 0L));
        });
        List<NameCount> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            result.add(new NameCount(entry.getKey(), pics.get(entry.getKey()), entry.getValue(),
                    hours.getOrDefault(entry.getKey(), 0.0), movies.getOrDefault(entry.getKey(), false)));
        }
        return result;
    }
}
