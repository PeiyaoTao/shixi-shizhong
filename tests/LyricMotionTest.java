package com.foldstandby.clock;

public final class LyricMotionTest {
    public static void main(String[] args) {
        LyricMotion motion = new LyricMotion();

        // First draw snaps immediately with settled state
        require(!motion.update("trackA", false, 1, "第一句", true, 1000L, true), "first draw does not animate");
        require(!motion.isAnimating() && motion.scrollOffset(1000L, 0, 80f) == 0f, "settled offset is zero");
        require(motion.currentScale(1000L, 0) == 1f && motion.currentEmphasis(1000L, 0, true) == 1f, "settled hero state");

        // Forward single-line step triggers staggered upward glide & emphasis cross-fade
        require(motion.update("trackA", false, 2, "第二句", true, 2000L, true), "forward step starts animation");
        require(motion.isAnimating() && motion.stepDirection() == 1 && !motion.isMultiJump(), "forward single-line state");
        require(Math.abs(motion.scrollOffset(2000L, 0, 80f) - 80f) < 0.01f, "starts shifted down by one row");
        require(motion.currentScale(2000L, 1) < 0.9f && motion.departingScale(2000L, 0) > 1.1f, "hero grows while previous shrinks");
        require(motion.exitingAlpha(2000L) == 1f, "exiting top line starts visible");

        // Mid-transition stagger: leading row moves ahead of trailing rows
        long mid = 2160L;
        require(motion.update("trackA", false, 2, "第二句", true, mid, true), "still animating mid-way");
        float lead = motion.rowProgress(mid, 0);
        float trail = motion.rowProgress(mid, 3);
        require(lead > trail && trail > 0f && lead < 1f, "rows cascade with smooth stagger");
        require(motion.scrollOffset(mid, 0, 80f) > 0f && motion.scrollOffset(mid, 0, 80f) < 80f, "offset glides smoothly toward zero");
        require(motion.currentEmphasis(mid, 1, true) > 0f && motion.departingEmphasis(mid, 0) > 0f, "both lines cross-fade emphasis");

        // Settles cleanly at SCROLL_DURATION_MS
        long done = 2000L + LyricMotion.SCROLL_DURATION_MS;
        require(!motion.update("trackA", false, 2, "第二句", true, done, true), "completes at duration");
        require(!motion.isAnimating() && motion.scrollOffset(done, 0, 80f) == 0f && motion.exitingAlpha(done) == 0f, "fully settled");

        // Backward single-line step glides downward
        require(motion.update("trackA", false, 1, "第一句", true, 3000L, true), "backward step animates");
        require(motion.stepDirection() == -1 && motion.scrollOffset(3000L, 0, 80f) < -79f, "backward step shifts from above");

        // Multi-line seek uses compact jump with fade-in and no departing hero emphasis
        require(motion.update("trackA", false, 9, "第九句", true, 4000L, true), "multi-line seek animates");
        require(motion.isMultiJump() && motion.departingEmphasis(4000L, 0) == 0f, "jump skips departing hero");
        require(motion.jumpAlpha(4000L, 0) < 0.3f && motion.jumpAlpha(4200L, 0) > 0.7f, "jump fades in smoothly");

        // Highlight-only transition (intro -> active)
        motion.reset();
        motion.update("trackB", false, 0, "前奏首句", false, 5000L, true);
        require(motion.update("trackB", false, 0, "前奏首句", true, 6000L, true), "highlight toggle animates");
        require(motion.stepDirection() == 0 && motion.scrollOffset(6080L, 0, 80f) == 0f, "highlight toggle does not scroll");
        float midEmph = motion.currentEmphasis(6100L, 0, true);
        require(midEmph > 0.1f && midEmph < 0.99f, "emphasis blooms smoothly");

        // Live single-line cap (index stays 31 while text changes)
        motion.reset();
        motion.update("liveTrack", true, 31, "旧句", true, 7000L, true);
        require(motion.update("liveTrack", true, 31, "新句", true, 8000L, true), "capped live stream still animates on new text");
        require(motion.stepDirection() == 1 && !motion.isMultiJump(), "capped live line scrolls forward smoothly");

        // Track switch & animate=false snap immediately
        require(!motion.update("trackC", false, 0, "新歌首句", true, 8100L, true), "track switch snaps immediately");
        require(!motion.update("trackC", false, 1, "新歌二句", true, 8200L, false), "disabled motion snaps immediately");

        // Color helpers
        require(LyricMotion.blendArgb(0xff000000, 0xffffffff, 0.5f) == 0xff808080, "blendArgb midpoint");
        require(LyricMotion.withAlpha(0xffaabbcc, 0.5f) == 0x80aabbcc, "withAlpha halves alpha");

        System.out.println("PASS: lyric scroll stagger, scale/emphasis cross-fade, live cap, seeks, and highlight transitions.");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
