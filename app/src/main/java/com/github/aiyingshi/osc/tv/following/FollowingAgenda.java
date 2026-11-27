package com.github.aiyingshi.osc.tv.following;

import android.content.Context;

import com.github.aiyingshi.osc.tv.R;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 追更日历分组：把 {@link Following#nextAirAt} 折算成日期桶，供追更页的日期 Tab /
 * 日期条 / 时间轴分组用。nextAirAt 是 TMDB air_date 当地 20:00
 *（{@link FollowingMetadataClient#airTime}），所以播出时刻只看日期部分，时刻固定 20:00。
 * 已过播出日但未看的剧算进“今天”，即催看；没有播出日期的进“待定”，只在“全部”里出现。
 */
public final class FollowingAgenda {

    public enum Bucket {
        TODAY, TOMORROW, WEEK, FUTURE, UNSCHEDULED
    }

    /** 日期条显示的天数。 */
    public static final int STRIP_DAYS = 14;

    private FollowingAgenda() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    public static long dayStartMillis(LocalDate date) {
        if (date == null) return 0;
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 播出日期当天的 0 点毫秒值；没有播出日期返回 0。 */
    public static long airDayStart(Following item) {
        if (item == null || item.nextAirAt <= 0) return 0;
        return dayStartMillis(LocalDate.ofInstant(
                java.time.Instant.ofEpochMilli(item.nextAirAt), ZoneId.systemDefault()));
    }

    public static Bucket bucketFor(Following item) {
        long airDay = airDayStart(item);
        if (airDay <= 0) return Bucket.UNSCHEDULED;
        LocalDate now = today();
        long todayStart = dayStartMillis(now);
        if (airDay <= todayStart) return Bucket.TODAY;
        if (airDay == dayStartMillis(now.plusDays(1))) return Bucket.TOMORROW;
        if (airDay < dayStartMillis(now.plusDays(7))) return Bucket.WEEK;
        return Bucket.FUTURE;
    }

    public static boolean isEnded(Following item) {
        if (item == null) return false;
        return FollowingMetadataSnapshot.ENDED.equals(item.officialStatus)
                || FollowingMetadataSnapshot.CANCELED.equals(item.officialStatus);
    }

    /** Calendar.DAY_OF_WEEK（1=周日..7=周六），与 R.array.following_weekdays 的下标对应（-1）。 */
    public static int weekdayOf(long dayStartMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dayStartMillis);
        return calendar.get(Calendar.DAY_OF_WEEK);
    }

    /**
     * 剧卡播出时间线文案，如“今天 20:00 · 第 2 季第 5 集”；已完结或没有播出信息返回 null，
     * 调用方直接隐藏这一行（不刷“待定”噪音）。
     */
    public static String airLineText(Context context, Following item) {
        if (context == null || item == null || isEnded(item)
                || item.nextAirAt <= 0 || item.nextAirSeason <= 0 || item.nextAirEpisode <= 0) {
            return null;
        }
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(item.nextAirAt));
        long airDay = airDayStart(item);
        long todayStart = dayStartMillis(today());
        if (airDay <= todayStart) {
            return context.getString(R.string.following_agenda_air_today,
                    time, item.nextAirSeason, item.nextAirEpisode);
        }
        if (airDay == dayStartMillis(today().plusDays(1))) {
            return context.getString(R.string.following_agenda_air_tomorrow,
                    time, item.nextAirSeason, item.nextAirEpisode);
        }
        String date = new SimpleDateFormat("M月d日", Locale.getDefault())
                .format(new Date(item.nextAirAt));
        return context.getString(R.string.following_agenda_air_date,
                date, time, item.nextAirSeason, item.nextAirEpisode);
    }

    /** “M月d日”格式的日期短串，供时间轴分组头用。 */
    public static String shortDate(long dayStartMillis) {
        return new SimpleDateFormat("M月d日", Locale.getDefault())
                .format(new Date(dayStartMillis));
    }
}
