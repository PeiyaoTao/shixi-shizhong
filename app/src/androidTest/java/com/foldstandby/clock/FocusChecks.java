package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** On-device regression suite for custom focus duration picker, settings, inspector, and focus styles. */
final class FocusChecks {
    private final Instrumentation runner;
    private Context context;
    private SharedPreferences prefs;
    private MainActivity live;
    private int assertions;
    private int renders;

    private FocusChecks(Instrumentation runner) {
        this.runner = runner;
    }

    static Bundle run(Instrumentation runner) {
        return new FocusChecks(runner).run();
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void sync(Runnable action) {
        Throwable[] error = { null };
        runner.runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw new AssertionError(error[0]);
    }

    private Object call(Object target, String name) {
        try {
            Method m = target.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception e) {
            throw new AssertionError(name, e);
        }
    }

    private Object get(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new AssertionError(name, e);
        }
    }

    private View findText(View root, String exact) {
        if (root instanceof TextView && exact.equals(((TextView) root).getText().toString())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), exact);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Bundle run() {
        context = runner.getTargetContext();
        prefs = context.getSharedPreferences("ui_checks", 0);
        Map<String, ?> original = prefs.getAll();
        Bundle result = new Bundle();
        try {
            sync(() -> {
                prefs.edit()
                    .clear()
                    .putBoolean("night", false)
                    .putBoolean("motion", false)
                    .putBoolean("studio_guided", true)
                    .putInt("focusMinutes", 25)
                    .commit();
                Scene base = Scene.load(prefs);
                base.save(prefs);
                wiringChecks();
                offscreenPickerDrawChecks();
                focusClockFaceChecks();
            });
            liveDialogChecks();
            result.putBoolean("passed", true);
            result.putString("result", "PASS: " + assertions + " focus checks, " + renders + " device renders");
        } catch (Throwable t) {
            result.putBoolean("passed", false);
            result.putString("result", android.util.Log.getStackTraceString(t));
        } finally {
            sync(() -> {
                UiKit.customFontScale = 1.0f;
                if (live != null) live.finish();
                SharedPreferences.Editor editor = prefs.edit().clear();
                for (Map.Entry<String, ?> entry : original.entrySet()) {
                    Object value = entry.getValue();
                    String key = entry.getKey();
                    if (value instanceof String) editor.putString(key, (String) value);
                    else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                    else if (value instanceof Long) editor.putLong(key, (Long) value);
                    else if (value instanceof Float) editor.putFloat(key, (Float) value);
                    else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
                }
                editor.commit();
            });
        }
        return result;
    }

    private void wiringChecks() {
        MainActivity editor = new MainActivity();
        editor.prepareForCheck(context);
        DeskData.setFocusMinutes(prefs, 25);

        editor.selectForCheck(3, 2);
        View dataSettings = editor.createUi(360, 540);
        check(findText(dataSettings, "默认专注时长 · 25 分钟  ›") != null,
            "data settings shows custom focus duration row");

        editor.draft.pages.get(0).slots[0].setType("focus");
        editor.selectForCheck(1, 0);
        View inspector = editor.createUi(400, 820);
        check(findText(inspector, "默认专注时长 · 25 分钟  ›") != null,
            "component inspector shows default focus duration action");
        check(findText(inspector, "专注计时设置  ›") != null,
            "component inspector retains focus settings shortcut");
    }

