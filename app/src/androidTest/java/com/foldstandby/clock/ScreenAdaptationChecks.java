package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Switch;

final class ScreenAdaptationChecks {
    static Bundle run(Instrumentation instrumentation) {
        Bundle result = new Bundle();
        instrumentation.runOnMainSync(() -> {
            try {
                Context context = instrumentation.getTargetContext();
                SharedPreferences prefs = context.getSharedPreferences("screen_adaptation_checks", 0);
                Scene scene = Scene.load(prefs);
                scene.pages.get(0).layout = 1;
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(430 * density);
                int height = Math.round(620 * density);
                ClockFace portraitFace = new ClockFace(context, prefs);
                portraitFace.bind(scene, 0);
                int[] slot = {-1};
                portraitFace.slotTap = value -> slot[0] = value;
                ClockStage stage = new ClockStage(context, portraitFace);
                stage.setFold(new Rect(0, height / 2 - 4, width, height / 2 + 4));
                render(stage, width, height);
                check(stage.content.getRotation() == 90f, "portrait-only window uses a fixed quarter-turn");
                check(portraitFace.getWidth() == height && portraitFace.getHeight() == width,
                        "the rotated clock fills the available window");
                tap(stage, width / 2f, height / 4f);
                check(slot[0] == 0, "rotated touch in the top half reaches the first component");
                tap(stage, width / 2f, height * .75f);
                check(slot[0] == 1, "rotated touch in the bottom half reaches the second component");
                slot[0] = -1;
                tap(stage, width / 2f, height / 2f);
                check(slot[0] == -1, "hinge is excluded from touch");
                stage.setFold(null);
                render(stage, height, width);
                check(stage.content.getRotation() == 0 && portraitFace.getWidth() == height
                        && portraitFace.getHeight() == width, "clock fills a landscape window");
                scene.pages.get(0).layout = 3;
                ClockFace face = new ClockFace(context, prefs);
                face.bind(scene, 0);
                int[] swipes = {0};
                face.pageSwipe = direction -> swipes[0]++;
                render(face, Math.round(320 * density), Math.round(140 * density));
                check(face.hasSubpages(), "narrow split window has more than one component group");
                check(face.moveSubpage(1) && face.currentSubpage() == 1, "secondary group is reachable");
                check(!face.moveSubpage(1) && face.moveSubpage(-1), "first and last group have correct boundaries");
                render(face, Math.round(800 * density), Math.round(430 * density));
                check(!face.hasSubpages() && face.currentSubpage() == 0, "larger window recombines the saved page");
                check(scene.pages.get(0).layout == 3 && swipes[0] == 0, "reflow does not edit or replace scene pages");
                result.putBoolean("passed", true);
                result.putString("result", "PASS: fixed landscape canvas, portrait-window fallback, hinge-aware touch, and subpage reflow");
            } catch (Throwable error) {
                result.putString("result", android.util.Log.getStackTraceString(error));
            }
        });
        if (result.getBoolean("passed")) {
            try {
                verifyManualEntry(instrumentation);
                result.putString("result", result.getString("result")
                        + "; automatic mode defaults off, and manual standby opens from Settings");
            } catch (Throwable error) {
                result.putBoolean("passed", false);
                result.putString("result", android.util.Log.getStackTraceString(error));
            }
        }
        return result;
    }

    private static void verifyManualEntry(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        MainActivity editor = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .putExtra("uiCheck", true).putExtra("openStandbySettings", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int autoRotation = Settings.System.getInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, -1);
        int systemRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, -1);
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                check(editor.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_USER, "editor follows system orientation");
                View root = editor.getWindow().getDecorView();
                Switch charging = findSwitch(root, "充电时自动待机");
                check(charging != null && !charging.isChecked(), "charging mode defaults off");
                View button = root.findViewWithTag("standby:start");
                check(button != null && button.performClick(), "manual entry launches standby");
            });
            for (int i = 0; i < 20 && ClockActivity.currentInstance == null; i++) SystemClock.sleep(100);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                ClockActivity clock = ClockActivity.currentInstance;
                check(clock != null && !clock.isFinishing(), "manual standby starts without overlay permission");
                check(clock.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        "standby requests fixed landscape regardless of the system lock");
                check(Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1) == autoRotation
                        && Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, -1) == systemRotation,
                        "standby leaves system rotation preferences intact");
                check(clock.getWindow().getDecorView().findViewWithTag("preview:return") == null,
                        "manual standby is a formal session");
                check((clock.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
                        "manual session keeps the screen on");
            });
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (ClockActivity.currentInstance != null) ClockActivity.currentInstance.finish();
                editor.finish();
            });
        }
    }

    private static Switch findSwitch(View view, String text) {
        if (view instanceof Switch && text.contentEquals(((Switch) view).getText())) return (Switch) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Switch found = findSwitch(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void render(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        bitmap.recycle();
    }

    private static void tap(View view, float x, float y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private ScreenAdaptationChecks() {}
}
