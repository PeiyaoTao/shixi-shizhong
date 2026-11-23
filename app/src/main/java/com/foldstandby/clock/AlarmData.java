package com.foldstandby.clock;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.widget.Toast;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** The system owns scheduling and ringing; scenes only store how its next alarm is displayed. */
final class AlarmData {
    static final class State {
        final long triggerAt;
        final PendingIntent showIntent;
        final boolean unavailable;
        State(long triggerAt, PendingIntent showIntent, boolean unavailable) {
            this.triggerAt = triggerAt;
            this.showIntent = showIntent;
            this.unavailable = unavailable;
        }
        boolean upcoming(long now) { return !unavailable && triggerAt > now; }
    }

    static volatile State state = new State(0, null, false);
    private static final Set<Runnable> observers = new HashSet<>();
    private static long checkedAt;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("hh:mm", Locale.ROOT);
    private static final BroadcastReceiver changes = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
            refresh(c, true);
            // Date, zone and countdown can change even if the scheduled instant is unchanged.
            notifyObservers();
        }
    };

    private AlarmData() {}

    static void refresh(Context context, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && checkedAt > 0 && now - checkedAt < 15000) return;
        checkedAt = now;
        State next;
        try {
            AlarmManager manager = context.getSystemService(AlarmManager.class);
            if (manager == null) next = new State(0, null, true);
            else {
                AlarmManager.AlarmClockInfo alarm = manager.getNextAlarmClock();
                next = alarm == null ? new State(0, null, false)
                    : new State(alarm.getTriggerTime(), alarm.getShowIntent(), false);
            }
        } catch (SecurityException error) {
            next = new State(0, null, true);
        }
        State before = state;
        state = next;
        if (before.triggerAt != next.triggerAt || before.unavailable != next.unavailable
            || !Objects.equals(before.showIntent, next.showIntent)) notifyObservers();
    }

    static void observe(Context context, Runnable observer) {
        if (observers.isEmpty()) {
            IntentFilter filter = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_TICK);
            context.getApplicationContext().registerReceiver(changes, filter);
        }
        observers.add(observer);
        refresh(context, true);
    }

    static void forget(Context context, Runnable observer) {
        if (observers.remove(observer) && observers.isEmpty()) {
            context.getApplicationContext().unregisterReceiver(changes);
        }
    }

    private static void notifyObservers() {
        for (Runnable observer : new ArrayList<>(observers)) observer.run();
    }

    static String time(ZonedDateTime alarm, boolean h24) { return alarm.format(h24 ? H24 : H12); }
    static String period(ZonedDateTime alarm) { return alarm.getHour() < 12 ? "上午" : "下午"; }
    static String date(ZonedDateTime alarm, ZonedDateTime now) {
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), alarm.toLocalDate());
        return (days == 0 ? "今天 · " : days == 1 ? "明天 · " : "") + alarm.format(DATE);
    }
    static String remaining(long triggerAt, long now) {
        long millis = Math.max(0, triggerAt - now);
        if (millis < 60000) return "即将响铃";
        long minutes = (millis + 59999) / 60000;
        long days = minutes / 1440, hours = minutes % 1440 / 60, rest = minutes % 60;
        return ((days > 0 ? days + "天 " : "") + (hours > 0 ? hours + "小时 " : "")
            + (rest > 0 ? rest + "分钟" : "")).trim();
    }
    static String status(long now) {
        State current = state;
        if (current.unavailable) return "系统闹钟暂不可用";
        if (!current.upcoming(now)) return "未设置闹钟";
        ZonedDateTime alarm = Instant.ofEpochMilli(current.triggerAt).atZone(ZoneId.systemDefault());
        return "下次响铃 · " + date(alarm, Instant.ofEpochMilli(now).atZone(alarm.getZone())) + " " + time(alarm, true);
    }

    static boolean openNext(Context context) {
        State current = state;
        if (current.upcoming(System.currentTimeMillis()) && current.showIntent != null) {
            try { current.showIntent.send(); return true; }
            catch (PendingIntent.CanceledException | SecurityException ignored) { }
        }
        return open(context, !current.unavailable && !current.upcoming(System.currentTimeMillis()));
    }

    static boolean open(Context context, boolean create) {
        // Without a time, SET_ALARM opens the clock's editor; viewing a widget never schedules an alarm.
        Intent intent = new Intent(create ? AlarmClock.ACTION_SET_ALARM : AlarmClock.ACTION_SHOW_ALARMS);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(context, "未找到可用的系统闹钟应用", Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
