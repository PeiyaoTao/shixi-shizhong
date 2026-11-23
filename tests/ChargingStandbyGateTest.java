package com.foldstandby.clock;

public final class ChargingStandbyGateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ChargingStandbyGate gate = new ChargingStandbyGate();
        check(!gate.update(false, true, true, 0), "unplugged is ineligible");
        check(!gate.update(true, false, true, 200), "unlocked is ineligible");
        check(!gate.update(true, true, true, 300), "first stable locked sample starts settling");
        check(!gate.update(true, true, false, 3200), "motion resets settling");
        check(!gate.update(true, true, true, 3500), "second settling period starts");
        check(!gate.update(true, true, true, 6499), "settling uses the full interval");
        check(gate.update(true, true, true, 6500), "plugged and locked settled");
        gate.dismiss();
        check(!gate.update(true, true, true, 20000), "explicit exit stays dismissed while still plugged and locked");
        check(!gate.update(false, true, true, 20100), "unplug exits and rearms");
        check(!gate.update(true, true, true, 20200) && gate.update(true, true, true, 23200), "plug cycle retriggers after settling");
        gate.dismiss();
        check(!gate.update(true, false, true, 25000), "unlock exits and rearms");
        check(!gate.update(true, true, true, 26000) && gate.update(true, true, true, 29000), "next lock cycle retriggers");
        System.out.println("PASS: charging and lock requirements, motion debounce, dismissal, and retriggering");
    }
}
