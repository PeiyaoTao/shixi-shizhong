package com.foldstandby.clock;

/** Continuous perspective for every page depth; remote pages never collapse to one layer. */
final class CarouselDepth {
    private CarouselDepth() {}
    static float scale(float distance) {
        float depth = Math.abs(distance);
        return 1f / (1f + .30f * depth + .05f * depth * depth);
    }
    static float alpha(float distance) { return (float) Math.exp(-.78f * Math.abs(distance)); }
    static float rotation(float distance) { return (float) (-48f * Math.tanh(distance * .48f)); }
    static float shift(float distance) { return -Math.signum(distance) * (1f - scale(distance)) * .5f; }
    static float focus(float distance) { return (float) Math.exp(-2.2f * distance * distance); }
}
