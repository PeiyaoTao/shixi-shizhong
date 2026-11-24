package com.foldstandby.clock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/** Separate stationary surfaces from parts driven by the playback position. */
final class MusicArtwork {
    private static final int BLACK = 0xff101312;
    private static final int SILVER = 0xffbbbcb2;
    private static final int VECTOR_DETAIL = 0xff91958f;
    private static final int VECTOR_LABEL = 0xff303431;
    private static final float TAPE_CENTER_Y = 82f;
    private static final float FULL_REEL = 24f;
    private static final float EMPTY_REEL = 10f;
    private static final Typeface LABEL_TYPE = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Rect crop = new Rect();
    private final RectF label = new RectF(-22, -22, 22, 22);
    private final Materials[] materials = new Materials[TextGradients.COLORS.length];
    private final Shader[] spectrumGradients = new Shader[TextGradients.COLORS.length];
    private Shader recordLabel, cassetteLabel;
    private int labelFirst, labelLast;

    MusicArtwork() {
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private static Shader metal(float x, float y, float endX, float endY, int light, int mid, int dark) {
        return new LinearGradient(x, y, endX, endY, new int[]{light, mid, dark}, null, Shader.TileMode.CLAMP);
    }

    private static final class Materials {
        final Shader turntable, vinyl, arm, shell, glass, ribbon;

        Materials(int gradient) {
            turntable = tintMaterial(metal(0, 0, 189, 146, 0xff535850, 0xff282b28, 0xff373a34), gradient, 0, 0, 189, 146);
            vinyl = tintMaterial(new SweepGradient(75, 73,
                new int[]{0xff171a19, 0xff424842, 0xff131515, 0xff131615, 0xff3b403d, 0xff151717, 0xff171a19}, null),
                gradient, 14, 12, 136, 134);
            arm = tintMaterial(metal(-4, 0, 4, 0, 0xff454a45, 0xffd3d5c8, 0xff787e75), gradient, 0, 0, 0, 108);
            shell = tintMaterial(metal(4, 0, 228, 139, 0xff898578, 0xff504f46, 0xff302f28), gradient, 4, 0, 228, 139);
            glass = tintMaterial(metal(15, 52, 211, 109, 0xff2c3029, 0xff161914, 0xff141913), gradient, 15, 52, 211, 109);
            ribbon = tintMaterial(metal(-23, -21, 21, 24, 0xff957153, 0xff684838, 0xff342822), gradient, -23, -21, 21, 24);
        }
    }

    private static Shader tintMaterial(Shader material, int gradient, float l, float t, float r, float b) {
        return gradient == 0 ? material
                : new ComposeShader(material, TextGradients.shader(gradient, l, t, r, b), PorterDuff.Mode.MULTIPLY);
    }

    private Materials materials(int gradient) {
        Materials value = materials[gradient];
        if (value == null) materials[gradient] = value = new Materials(gradient);
        return value;
    }

    static int mix(int a, int b, float fraction) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * fraction),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * fraction),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction));
    }

    static int tint(int fallback, int gradient, float position) {
        if (gradient == 0) return fallback;
        int[] colors = TextGradients.COLORS[gradient];
        float index = position * (colors.length - 1);
        int start = Math.min(colors.length - 2, (int) index);
        return mix(colors[start], colors[start + 1], index - start);
    }

    private void updateLabels(int first, int last) {
        if (recordLabel != null && first == labelFirst && last == labelLast) return;
        labelFirst = first;
        labelLast = last;
        recordLabel = metal(-20, -20, 20, 22, first, mix(first, 0xff524032, .29f), last);
        cassetteLabel = metal(15, 14, 211, 45, mix(0xffeddec2, first, .62f), first, mix(0xff5a4536, last, .56f));
    }

    void record(Canvas c, boolean vector, boolean night, int ink, int gradient, Bitmap art,
                long position, float progress, boolean spinning) {
        int first = tint(ink, gradient, .12f), last = tint(ink, gradient, .82f);
        int edge = edge(vector, night, first);
        boolean textured = !vector && !night;
        Materials material = textured ? materials(gradient) : null;
        c.save();
        c.translate(39, 24);
        c.scale(1.07f, 1.07f);
        rounded(c, 0, 1, 192, 150, 12, night ? 0xff0a0d0b : BLACK);
        if (textured) paint.setShader(material.turntable);
        rounded(c, 1, 0, 191, 146, 11, background(vector, night, first));
        outline(c, 1, 0, 191, 146, 11, edge, 1.15f);
        if (textured) {
            outline(c, 5, 4, 187, 143, 8, 0xff20231f, 1);
            for (int y = 10; y < 142; y += 3) line(c, 10, y, 181, y, 0x0dffffff, .6f);
            screw(c, 10, 10);
            screw(c, 182, 10);
            screw(c, 10, 137);
            screw(c, 182, 137);
        }
        circle(c, 75, 73, 64, BLACK);
        ring(c, 75, 73, 63, edge, 1.2f);
        if (textured) paint.setShader(material.vinyl);
        circle(c, 75, 73, 61, vector ? mix(0xff171a18, first, night ? .015f : .035f) : 0xff161917);
        if (textured) {
            for (int i = 0; i < 31; i++) ring(c, 75, 73, 24.5f + i * 1.16f,
                    i % 7 == 0 ? 0x77060a08 : 0x224e584e, i % 7 == 0 ? 1f : .55f);
        } else {
            for (int radius = 33; radius <= 57; radius += 8) {
                ring(c, 75, 73, radius, vector ? mix(0xff3a403b, first, night ? .04f : .12f) : mix(BLACK, first, .35f), .95f);
            }
        }

        c.save();
        c.translate(75, 73);
        if (spinning) c.rotate((position % 1800L) / 5f);
        if (textured) {
            updateLabels(first, last);
            paint.setShader(recordLabel);
        }
        int paper = vector ? mix(night ? BLACK : VECTOR_LABEL, first, night ? .10f : .14f) : night ? edge : first;
        circle(c, 0, 0, 22, paper);
        if (art != null && textured) {
            int size = Math.min(art.getWidth(), art.getHeight());
            int x = (art.getWidth() - size) / 2, y = (art.getHeight() - size) / 2;
            crop.set(x, y, x + size, y + size);
            path.reset();
            path.addCircle(0, 0, 19.5f, Path.Direction.CW);
            int save = c.save();
            c.clipPath(path);
            c.drawBitmap(art, crop, label, paint);
            c.restoreToCount(save);
        } else if (vector) {
            rounded(c, -12, -9, 12, -7, 1, night ? edge : mix(VECTOR_DETAIL, first, .62f));
            rounded(c, -6, 8, 6, 9.2f, .6f, night ? edge : mix(VECTOR_DETAIL, last, .42f));
        } else {
            rounded(c, -21, -3, 21, 3, 2, 0x55231e17);
            text(c, "SIDE A", 0, -8, 5.5f, night ? BLACK : 0xff282318, Paint.Align.CENTER);
            text(c, "33 ⅓", 0, 13, 5.5f, night ? BLACK : 0xff282318, Paint.Align.CENTER);
        }
        int rim = vector ? mix(paper, last, night ? .28f : .54f) : night ? 0xff141915 : 0x6ef8eecc;
        ring(c, 0, 0, 20.5f, rim, .75f);
        c.restore();
        circle(c, 75, 73, 2.7f, BLACK);
        circle(c, 75, 73, 1.35f, night ? edge : SILVER);

        int metalInk = night ? edge : vector ? mix(VECTOR_DETAIL, last, .18f) : SILVER;
        rounded(c, 153, 110, 179, 124, 4, mix(BLACK, first, vector ? .035f : .12f));
        outline(c, 153, 110, 179, 124, 4, edge, .75f);
        circle(c, 166, 117, 3.5f, edge);
        c.save();
        c.translate(161, 24);
        float armProgress = Float.isNaN(progress) ? 0 : progress;
        float leadIn = spinning ? Math.min(1, position / 480f) : position > 0 ? 1 : 0;
        c.rotate(7 + (19 + armProgress * 22) * leadIn);
        path.reset();
        path.moveTo(0, 0);
        path.lineTo(.5f, 62);
        path.lineTo(-3, 95);
        path.lineTo(0, 108);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6);
        paint.setColor(BLACK);
        c.drawPath(path, paint);
        paint.setStrokeWidth(vector ? 3.9f : 3.2f);
        paint.setColor(metalInk);
        if (textured) paint.setShader(material.arm);
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        rounded(c, -6, 94, 3, 107, 2, metalInk);
        rounded(c, -4, 105, 2, 110, 1.5f, night ? edge : last);
        c.restore();
        circle(c, 161, 24, 11, BLACK);
        ring(c, 161, 24, 10, edge, 1.1f);
        if (textured) paint.setShader(material.arm);
        circle(c, 161, 24, 7.5f, metalInk);
        circle(c, 161, 24, 2.9f, mix(BLACK, metalInk, .52f));
        circle(c, 18, 129, 2.1f, night ? edge : last);
        text(c, "33⅓", 25, 131.3f, 6.2f, metalInk, Paint.Align.LEFT);
        c.restore();
    }

    void cassette(Canvas c, boolean vector, boolean night, int ink, int gradient,
                  String title, long position, long duration, float progress, boolean spinning) {
        int first = tint(ink, gradient, .16f), last = tint(ink, gradient, .81f);
        int edge = edge(vector, night, first);
        boolean textured = !vector && !night;
        Materials material = textured ? materials(gradient) : null;
        float fraction = Float.isNaN(progress) ? .5f : progress;
        float difference = FULL_REEL * FULL_REEL - EMPTY_REEL * EMPTY_REEL;
        float left = (float) Math.sqrt(FULL_REEL * FULL_REEL - fraction * difference);
        float right = (float) Math.sqrt(EMPTY_REEL * EMPTY_REEL + fraction * difference);
        float angleFactor = duration * 6.4f / difference;
        float leftAngle = duration > 0 ? angleFactor * (FULL_REEL - left) : position / 5.8f;
        float rightAngle = duration > 0 ? angleFactor * (right - EMPTY_REEL) : position / 5.8f;

        c.save();
        c.translate(31, 34);
        rounded(c, 0, 0, 226, 139, 11, BLACK);
        if (textured) paint.setShader(material.shell);
        rounded(c, 1, 1, 225, 136, 10, background(vector, night, first));
        outline(c, 1, 1, 225, 136, 10, edge, 1.35f);
        outline(c, 5, 5, 221, 131, 8, vector ? mix(0xff404541, first, night ? .015f : .09f) : BLACK, .85f);
        if (textured) {
            screw(c, 11, 12);
            screw(c, 215, 12);
            screw(c, 11, 124);
            screw(c, 215, 124);
        }
        if (textured) {
            updateLabels(first, last);
            paint.setShader(cassetteLabel);
        }
        int labelColor = vector ? mix(night ? BLACK : VECTOR_LABEL, first, night ? .065f : .105f)
                : night ? edge : mix(first, last, .28f);
        rounded(c, 16, 15, 210, 46, 5, vector || night ? labelColor : first);
        rounded(c, 22, 21, 43, 40, 3, vector || night ? BLACK : mix(BLACK, first, .19f));
        text(c, "A", 32.5f, 34.8f, 13, night ? edge : first, Paint.Align.CENTER);
        if (vector) {
            line(c, 53, 42.3f, 200, 42.3f, mix(labelColor, last, night ? .21f : .42f), 1);
            line(c, 190, 27, 201, 27, night ? edge : mix(VECTOR_DETAIL, last, .61f), 1.5f);
            line(c, 196, 32, 201, 32, night ? edge : mix(VECTOR_DETAIL, last, .37f), 1);
        }
        cassetteTitle(c, title, vector ? 125 : 150, labelColor);
        if (textured) line(c, 16, 44, 210, 44, 0x77463022, 1);

        if (textured) paint.setShader(material.glass);
        rounded(c, 20, 51, 206, 112, 13, mix(BLACK, first, vector ? .025f : night ? .07f : .12f));
        outline(c, 20, 51, 206, 112, 13, edge, .9f);
        paint.setStyle(Paint.Style.FILL);
        line(c, 70, 105, 156, 105, vector ? mix(0xff4c4941, first, night ? .06f : .26f) : 0xff80614a, 1.25f);
        Shader ribbonShader = material == null ? null : material.ribbon;
        reel(c, 61, left, spinning ? leftAngle : 0, vector, night, first, ribbonShader);
        reel(c, 165, right, spinning ? rightAngle : 0, vector, night, last, ribbonShader);
        outline(c, 92, 69, 134, 94, 5, edge, .75f);
        for (int x = 97; x <= 129; x += 4) line(c, x, 75, x, x % 8 == 1 ? 80 : 77, edge, .7f);
        if (textured) line(c, 31, 53, 195, 53, 0x4edcdac6, .8f);

        path.reset();
        path.moveTo(50, 134);
        path.lineTo(58, 119);
        path.quadTo(59, 116, 63, 116);
        path.lineTo(163, 116);
        path.quadTo(167, 116, 168, 119);
        path.lineTo(176, 134);
        path.close();
        paint.setColor(mix(BLACK, edge, .5f));
        c.drawPath(path, paint);
        rounded(c, 68, 123, 79, 129, 2, BLACK);
        rounded(c, 148, 123, 159, 129, 2, BLACK);
        rounded(c, 91, 129, 135, 135, 2, BLACK);
        for (int y = 118; y <= 126; y += 4) {
            line(c, 20, y, 43, y, edge, .75f);
            line(c, 183, y, 206, y, edge, .75f);
        }
        c.restore();
    }

    private void cassetteTitle(Canvas c, String title, float width, int background) {
        String name = title == null ? "" : title.replace('\n', ' ').replace('\r', ' ').trim();
        int foreground = Color.luminance(background) >= .20f ? 0xff101312 : 0xfff9f7ee;
        if (name.isEmpty()) {
            text(c, "COMPACT CASSETTE", 50, 34.5f, 8.5f, foreground, Paint.Align.LEFT);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(foreground);
        paint.setTypeface(LABEL_TYPE);
        paint.setTextSize(13.5f);
        paint.setTextAlign(Paint.Align.LEFT);
        CharSequence fitted = TextUtils.ellipsize(name, paint, width, TextUtils.TruncateAt.END);
        float baseline = 30.5f - (paint.ascent() + paint.descent()) / 2;
        int save = c.save();
        c.clipRect(49, 18, 50 + width, 41);
        c.drawText(fitted, 0, fitted.length(), 50, baseline, paint);
        c.restoreToCount(save);
    }

    private void reel(Canvas c, float x, float radius, float angle, boolean vector, boolean night, int accent, Shader ribbonShader) {
        c.save();
        c.translate(x, TAPE_CENTER_Y);
        circle(c, 0, 0, 26.3f, BLACK);
        ring(c, 0, 0, 25.8f, edge(vector, night, accent), .85f);
        paint.setShader(ribbonShader);
        int tapeColor = vector ? mix(0xff3b3831, accent, night ? .06f : .17f) : mix(BLACK, accent, night ? .25f : .49f);
        circle(c, 0, 0, radius, tapeColor);
        for (float r = radius - 1.4f; r > 10.5f; r -= vector ? 4f : 1.65f) {
            ring(c, 0, 0, r, 0x80141614, .7f);
        }
        c.rotate(angle % 360);
        int hub = night ? edge(vector, true, accent) : vector ? mix(VECTOR_DETAIL, accent, .3f) : 0xffcabea4;
        circle(c, 0, 0, 9, hub);
        circle(c, 0, 0, 5.9f, BLACK);
        for (int i = 0; i < 6; i++) {
            rounded(c, -.85f, -6.8f, .85f, -3.8f, .55f, hub);
            c.rotate(60);
        }
        circle(c, 7.1f, 0, 1.1f, BLACK);
        c.restore();
    }

    void spectrum(Canvas c, float[] levels, int ink, int gradient, boolean night) {
        int bandCount = levels.length;
        int edge = mix(BLACK, ink, night ? .19f : .34f);
        if (gradient == 0) {
            rounded(c, 48, 88, 452, 180, 9, mix(0xff0a0d0b, ink, night ? .015f : .045f));
            outline(c, 48, 88, 452, 180, 9, edge, 1);
        } else {
            if (spectrumGradients[gradient] == null) spectrumGradients[gradient] = TextGradients.shader(gradient, 48, 88, 452, 180);
            paint.setShader(spectrumGradients[gradient]);
            rounded(c, 48, 88, 452, 180, 9, 0x18000000);
            paint.setShader(spectrumGradients[gradient]);
            outline(c, 48, 88, 452, 180, 9, 0x56000000, 1);
            paint.setShader(null);
        }
        for (int i = 0; i < bandCount; i++) {
            float x = 60 + 380f * i / bandCount;
            int hue = tint(ink, gradient, i / (float) (bandCount - 1));
            int count = Math.round(10 * levels[i]);
            for (int row = 0; row <= 10; row++) {
                float y = 157 - row * 5.3f;
                float bright = row > count ? .085f : row == 0 ? .32f : .60f + row * .04f;
                rounded(c, x, y, x + 7.5f, y + 3, .74f, mix(BLACK, hue, night ? bright * .67f : bright));
            }
        }
        if (gradient > 0) paint.setShader(spectrumGradients[gradient]);
        line(c, 60, 165, 440, 165, gradient == 0 ? edge : 0x56000000, .7f);
        int label = gradient == 0 ? mix(BLACK, ink, night ? .44f : .72f) : 0xb8000000;
        text(c, "LOW", 61, 174.5f, 7.4f, label, Paint.Align.LEFT);
        text(c, "MID", 250, 174.5f, 7.4f, label, Paint.Align.CENTER);
        text(c, "HIGH", 439, 174.5f, 7.4f, label, Paint.Align.RIGHT);
        paint.setShader(null);
    }

    private static int edge(boolean vector, boolean night, int accent) {
        return night ? mix(BLACK, accent, .44f) : vector ? mix(0xff5f6661, accent, .18f) : 0xff7a7b6f;
    }

    private static int background(boolean vector, boolean night, int accent) {
        return night ? mix(BLACK, accent, .065f) : vector ? mix(0xff242825, accent, .055f) : 0xff343630;
    }

    private void screw(Canvas c, float x, float y) {
        circle(c, x, y, 3.05f, 0xff151814);
        circle(c, x, y, 2.4f, 0xff7d7d70);
        line(c, x - 1, y - .65f, x + 1, y + .65f, BLACK, .85f);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color, Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        c.drawText(value, x, y, paint);
    }

    private void rounded(Canvas c, float l, float t, float r, float b, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRoundRect(l, t, r, b, radius, radius, paint);
        paint.setShader(null);
    }

    private void outline(Canvas c, float l, float t, float r, float b, float radius, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        c.drawRoundRect(l, t, r, b, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void circle(Canvas c, float x, float y, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
        paint.setShader(null);
    }

    private void ring(Canvas c, float x, float y, float radius, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas c, float x, float y, float endX, float endY, int color, float width) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        c.drawLine(x, y, endX, endY, paint);
    }
}
