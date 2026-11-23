package com.foldstandby.clock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Schedules the nearest enabled notification, then recomputes after delivery. */
public final class TodoReminders extends BroadcastReceiver {
    static final String CHANNEL = "todo_reminders";
    private static final long DELIVERY_WINDOW = TimeUnit.HOURS.toMillis(12);

    static boolean enabled(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) return false;
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static boolean precise(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
    }

    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0,
                new Intent(context, TodoReminders.class).setAction("com.foldstandby.clock.TODO_REMINDER"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        TodoStore store = TodoStore.open(context);
        long now = System.currentTimeMillis();
        long purge = store.nextTrashExpiry(), next = purge;
        if (enabled(context)) {
            for (TodoTask task : store.pendingReminders()) {
                long due = timestamp(task);
                if (due >= now - DELIVERY_WINDOW) next = Math.min(next, due);
            }
        }
        if (next == Long.MAX_VALUE) {
            alarms.cancel(pending);
            return;
        }
        long trigger = Math.max(now + 100, next);
        if (next != purge && precise(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
                return;
            } catch (SecurityException ignored) {
                // The exact alarm setting can be revoked between the check and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
    }

    static void cancel(Context context, Collection<String> ids) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) for (String id : ids) manager.cancel(id, 1);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if ("com.foldstandby.clock.TODO_REMINDER".equals(intent.getAction())) deliver(context);
        schedule(context);
    }

    private static void deliver(Context context) {
        if (!enabled(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        TodoStore store = TodoStore.open(context);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_HIGH));
        long now = System.currentTimeMillis();
        boolean showPrivate = context.getSharedPreferences("clock", Context.MODE_PRIVATE).getBoolean("private", false);
        List<TodoTask> sent = new ArrayList<>();
        for (TodoTask task : store.pendingReminders()) {
            long time = timestamp(task);
            if (time > now || time < now - DELIVERY_WINDOW) continue;
            Intent open = TodoActivity.intent(context, task.list, TodoStore.View.TODAY, task.id, false, showPrivate);
            open.setData(android.net.Uri.fromParts("shiqi-todo", task.id, null));
            PendingIntent content = PendingIntent.getActivity(context, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_clock)
                    .setContentTitle(task.title)
                    .setContentText("待办提醒")
                    .setStyle(new Notification.BigTextStyle().bigText(task.title))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(showPrivate ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_PRIVATE)
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build();
            try {
                manager.notify(task.id, 1, notification);
                sent.add(task);
            } catch (SecurityException ignored) {
                break;
            }
        }
        store.markNotified(sent);
    }

    private static long timestamp(TodoTask task) {
        return task.reminder.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
