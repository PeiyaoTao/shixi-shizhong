package com.foldstandby.clock;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Dedicated vector weather artwork with knockout occlusion and per-condition geometry. */
final class WeatherArtwork {
    enum Kind {
        UNKNOWN,
        CLEAR,
        MAINLY_CLEAR,
        PARTLY_CLOUDY,
        OVERCAST,
        FOG,
        DRIZZLE,
        FREEZING_RAIN,
        RAIN,
        HEAVY_RAIN,
        SNOW,
        RAIN_SHOWERS,
        SNOW_SHOWERS,
        THUNDERSTORM,
        THUNDERSTORM_HAIL
    }

    private static final float[] FULL_SUN_ANGLES = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f};
    private static final float[] PEEK_SUN_ANGLES = {-120f, -80f, -40f, 0f, 35f};
    private static final float[] SHOWER_SUN_ANGLES = {-120f, -80f, -40f, 0f, 30f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Shader[] gradients = new Shader[TextGradients.COLORS.length];
    private final DashPathEffect dashEffect = new DashPathEffect(new float[]{7.5f, 6f}, 0f);

    private int ink;
    private int secondary;
    private int plate;
    private int gradient;
    private float minimumStroke;

    static Kind kindOf(int code) {
        if (code < 0) return Kind.UNKNOWN;
        if (code == 0) return Kind.CLEAR;
        if (code == 1) return Kind.MAINLY_CLEAR;
        if (code == 2) return Kind.PARTLY_CLOUDY;
        if (code == 3) return Kind.OVERCAST;
        if (code <= 49) return Kind.FOG;
        if (code == 56 || code == 57 || code == 66 || code == 67) return Kind.FREEZING_RAIN;
        if (code <= 55) return Kind.DRIZZLE;
        if (code == 65) return Kind.HEAVY_RAIN;
        if (code <= 69) return Kind.RAIN;
        if (code <= 79) return Kind.SNOW;
        if (code <= 84) return Kind.RAIN_SHOWERS;
        if (code <= 89) return Kind.SNOW_SHOWERS;
        if (code == 96 || code == 99) return Kind.THUNDERSTORM_HAIL;
        return Kind.THUNDERSTORM;
    }

    void palette(int primary, int secondary, int plate, int gradient) {
        this.ink = primary;
        this.secondary = secondary;
        this.plate = plate;
        this.gradient = gradient;
        if (gradient > 0 && gradients[gradient] == null) {
            gradients[gradient] = TextGradients.shader(gradient, -72f, -70f, 72f, 70f);
        }
    }

    void draw(Canvas canvas, float x, float y, float size, int code) {
        draw(canvas, x, y, size, kindOf(code));
    }

    void draw(Canvas canvas, float x, float y, float size, Kind kind) {
        minimumStroke = size < 0.7f ? 1.25f / Math.max(0.01f, size) : 0f;
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.scale(size, size);
        try {
            switch (kind) {
                case CLEAR:
                    drawClear(canvas);
                    break;
                case MAINLY_CLEAR:
                    drawMainlyClear(canvas);
                    break;
                case PARTLY_CLOUDY:
                    drawPartlyCloudy(canvas);
                    break;
                case OVERCAST:
                    drawOvercast(canvas);
                    break;
                case FOG:
                    drawFog(canvas);
                    break;
                case DRIZZLE:
                    drawDrizzle(canvas);
                    break;
                case FREEZING_RAIN:
                    drawFreezingRain(canvas);
                    break;
                case RAIN:
                    drawRain(canvas);
                    break;
                case HEAVY_RAIN:
                    drawHeavyRain(canvas);
                    break;
                case SNOW:
                    drawSnow(canvas);
                    break;
                case RAIN_SHOWERS:
                    drawRainShowers(canvas);
                    break;
                case SNOW_SHOWERS:
                    drawSnowShowers(canvas);
                    break;
                case THUNDERSTORM:
                    drawThunderstorm(canvas);
                    break;
                case THUNDERSTORM_HAIL:
                    drawThunderstormHail(canvas);
                    break;
                case UNKNOWN:
                default:
                    drawUnknown(canvas);
                    break;
            }
        } finally {
            paint.setShader(null);
            paint.setPathEffect(null);
            canvas.restoreToCount(save);
        }
    }

    private static int alpha(int color, int opacity) {
        return Color.argb(opacity, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void brush(int color, float width, int gradientAlpha) {
        paint.setShader(null);
        paint.setPathEffect(null);
        paint.setColor(color);
        paint.setStrokeWidth(width == 0f ? 0f : Math.max(width, minimumStroke));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(width == 0f ? Paint.Style.FILL : Paint.Style.STROKE);
        if (gradient > 0 && color != Color.BLACK) {
            paint.setShader(gradients[gradient]);
            paint.setAlpha(Math.min(Color.alpha(color), gradientAlpha));
        }
    }

    private void buildCloudPath(float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx - 26f * s, cy + 24f * s);
        float lCx = cx - 26f * s, lCy = cy + 7f * s, lR = 17f * s;
        rect.set(lCx - lR, lCy - lR, lCx + lR, lCy + lR);
        path.arcTo(rect, 90f, 170.79f, false);

        float mCx = cx - 3f * s, mCy = cy - 6f * s, mR = 26f * s;
        rect.set(mCx - mR, mCy - mR, mCx + mR, mCy + mR);
        path.arcTo(rect, 188.36f, 159.13f, false);

        float rCx = cx + 26f * s, rCy = cy + 6f * s, rR = 18f * s;
        rect.set(rCx - rR, rCy - rR, rCx + rR, rCy + rR);
        path.arcTo(rect, 258.40f, 191.60f, false);

        path.close();
    }

    private void drawCloud(Canvas c, float cx, float cy, float s, float strokeW,
                           boolean primary, boolean knockout, boolean sheen) {
        buildCloudPath(cx, cy, s);
        if (knockout) {
            brush(Color.BLACK, 0f, 255);
            c.drawPath(path, paint);
            brush(Color.BLACK, strokeW + 7.5f, 255);
            c.drawPath(path, paint);
        }
        int fillAlpha = primary ? 26 : 18;
        brush(alpha(primary ? ink : secondary, fillAlpha), 0f, fillAlpha);
        c.drawPath(path, paint);

        brush(primary ? ink : secondary, strokeW, primary ? 255 : 176);
        c.drawPath(path, paint);

        if (sheen) {
            float mCx = cx - 3f * s, mCy = cy - 6f * s, sR = 19f * s;
            rect.set(mCx - sR, mCy - sR, mCx + sR, mCy + sR);
            brush(alpha(secondary, 125), Math.max(1.8f, 2.0f * s), 125);
            c.drawArc(rect, 205f, 50f, false, paint);
        }
    }

    private void drawSun(Canvas c, float cx, float cy, float r, float strokeW,
                         float rayInner, float rayOuterCardinal, float rayOuterDiag,
                         float[] anglesDeg, boolean sheen) {
        brush(alpha(ink, 28), 0f, 28);
        c.drawCircle(cx, cy, r, paint);

        brush(ink, strokeW, 255);
        c.drawCircle(cx, cy, r, paint);

        if (sheen) {
            float sR = r * 0.68f;
            rect.set(cx - sR, cy - sR, cx + sR, cy + sR);
            brush(alpha(secondary, 130), 1.9f, 130);
            c.drawArc(rect, 195f, 68f, false, paint);
        }

        for (float deg : anglesDeg) {
            boolean cardinal = Math.round(deg) % 90 == 0;
            float outer = cardinal ? rayOuterCardinal : rayOuterDiag;
            float rayWidth = cardinal ? Math.max(2.9f, strokeW * 0.92f) : Math.max(2.5f, strokeW * 0.78f);
            int rayColor = cardinal ? ink : alpha(secondary, 220);
            int gradAlpha = cardinal ? 255 : 220;
            double rad = Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            brush(rayColor, rayWidth, gradAlpha);
            c.drawLine(cx + cos * rayInner, cy + sin * rayInner,
                    cx + cos * outer, cy + sin * outer, paint);
        }
    }

    private void drawTeardrop(Canvas c, float cx, float cy, float length, float tiltDeg, boolean primary) {
        int save = c.save();
        c.translate(cx, cy);
        c.rotate(tiltDeg);
        float h = length;
        float w = length * 0.28f;
        path.reset();
        path.moveTo(0f, -h * 0.50f);
        path.cubicTo(w * 0.35f, -h * 0.20f, w, -h * 0.02f, w, h * 0.16f);
        rect.set(-w, h * 0.16f - w, w, h * 0.16f + w);
        path.arcTo(rect, 0f, 180f, false);
        path.cubicTo(-w, -h * 0.02f, -w * 0.35f, -h * 0.20f, 0f, -h * 0.50f);
        path.close();

        brush(primary ? ink : secondary, 0f, primary ? 255 : 176);
        c.drawPath(path, paint);
        c.restoreToCount(save);
    }

    private void drawSnowflake(Canvas c, float cx, float cy, float r, boolean branched, boolean primary) {
        int color = primary ? ink : secondary;
        int gradAlpha = primary ? 255 : 176;
        float strokeW = branched ? 2.6f : 2.4f;
        brush(color, strokeW, gradAlpha);
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(30f + i * 60f);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float ex = cx + cos * r;
            float ey = cy + sin * r;
            c.drawLine(cx, cy, ex, ey, paint);

            if (branched) {
                float bx = cx + cos * (0.58f * r);
                float by = cy + sin * (0.58f * r);
                float branchLen = 0.36f * r;
                double leftA = angle - Math.toRadians(42f);
                double rightA = angle + Math.toRadians(42f);
                c.drawLine(bx, by, bx + (float) Math.cos(leftA) * branchLen,
                        by + (float) Math.sin(leftA) * branchLen, paint);
                c.drawLine(bx, by, bx + (float) Math.cos(rightA) * branchLen,
                        by + (float) Math.sin(rightA) * branchLen, paint);
            }
        }
        brush(color, 0f, gradAlpha);
        c.drawCircle(cx, cy, Math.max(1.8f, r * 0.18f), paint);
    }

    private void drawCrystalDiamond(Canvas c, float cx, float cy, float r, float tiltDeg,
                                    boolean hollow, boolean primary) {
        int save = c.save();
        c.translate(cx, cy);
        c.rotate(tiltDeg);
        float rx = r * 0.78f;
        path.reset();
        path.moveTo(0f, -r);
        path.lineTo(rx, 0f);
        path.lineTo(0f, r);
        path.lineTo(-rx, 0f);
        path.close();

        int color = primary ? ink : secondary;
        int gradAlpha = primary ? 255 : 176;
        if (hollow) {
            brush(alpha(color, 42), 0f, 42);
            c.drawPath(path, paint);
            brush(color, 2.3f, gradAlpha);
            c.drawPath(path, paint);
        } else {
            brush(color, 0f, gradAlpha);
            c.drawPath(path, paint);
        }
        c.restoreToCount(save);
    }

    private void drawMistWave(Canvas c, float left, float right, float y, float amp,
                              float strokeW, int color, int gradAlpha) {
        float span = right - left;
        float mid = (left + right) * 0.5f;
        float q = span * 0.25f;
        path.reset();
        path.moveTo(left, y);
        path.cubicTo(left + q * 0.65f, y - amp, mid - q * 0.65f, y - amp, mid, y);
        path.cubicTo(mid + q * 0.65f, y + amp, right - q * 0.65f, y + amp, right, y);
        brush(color, strokeW, gradAlpha);
        c.drawPath(path, paint);
    }

    private void drawBolt(Canvas c, float ox, float oy) {
        path.reset();
        path.moveTo(ox + 4f, oy - 8f);
        path.lineTo(ox - 13f, oy + 23f);
        path.lineTo(ox - 1f, oy + 23f);
        path.lineTo(ox - 9f, oy + 56f);
        path.lineTo(ox + 15f, oy + 17f);
        path.lineTo(ox + 2f, oy + 17f);
        path.close();

        brush(Color.BLACK, 7.5f, 255);
        c.drawPath(path, paint);
        brush(ink, 0f, 255);
        c.drawPath(path, paint);
    }

    private void drawClear(Canvas c) {
        drawSun(c, 0f, 0f, 24f, 4.2f, 35f, 52f, 47f, FULL_SUN_ANGLES, true);
    }

    private void drawMainlyClear(Canvas c) {
        drawSun(c, -14f, -12f, 21.5f, 4.0f, 30f, 43f, 39f, FULL_SUN_ANGLES, true);
        drawCloud(c, 17f, 24f, 0.62f, 4.0f, true, true, true);
    }

    private void drawPartlyCloudy(Canvas c) {
        drawSun(c, 22f, -20f, 18.5f, 3.8f, 26f, 37f, 34f, PEEK_SUN_ANGLES, false);
        drawCloud(c, -5f, 12f, 0.94f, 4.2f, true, true, true);
    }

    private void drawOvercast(Canvas c) {
        drawCloud(c, 15f, -14f, 0.78f, 3.4f, false, false, false);
        drawCloud(c, -8f, 14f, 0.92f, 4.2f, true, true, true);
    }

    private void drawFog(Canvas c) {
        drawCloud(c, 0f, -21f, 0.84f, 4.0f, true, false, true);
        drawMistWave(c, -48f, 38f, 16f, 3.5f, 3.8f, ink, 255);
        drawMistWave(c, -36f, 50f, 31f, -3.5f, 3.4f, secondary, 185);
        drawMistWave(c, -44f, 28f, 46f, 3.0f, 3.0f, alpha(secondary, 185), 145);
    }

    private void drawDrizzle(Canvas c) {
        drawCloud(c, 0f, -20f, 0.86f, 4.0f, true, false, true);
        drawTeardrop(c, -26f, 20f, 11.5f, 18f, true);
        drawTeardrop(c, 0f, 20f, 11.5f, 18f, true);
        drawTeardrop(c, 26f, 20f, 11.5f, 18f, true);
        drawTeardrop(c, -34f, 40f, 9.5f, 18f, false);
        drawTeardrop(c, -8f, 40f, 9.5f, 18f, false);
        drawTeardrop(c, 18f, 40f, 9.5f, 18f, false);
    }

    private void drawRain(Canvas c) {
        drawCloud(c, 0f, -22f, 0.88f, 4.2f, true, false, true);
        drawTeardrop(c, -26f, 26f, 20f, 16f, true);
        drawTeardrop(c, -2f, 39f, 22f, 16f, true);
        drawTeardrop(c, 24f, 26f, 20f, 16f, false);
    }

    private void drawHeavyRain(Canvas c) {
        drawCloud(c, 14f, -30f, 0.66f, 3.0f, false, false, false);
        drawCloud(c, -4f, -20f, 0.88f, 4.2f, true, true, true);
        drawTeardrop(c, -30f, 22f, 18f, 20f, true);
        drawTeardrop(c, -4f, 22f, 18f, 20f, true);
        drawTeardrop(c, 22f, 22f, 18f, 20f, true);
        drawTeardrop(c, -20f, 44f, 19f, 20f, false);
        drawTeardrop(c, 8f, 44f, 19f, 20f, false);
    }

    private void drawFreezingRain(Canvas c) {
        drawCloud(c, 0f, -22f, 0.88f, 4.2f, true, false, true);
        drawTeardrop(c, -28f, 27f, 20f, 16f, true);
        drawSnowflake(c, -1f, 38f, 12.5f, false, true);
        drawTeardrop(c, 26f, 27f, 19f, 16f, false);
    }

    private void drawSnow(Canvas c) {
        drawCloud(c, 0f, -22f, 0.88f, 4.2f, true, false, true);
        drawSnowflake(c, -28f, 26f, 10.5f, false, false);
        drawSnowflake(c, 0f, 39f, 14.5f, true, true);
        drawSnowflake(c, 28f, 26f, 10.5f, false, false);
    }

    private void drawRainShowers(Canvas c) {
        drawSun(c, 22f, -34f, 15.5f, 3.4f, 22f, 31f, 28f, SHOWER_SUN_ANGLES, false);
        drawCloud(c, -5f, -14f, 0.86f, 4.0f, true, true, true);
        drawTeardrop(c, -26f, 30f, 19f, 18f, true);
        drawTeardrop(c, -2f, 41f, 20f, 18f, false);
        drawTeardrop(c, 22f, 30f, 19f, 18f, true);
    }

    private void drawSnowShowers(Canvas c) {
        drawSun(c, 22f, -34f, 15.5f, 3.4f, 22f, 31f, 28f, SHOWER_SUN_ANGLES, false);
        drawCloud(c, -5f, -14f, 0.86f, 4.0f, true, true, true);
        drawSnowflake(c, -26f, 31f, 9.5f, false, false);
        drawSnowflake(c, -2f, 42f, 13f, true, true);
        drawSnowflake(c, 22f, 31f, 9.5f, false, false);
    }

    private void drawThunderstorm(Canvas c) {
        drawCloud(c, 14f, -30f, 0.66f, 3.0f, false, false, false);
        drawCloud(c, -4f, -20f, 0.88f, 4.2f, true, true, true);
        drawTeardrop(c, -31f, 30f, 17f, 18f, false);
        drawTeardrop(c, 27f, 30f, 17f, 18f, false);
        drawBolt(c, 0f, 0f);
    }

    private void drawThunderstormHail(Canvas c) {
        drawCloud(c, 14f, -30f, 0.66f, 3.0f, false, false, false);
        drawCloud(c, -4f, -20f, 0.88f, 4.2f, true, true, true);
        drawCrystalDiamond(c, -30f, 25f, 7.5f, 10f, true, true);
        drawCrystalDiamond(c, -21f, 44f, 5.2f, 10f, false, false);
        drawCrystalDiamond(c, 28f, 25f, 7.5f, 10f, true, true);
        drawCrystalDiamond(c, 20f, 44f, 5.2f, 10f, false, false);
        drawBolt(c, 0f, 0f);
    }

    private void drawUnknown(Canvas c) {
        buildCloudPath(0f, -4f, 0.90f);
        brush(alpha(secondary, 14), 0f, 14);
        c.drawPath(path, paint);

        brush(secondary, 3.4f, 176);
        paint.setPathEffect(dashEffect);
        c.drawPath(path, paint);
        paint.setPathEffect(null);

        brush(ink, 3.6f, 255);
        rect.set(-9.5f, -20f, 9.5f, -1f);
        path.reset();
        path.addArc(rect, 195f, 225f);
        path.quadTo(0.8f, -0.5f, 0f, 6.5f);
        c.drawPath(path, paint);

        brush(ink, 0f, 255);
        c.drawCircle(0f, 14.5f, 2.3f, paint);
    }
}