    private void offscreenPickerDrawChecks() {
        float savedScale = UiKit.customFontScale;
        try {
            int width = UiKit.dp(context, 320);
            for (float scale : new float[] { 1.0f, 1.30f }) {
                UiKit.customFontScale = scale;
                FocusDurationPicker picker = new FocusDurationPicker(context, 95);
                picker.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                );
                int height = picker.getMeasuredHeight();
                check(height > 0, "picker measures positive height at font scale " + scale);
                picker.layout(0, 0, width, height);
                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                picker.draw(new Canvas(bmp));
                renders++;
                bmp.recycle();
            }
        } finally {
            UiKit.customFontScale = savedScale;
        }
    }

    private void liveDialogChecks() {
        live = (MainActivity) runner.startActivitySync(
            new Intent(context, MainActivity.class)
                .putExtra("uiCheck", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        runner.waitForIdleSync();

        sync(() -> {
            DeskData.setFocusMinutes(prefs, 25);
            call(live, "showFocusDuration");
        });
        runner.waitForIdleSync();

        sync(() -> {
            InputDialog dialog = (InputDialog) get(live, "inputDialog");
            check(dialog != null && dialog.isShowing(), "focus duration dialog is shown");
            View decor = dialog.getWindow().getDecorView();
            FocusDurationPicker picker = decor.findViewWithTag("focus:duration-picker");
            TextView summary = decor.findViewWithTag("focus:duration-summary");
            View hoursWheel = decor.findViewWithTag("focus:wheel-hours");
            View minutesWheel = decor.findViewWithTag("focus:wheel-minutes");
            check(picker != null && summary != null && hoursWheel != null && minutesWheel != null,
                "picker views and wheels exist");
            check(picker.hours() == 0 && picker.minutes() == 25, "picker initializes to 00:25");
            check("共 25 分钟".equals(summary.getText().toString()), "initial summary matches 25 minutes");

            picker.setHoursMinutes(0, 0, false);
            check(summary.getText().toString().contains("至少为 1 分钟"), "zero duration displays warning");
            findText(decor, "应用").performClick();
            check(dialog.isShowing() && prefs.getInt("focusMinutes", 0) == 25,
                "zero duration blocks applying");

            check(hoursWheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null),
                "hours wheel supports backward scroll action");
            check(picker.hours() == 23, "hours wheel wraps backward from 0 to 23");
            check(hoursWheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null),
                "hours wheel supports forward scroll action");
            check(picker.hours() == 0, "hours wheel wraps forward from 23 to 0");

            float cx = minutesWheel.getWidth() / 2f;
            float cy = minutesWheel.getHeight() / 2f;
            float stepY = cy + minutesWheel.getHeight() / 5f;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, stepY, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, cx, stepY, 0);
            minutesWheel.onTouchEvent(down);
            minutesWheel.onTouchEvent(up);
            down.recycle();
            up.recycle();
            check(picker.minutes() == 1, "tapping below center steps minutes wheel forward");

            now = SystemClock.uptimeMillis();
            MotionEvent dragDown = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0);
            MotionEvent dragMove = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, cx, cy - minutesWheel.getHeight() / 3f, 0);
            MotionEvent dragUp = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, cx, cy - minutesWheel.getHeight() / 3f, 0);
            minutesWheel.onTouchEvent(dragDown);
            minutesWheel.onTouchEvent(dragMove);
            minutesWheel.onTouchEvent(dragUp);
            dragDown.recycle();
            dragMove.recycle();
            dragUp.recycle();
            check(picker.minutes() > 1, "dragging minutes wheel upward advances minutes");

            picker.setHoursMinutes(1, 35, false);
            check("共 1 小时 35 分钟".equals(summary.getText().toString()), "summary reflects 1h35m");
            findText(decor, "应用").performClick();
            check(!dialog.isShowing() && prefs.getInt("focusMinutes", 0) == 95,
                "custom 1h35m duration saves as 95 minutes");
        });
        runner.waitForIdleSync();

        sync(() -> call(live, "showFocusDuration"));
        runner.waitForIdleSync();

        sync(() -> {
            InputDialog dialog = (InputDialog) get(live, "inputDialog");
            View decor = dialog.getWindow().getDecorView();
            FocusDurationPicker picker = decor.findViewWithTag("focus:duration-picker");
            TextView summary = decor.findViewWithTag("focus:duration-summary");
            check(picker.hours() == 1 && picker.minutes() == 35, "reopening loads saved 1h35m");

            findText(decor, "45 分钟").performClick();
            check(picker.hours() == 0 && picker.minutes() == 45, "preset chip selects 00:45");
            check("共 45 分钟".equals(summary.getText().toString()), "summary updates after preset chip click");
            findText(decor, "应用").performClick();
            check(!dialog.isShowing() && prefs.getInt("focusMinutes", 0) == 45,
                "preset chip selection applies and saves");
        });
    }

    private void focusClockFaceChecks() {
        for (int style = 0; style < ComponentStyles.ids("focus").length; style++) {
            Bitmap b25 = renderFocusStyle(style, 25, false);
            Bitmap b95 = renderFocusStyle(style, 95, false);
            Bitmap b150 = renderFocusStyle(style, 150, false);
            Bitmap demo95 = renderFocusStyle(style, 95, true);
            check(!b25.sameAs(b95), "focus style " + style + " updates render for custom 1h35m duration");
            b25.recycle();
            b95.recycle();
            b150.recycle();
            demo95.recycle();
        }
    }

    private Bitmap renderFocusStyle(int style, int minutes, boolean demo) {
        prefs.edit().putLong("focusEnd", 0).remove("focusResetAt").commit();
        DeskData.setFocusMinutes(prefs, minutes);
        DeskData.resetFocus(prefs);
        prefs.edit().remove("focusResetAt").commit();

        Scene scene = Scene.load(prefs);
        scene.pages.get(0).layout = 0;
        ComponentStyles.select(scene.pages.get(0).slots[0], "focus", style);

        ClockFace face = new ClockFace(context, prefs);
        face.bind(scene, 0);
        face.nightPreview = false;
        face.demo = demo;
        face.testTime = 10000L;
        face.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(684, View.MeasureSpec.EXACTLY)
        );
        face.layout(0, 0, 1080, 684);
        Bitmap bitmap = Bitmap.createBitmap(1080, 684, Bitmap.Config.ARGB_8888);
        face.draw(new Canvas(bitmap));
        renders++;
        return bitmap;
    }
}
