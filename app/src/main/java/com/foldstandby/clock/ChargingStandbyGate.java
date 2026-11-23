package com.foldstandby.clock;

/** Tracks a single charging/lock cycle, including an explicit dismissal. */
final class ChargingStandbyGate {
    static final long SETTLE_MS = 3000;
    private long readySince = -1;
    private boolean dismissed;

    void reset() {
        readySince = -1;
        dismissed = false;
    }

    void dismiss() {
        readySince = -1;
        dismissed = true;
    }

    boolean update(boolean plugged, boolean locked, boolean still, long now) {
        if (!plugged || !locked) {
            reset();
            return false;
        }
        if (dismissed || !still) {
            readySince = -1;
            return false;
        }
        if (readySince < 0) readySince = now;
        return now - readySince >= SETTLE_MS;
    }

    boolean dismissed() { return dismissed; }
}
