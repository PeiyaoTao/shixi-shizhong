package com.foldstandby.clock;

/** Automatic folded support recognition, settling and entry/exit debounce. */
public final class PostureGate {
    public static final int NONE = 0, TENT = 1, STAND = 2;
    private static final long SAMPLE_TIMEOUT_MS = 2500;
    private long since = -1;
    private boolean candidate, active;
    private long sampledAt = -1, stableSince = -1;
    private float anchorAngle, anchorX, anchorY, anchorZ;

    public static boolean isPartlyFolded(float angle) {
        return Float.isFinite(angle) && angle >= 5 && angle <= 170;
    }

    public static int classify(float angle, float x, float y, float z) {
        if (!isPartlyFolded(angle) || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(z)) return NONE;
        double norm = Math.sqrt(x*x+y*y+z*z);
        if (norm < 7 || norm > 12) return NONE;
        // Gravity describes one half of a foldable. That half can lie flat as the base
        // while the other half is propped up. A valid partial fold is sufficient here.
        // Both signs and both in-plane axes are valid: no stored direction whitelist.
        if (Math.abs(x) / norm > .4 && Math.abs(y) / norm < .4 && z / norm < -.1)
            return TENT;
        return STAND;
    }

    public static boolean matches(float angle, float x, float y, float z) {
        return classify(angle,x,y,z) != NONE;
    }

    public void observe(float angle, float x, float y, float z, long now) {
        if (!Float.isFinite(angle) || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(z)) {
            sampledAt = stableSince = -1;
            return;
        }
        float dx=x-anchorX, dy=y-anchorY, dz=z-anchorZ;
        // Compare with a fixed anchor, not just the last sample, so slow movement accumulates.
        if (sampledAt < 0 || now < sampledAt || now-sampledAt >= SAMPLE_TIMEOUT_MS
                || Math.abs(angle-anchorAngle) >= 3 || dx*dx+dy*dy+dz*dz > .36f) {
            stableSince=now;
            anchorAngle=angle; anchorX=x; anchorY=y; anchorZ=z;
        }
        sampledAt=now;
    }

    public boolean isStill(long now) {
        return sampledAt >= 0 && now >= sampledAt && now-sampledAt < SAMPLE_TIMEOUT_MS
                && stableSince >= 0 && now-stableSince >= 1000;
    }

    public void reset() {
        since=sampledAt=stableSince=-1;
        candidate=active=false;
    }

    public boolean update(boolean matches, long now) {
        if (since < 0 || candidate != matches) { candidate = matches; since = now; }
        if (candidate != active && now - since >= (candidate ? 2200 : 900)) active = candidate;
        return active;
    }
}
