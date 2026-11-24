package com.github.moyingshi.osc.tv.following;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.ui.activity.FollowingActivity;

public final class FollowingNotifier {

    public static final String CHANNEL_ID = "webhtv.following.updates";
    public static final String REMINDER_CHANNEL_ID = "webhtv.following.reminders";

    private FollowingNotifier() {
    }

    public static void createChannel() {
        NotificationManagerCompat manager = NotificationManagerCompat.from(App.get());
        manager.createNotificationChannel(new NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(App.get().getString(R.string.following_channel_name))
                .setDescription(App.get().getString(R.string.following_channel_description))
                .build());
    }

    public static void createReminderChannel() {
        NotificationManagerCompat manager = NotificationManagerCompat.from(App.get());
        manager.createNotificationChannel(new NotificationChannelCompat.Builder(REMINDER_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(App.get().getString(R.string.following_reminder_channel_name))
                .setDescription(App.get().getString(R.string.following_reminder_channel_description))
                .build());
    }

    /** 播出前提醒：标题是剧名，正文复用追更页的播出时间线；点开进追更页。 */
    public static boolean notifyReminder(Following item) {
        // 用户正看着 App 时不打扰，追更页上已经能看到播出时间。
        if (App.activity() != null) return false;
        if (item == null || !item.enabled || !item.notifyEnabled || !canNotify()) return false;
        String airLine = FollowingAgenda.airLineText(App.get(), item);
        if (airLine == null) return false;
        createReminderChannel();
        Intent intent = FollowingActivity.intent(App.get(), item.identityKey);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(App.get(), item.identityKey.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(App.get(), REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home_following)
                .setContentTitle(App.get().getString(R.string.following_reminder_title, item.vodName))
                .setContentText(airLine)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(airLine))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        // 通知 ID 与更新通知分开，提醒不会顶掉新集通知。
        NotificationManagerCompat.from(App.get()).notify(("reminder:" + item.identityKey).hashCode(), builder.build());
        return true;
    }

    public static boolean canNotify() {
        return FollowingSettings.isEnabled() && FollowingSettings.isNotificationsEnabled()
                && ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                && NotificationManagerCompat.from(App.get()).areNotificationsEnabled();
    }

    public static boolean notifyUpdate(Following item, FollowingSource source) {
        // Keep the database-derived site state fresh while the user is already looking at the app;
        // defer the system notification until the next background check.
        if (App.activity() != null) return false;
        if (item == null || !FollowingUpdatePolicy.shouldNotify(item) || !canNotify()) return false;
        createChannel();
        Intent intent = FollowingActivity.intent(App.get(), item.identityKey);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(App.get(), item.identityKey.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = App.get().getString(R.string.following_notification_text,
                FollowingUpdatePolicy.releasedEpisode(item),
                source == null ? 0 : source.playableEpisode);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(App.get(), CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home_following)
                .setContentTitle(App.get().getString(R.string.following_notification_title, item.vodName))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManagerCompat.from(App.get()).notify(item.identityKey.hashCode(), builder.build());
        FollowingUpdatePolicy.markNotified(item, System.currentTimeMillis());
        return true;
    }
}
