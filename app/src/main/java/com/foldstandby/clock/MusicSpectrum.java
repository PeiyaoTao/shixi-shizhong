package com.foldstandby.clock;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

import java.util.Arrays;

/** A visible spectrum reads only the FFT of Android's playback output. */
final class MusicSpectrum {
    static final int BANDS = 30;
    static final String ENABLED = "thirdPartySpectrumEnabled";
    private static final float[] DEMO_LEVELS = {
            .26f,.52f,.73f,.83f,.72f,.58f,.46f,.52f,.42f,.30f,.25f,.47f,.60f,.39f,.21f,
            .28f,.36f,.31f,.16f,.22f,.30f,.26f,.17f,.12f,.24f,.29f,.19f,.12f,.18f,.08f
    };

    private final float[] levels = new float[BANDS];
    private final float[] target = new float[BANDS];
    private Visualizer capture;
    private byte[] bins;
    private int sampleRateMilliHz;
    private long lastRead;
    private long retryAt;

    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean enabled(Context context, SharedPreferences prefs) {
        return prefs.getBoolean(ENABLED, false) && allowed(context);
    }

    static float[] demoLevels() { return DEMO_LEVELS; }

    float[] levels(boolean active) {
        long now = SystemClock.uptimeMillis();
        if (!active) releaseCapture();
        if (now - lastRead < 44) return levels;
        lastRead = now;
        if (active && capture == null && now >= retryAt) start(now);
        Arrays.fill(target, 0);
        if (capture != null) {
            try {
                if (capture.getFft(bins) == Visualizer.SUCCESS) computeBands(bins, sampleRateMilliHz, target);
                else fail(now);
            } catch (RuntimeException unavailable) {
                fail(now);
            }
        }
        for (int i = 0; i < BANDS; i++) {
            float step = target[i] > levels[i] ? .75f : .29f;
            levels[i] += (target[i] - levels[i]) * step;
            if (levels[i] < .012f) levels[i] = 0;
        }
        return levels;
    }

    boolean moving() {
        for (float level : levels) if (level > 0) return true;
        return false;
    }

    boolean capturing() { return capture != null; }

    void reset() {
        releaseCapture();
        Arrays.fill(levels, 0);
        lastRead = retryAt = 0;
    }

    private void start(long now) {
        try {
            capture = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.max(range[0], Math.min(range[1], 1024));
            if (capture.setCaptureSize(size) != Visualizer.SUCCESS || capture.setEnabled(true) != Visualizer.SUCCESS) {
                fail(now);
                return;
            }
            bins = new byte[size];
            sampleRateMilliHz = capture.getSamplingRate();
        } catch (RuntimeException unavailable) {
            fail(now);
        }
    }

    private void fail(long now) {
        releaseCapture();
        retryAt = now + 2000L;
    }

    private void releaseCapture() {
        if (capture == null) return;
        capture.release();
        capture = null;
        bins = null;
    }

    static void computeBands(byte[] fft, int sampleRateMilliHz, float[] out) {
        float hzPerBin = sampleRateMilliHz / (1000f * fft.length);
        if (hzPerBin <= 0) return;
        int maxBin = fft.length / 2;
        int linearBands = Math.max(0, Math.min(8, Math.round(360 / hzPerBin) - 1));
        double minHz = (1 + linearBands) * hzPerBin;
        double maxHz = Math.min(12000, hzPerBin * (maxBin - 1));
        int first = 1;
        for (int band = 0; band < out.length; band++) {
            double edge = band < linearBands ? (band + 2) * hzPerBin : minHz * Math.pow(maxHz / minHz,
                    (band + 1.0 - linearBands) / (out.length - linearBands));
            int last = Math.min(maxBin, Math.max(first + 1, (int) Math.round(edge / hzPerBin)));
            float energy = 0;
            for (int bin = first; bin < last; bin++) {
                int real = fft[2 * bin], imaginary = fft[2 * bin + 1];
                energy = Math.max(energy, real * real + imaginary * imaginary);
            }
            out[band] = energy < 4 ? 0 : Math.min(1f, (float) Math.log1p(Math.sqrt(energy) / 3) / 3.65f);
            first = last;
        }
    }
}
