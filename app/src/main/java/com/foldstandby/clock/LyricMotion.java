package com.foldstandby.clock;

import java.util.Objects;

/** Smooth staggered scroll, scale, emphasis, and edge-fade state for a music slot's lyrics. */
final class LyricMotion {
    static final long SCROLL_DURATION_MS = 480L;
    static final long JUMP_DURATION_MS = 380L;
    static final long HIGHLIGHT_DURATION_MS = 320L;
    private static final float STAGGER_PER_ROW = 0.055f;
    private static final float MAX_STAGGER = 0.24f;

    private boolean initialized;
    private String lastTrackKey;
    private int lastIndex = -1;
    private String lastLine = "";
    private boolean lastHighlight;

    private boolean active;
    private long startMs;
    private long durationMs;
    private int stepDirection;
    private boolean multiJump;
    private boolean fromHighlight;
    private boolean toHighlight;

    void reset() {
        initialized = false;
        lastTrackKey = null;
        lastIndex = -1;
        lastLine = "";
        lastHighlight = false;
        stopAnimation();
    }

    private void stopAnimation() {
        active = false;
        startMs = 0L;
        durationMs = 0L;
        stepDirection = 0;
        multiJump = false;
        fromHighlight = lastHighlight;
        toHighlight = lastHighlight;
    }

    /**
     * Updates transition state for the current draw frame.
     * Returns true while the transition is still running and needs another animation frame.
     */
    boolean update(String trackKey, boolean live, int index, String lineText,
                   boolean highlight, long nowMs, boolean animate) {
        String safeLine = lineText == null ? "" : lineText;
        if (!animate || !initialized || !Objects.equals(trackKey, lastTrackKey)) {
            initialized = true;
            lastTrackKey = trackKey;
            lastIndex = index;
            lastLine = safeLine;
            lastHighlight = highlight;
            stopAnimation();
            return false;
        }

        boolean lineChanged = index != lastIndex || (live && !safeLine.equals(lastLine));
        if (lineChanged) {
            int delta = index - lastIndex;
            if (live && delta == 0) delta = 1;
            stepDirection = delta >= 0 ? 1 : -1;
            multiJump = Math.abs(delta) > 1;
            fromHighlight = lastHighlight;
            toHighlight = highlight;
            startMs = nowMs;
            durationMs = multiJump ? JUMP_DURATION_MS : SCROLL_DURATION_MS;
            active = true;
            lastIndex = index;
            lastLine = safeLine;
            lastHighlight = highlight;
        } else if (highlight != lastHighlight) {
            stepDirection = 0;
            multiJump = false;
            fromHighlight = lastHighlight;
            toHighlight = highlight;
            startMs = nowMs;
            durationMs = HIGHLIGHT_DURATION_MS;
            active = true;
            lastHighlight = highlight;
        }

        if (!active) return false;
        if (nowMs - startMs >= durationMs) {
            stopAnimation();
            return false;
        }
        return true;
    }

    boolean isAnimating() {
        return active;
    }

    int stepDirection() {
        return active ? stepDirection : 0;
    }

    boolean isMultiJump() {
        return active && multiJump;
    }

    float rawProgress(long nowMs) {
        if (!active || durationMs <= 0L) return 1f;
        long elapsed = nowMs - startMs;
        if (elapsed <= 0L) return 0f;
        if (elapsed >= durationMs) return 1f;
        return elapsed / (float) durationMs;
    }

    float rowProgress(long nowMs, int visualOrderFromLead) {
        if (!active) return 1f;
        float raw = rawProgress(nowMs);
        float stagger = stepDirection == 0 ? 0f : Math.min(MAX_STAGGER, Math.max(0, visualOrderFromLead) * STAGGER_PER_ROW);
        float local = stagger <= 0f ? raw : Math.max(0f, Math.min(1f, (raw - stagger) / (1f - stagger)));
        return easeOut(local);
    }

    float scrollOffset(long nowMs, int visualOrderFromLead, float stepPixels) {
        if (!active || stepDirection == 0 || stepPixels <= 0f) return 0f;
        float e = rowProgress(nowMs, visualOrderFromLead);
        float distance = multiJump ? stepPixels * 0.65f : stepPixels;
        return stepDirection * distance * (1f - e);
    }

    float currentScale(long nowMs, int visualOrderFromLead) {
        if (!active) return 1f;
        float e = rowProgress(nowMs, visualOrderFromLead);
        if (stepDirection != 0) {
            float startScale = multiJump ? 0.92f : (toHighlight ? 0.86f : 0.94f);
            return startScale + (1f - startScale) * e;
        }
        if (toHighlight && !fromHighlight) return 0.95f + 0.05f * e;
        return 1f;
    }

    float currentEmphasis(long nowMs, int visualOrderFromLead, boolean highlight) {
        if (!active) return highlight ? 1f : 0f;
        float e = rowProgress(nowMs, visualOrderFromLead);
        if (stepDirection != 0) {
            return highlight ? e : 0f;
        }
        float from = fromHighlight ? 1f : 0f;
        float to = toHighlight ? 1f : 0f;
        return from + (to - from) * e;
    }

    float departingScale(long nowMs, int visualOrderFromLead) {
        if (!active || multiJump || stepDirection == 0 || !fromHighlight) return 1f;
        float e = rowProgress(nowMs, visualOrderFromLead);
        return 1f + 0.14f * (1f - e);
    }

    float departingEmphasis(long nowMs, int visualOrderFromLead) {
        if (!active || multiJump || stepDirection == 0 || !fromHighlight) return 0f;
        float e = rowProgress(nowMs, visualOrderFromLead);
        return 1f - e;
    }

    float enteringAlpha(long nowMs, int visualOrderFromLead) {
        if (!active || stepDirection == 0) return 1f;
        return rowProgress(nowMs, visualOrderFromLead);
    }

    float jumpAlpha(long nowMs, int visualOrderFromLead) {
        if (!active || !multiJump) return 1f;
        float e = rowProgress(nowMs, visualOrderFromLead);
        return 0.25f + 0.75f * e;
    }

    float exitingAlpha(long nowMs) {
        if (!active || multiJump || stepDirection == 0) return 0f;
        float e = rowProgress(nowMs, 0);
        return Math.max(0f, 1f - e * 1.25f);
    }

    static float easeOut(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float inv = 1f - t;
        return (float) (1.0 - inv * inv * inv * Math.sqrt(inv));
    }

    static int blendArgb(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24) & 0xff) + (((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * clamped);
        int r = Math.round(((from >>> 16) & 0xff) + (((to >>> 16) & 0xff) - ((from >>> 16) & 0xff)) * clamped);
        int g = Math.round(((from >>> 8) & 0xff) + (((to >>> 8) & 0xff) - ((from >>> 8) & 0xff)) * clamped);
        int b = Math.round((from & 0xff) + ((to & 0xff) - (from & 0xff)) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static int withAlpha(int color, float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        int a = Math.round(((color >>> 24) & 0xff) * clamped);
        return (a << 24) | (color & 0x00ffffff);
    }
}
