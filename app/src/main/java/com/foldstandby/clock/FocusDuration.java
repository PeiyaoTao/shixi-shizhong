package com.foldstandby.clock;

import java.util.Locale;

/** Pure-Java helpers for custom focus durations (1 minute up to 23 hours 59 minutes). */
final class FocusDuration {
    static final int DEFAULT_MINUTES = 25;
    static final int MIN_MINUTES = 1;
    static final int MAX_HOURS = 23;
    static final int MAX_MINUTES = MAX_HOURS * 60 + 59;
    static final int[] PRESETS = { 5, 15, 25, 45, 60 };
    static final String[] PRESET_LABELS = { "5 分钟", "15 分钟", "25 分钟", "45 分钟", "1 小时" };

    private FocusDuration() {}

    static int clamp(int minutes) {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }

    static int toMinutes(int hours, int minutes) {
        int h = Math.max(0, Math.min(MAX_HOURS, hours));
        int m = Math.max(0, Math.min(59, minutes));
        return h * 60 + m;
    }

    static int hoursOf(int totalMinutes) {
        return clamp(totalMinutes) / 60;
    }

    static int minutesOf(int totalMinutes) {
        return clamp(totalMinutes) % 60;
    }

    static String label(int totalMinutes) {
        if (totalMinutes <= 0) return "0 分钟";
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        if (h == 0) return m + " 分钟";
        if (m == 0) return h + " 小时";
        return h + " 小时 " + m + " 分钟";
    }

    static String totalLabel(long totalMillis) {
        int mins = (int) Math.max(1, Math.round(totalMillis / 60000.0));
        return "共 " + label(mins);
    }

    static String formatCountdown(long leftMillis) {
        long totalSeconds = Math.max(0, leftMillis / 1000L);
        if (totalSeconds >= 3600L) {
            return String.format(
                Locale.ROOT,
                "%d:%02d:%02d",
                totalSeconds / 3600L,
                (totalSeconds % 3600L) / 60L,
                totalSeconds % 60L
            );
        }
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    static String[] flipTiles(long leftMillis) {
        long totalSeconds = Math.max(0, leftMillis / 1000L);
        if (totalSeconds >= 3600L) {
            return new String[] {
                String.format(Locale.ROOT, "%02d", totalSeconds / 3600L),
                "小时",
                String.format(Locale.ROOT, "%02d", (totalSeconds % 3600L) / 60L),
                "分钟"
            };
        }
        return new String[] {
            String.format(Locale.ROOT, "%02d", totalSeconds / 60L),
            "分钟",
            String.format(Locale.ROOT, "%02d", totalSeconds % 60L),
            "秒"
        };
    }
}
