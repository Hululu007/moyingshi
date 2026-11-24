package com.github.moyingshi.osc.tv.following;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 播出提醒的到点执行器：排队时写进任务数据的集号必须仍是下一集、且没提醒过、
 * 播出时间还没过，才推通知；否则静默成功（集数已推进时下一次检查会重排提醒）。
 */
public class FollowingReminderWorker extends Worker {

    public FollowingReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!FollowingSettings.isEnabled() || !FollowingSettings.isReminderEnabled()) return Result.success();
        String identityKey = getInputData().getString(FollowingReminderScheduler.KEY_IDENTITY);
        int episode = getInputData().getInt(FollowingReminderScheduler.KEY_EPISODE, 0);
        if (TextUtils.isEmpty(identityKey) || episode <= 0) return Result.success();
        Following item = FollowingStore.find(identityKey);
        if (item == null || !item.enabled || !item.notifyEnabled) return Result.success();
        if (FollowingAgenda.isEnded(item)) return Result.success();
        if (item.nextAirEpisode != episode) return Result.success();
        if (FollowingReminderScheduler.firedEpisode(identityKey) == episode) return Result.success();
        long now = System.currentTimeMillis();
        if (item.nextAirAt <= 0 || now >= item.nextAirAt) return Result.success();
        // 先记已提醒，再推通知：用户正看着 App 或通知没开时也不再为同一集补推。
        FollowingReminderScheduler.markFired(identityKey, episode);
        FollowingNotifier.notifyReminder(item);
        return Result.success();
    }
}
