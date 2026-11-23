package com.foldstandby.clock;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

/** Fold8 native modes verified through dumpsys display on SM-F971U1.
 * Window dimensions are deliberately excluded: cutouts and rotation change them.
 */
final class ScreenIdentity {
    static boolean isVerifiedCover(String model, int width, int height) {
        return isVerifiedFold8(model) && Math.min(width,height)==1248
                && Math.max(width,height)==1972;
    }

    static boolean isVerifiedFold8(String model) {
        return model != null && model.startsWith("SM-F971");
    }

    static boolean isPrimaryCover(Context ctx) {
        if (!isVerifiedFold8(Build.MODEL)) return Fold.forcedOuter && Fold.currentState >= 0;
        try {
            DisplayManager dm = ctx.getSystemService(DisplayManager.class);
            Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null && d.getMode() != null) {
                Display.Mode m = d.getMode();
                return isVerifiedCover(Build.MODEL, m.getPhysicalWidth(), m.getPhysicalHeight());
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
