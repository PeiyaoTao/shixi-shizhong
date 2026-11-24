package com.foldstandby.clock;

public final class FocusDurationTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(FocusDuration.clamp(-5) == 1, "negative minutes clamp to 1");
        check(FocusDuration.clamp(0) == 1, "zero minutes clamp to 1");
        check(FocusDuration.clamp(1) == 1, "min bound preserved");
        check(FocusDuration.clamp(25) == 25, "default minutes preserved");
        check(FocusDuration.clamp(1439) == 1439, "max bound preserved");
        check(FocusDuration.clamp(2000) == 1439, "overflow minutes clamp to 23:59");

        check(FocusDuration.toMinutes(0, 0) == 0, "00:00 stays 0 for picker validation");
        check(FocusDuration.toMinutes(1, 35) == 95, "1h35m converts to 95 minutes");
        check(FocusDuration.toMinutes(23, 59) == 1439, "23h59m converts to 1439 minutes");
        check(FocusDuration.toMinutes(-1, 70) == 59, "toMinutes clamps hours and minutes");

        int[] samples = { 1, 25, 60, 95, 1439 };
        for (int mins : samples) {
            int h = FocusDuration.hoursOf(mins);
            int m = FocusDuration.minutesOf(mins);
            check(FocusDuration.toMinutes(h, m) == mins, "round-trip hours/minutes for " + mins);
        }

        check("0 分钟".equals(FocusDuration.label(0)), "zero label");
        check("25 分钟".equals(FocusDuration.label(25)), "minutes-only label");
        check("1 小时".equals(FocusDuration.label(60)), "exact-hours label");
        check("1 小时 30 分钟".equals(FocusDuration.label(90)), "hours-and-minutes label");

        check("共 25 分钟".equals(FocusDuration.totalLabel(1500000L)), "totalLabel 25m");
        check("共 1 小时 30 分钟".equals(FocusDuration.totalLabel(5400000L)), "totalLabel 1h30m");

        check("00:00".equals(FocusDuration.formatCountdown(0L)), "countdown 0s");
        check("25:00".equals(FocusDuration.formatCountdown(1500000L)), "countdown 25m");
        check("59:59".equals(FocusDuration.formatCountdown(3599000L)), "countdown 59m59s");
        check("1:00:00".equals(FocusDuration.formatCountdown(3600000L)), "countdown 1h");
        check("1:30:05".equals(FocusDuration.formatCountdown(5405000L)), "countdown 1h30m5s");
        check("23:59:00".equals(FocusDuration.formatCountdown(86340000L)), "countdown 23h59m");

        assertArray(FocusDuration.flipTiles(1500000L), new String[] { "25", "分钟", "00", "秒" }, "flipTiles 25m");
        assertArray(FocusDuration.flipTiles(3599000L), new String[] { "59", "分钟", "59", "秒" }, "flipTiles 59m59s");
        assertArray(FocusDuration.flipTiles(3600000L), new String[] { "01", "小时", "00", "分钟" }, "flipTiles 1h");
        assertArray(FocusDuration.flipTiles(5700000L), new String[] { "01", "小时", "35", "分钟" }, "flipTiles 1h35m");

        System.out.println("PASS: FocusDurationTest");
    }

    private static void assertArray(String[] actual, String[] expected, String label) {
        check(actual.length == expected.length, label + " length");
        for (int i = 0; i < expected.length; i++) {
            check(expected[i].equals(actual[i]), label + " [" + i + "] expected " + expected[i] + " got " + actual[i]);
        }
    }
}
