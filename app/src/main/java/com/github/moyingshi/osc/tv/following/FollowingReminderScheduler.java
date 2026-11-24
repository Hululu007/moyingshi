package com.github.moyingshi.osc.tv.following;

import android.content.Context;
import android.text.TextUtils;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;

import com.github.catvod.utils.Prefers;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.utils.Task;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 播出提醒调度：每部在追剧集一个 WorkManager 一次性延迟任务，到点跑
 * {@link FollowingReminderWorker}。不走 AlarmManager——Manifest 没有
 * SCHEDULE_EXACT_ALARM，Android 12+ 上精确闹钟要用户去设置里另开；WorkManager
 * 无需新权限、重启后自己恢复，定时可能差几分钟，追剧提醒可以接受。
 * 提醒目标 = nextAirAt - 提前分钟数；单部开关复用 notifyEnabled（提醒开 =
 * 更新通知 + 播出提醒都开）；已提醒过的（剧，集）记在 Prefers 里，防重复推。
 */
public final class FollowingReminderScheduler {

    public static final String WORK_PREFIX = "webhtv.following.reminder.";
    public static final String KEY_IDENTITY = "identity_key";
    public static final String KEY_EPISODE = "episode";

    private static final String FIRED_PREFIX = "following_reminder_fired_";

    private FollowingReminderScheduler() {
    }

    public static void schedule(Context context, Following item) {
        if (context == null || item == null || TextUtils.isEmpty(item.identityKey)) return;
        // 只快照调度要用的字段，不持有可变的 Following 或 Activity。
        String identityKey = item.identityKey;
        boolean enabled = item.enabled && item.notifyEnabled
                && FollowingSettings.isEnabled() && FollowingSettings.isReminderEnabled();
        long nextAirAt = item.nextAirAt;
        int nextAirSeason = item.nextAirSeason;
        int nextAirEpisode = item.nextAirEpisode;
        boolean ended = FollowingAgenda.isEnded(item);
        FollowingWorkManager.withManager(context, manager -> {
            if (!enabled || ended || nextAirAt <= 0 || nextAirSeason <= 0 || nextAirEpisode <= 0) {
                manager.cancelUniqueWork(workName(identityKey));
                return;
            }
            if (firedEpisode(identityKey) == nextAirEpisode) {
                manager.cancelUniqueWork(workName(identityKey));
                return;
            }
            long now = System.currentTimeMillis();
            if (nextAirAt <= now) {
                // 已播出，等下一次检查把集数往前推，不再为这集排提醒。
                manager.cancelUniqueWork(workName(identityKey));
                return;
            }
            long target = nextAirAt - (long) FollowingSettings.getReminderLeadMinutes() * 60_000L;
            long delay = Math.max(0, target - now);
            Data input = new Data.Builder()
                    .putString(KEY_IDENTITY, identityKey)
                    .putInt(KEY_EPISODE, nextAirEpisode)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FollowingReminderWorker.class)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(input)
                    .addTag(FollowingScheduler.TAG)
                    .build();
            manager.enqueueUniqueWork(workName(identityKey), ExistingWorkPolicy.REPLACE, request);
        });
    }

    /** 提前分钟数改了之后，把全部在追剧集的提醒按新提前量重排。 */
    public static void scheduleAll() {
        Task.execute(() -> {
            for (Following item : FollowingStore.list()) {
                schedule(App.get(), item);
            }
        });
    }

    public static void cancel(Context context, String identityKey) {
        if (context == null || identityKey == null || identityKey.isBlank()) return;
        String key = identityKey;
        FollowingWorkManager.withManager(context, manager -> manager.cancelUniqueWork(workName(key)));
        Prefers.put(FIRED_PREFIX + key, 0);
    }

    static int firedEpisode(String identityKey) {
        return Prefers.getInt(FIRED_PREFIX + identityKey, 0);
    }

    static void markFired(String identityKey, int episode) {
        Prefers.put(FIRED_PREFIX + identityKey, episode);
    }

    private static String workName(String identityKey) {
        return String.format(Locale.US, "%s%08x", WORK_PREFIX, identityKey.hashCode());
    }
}
