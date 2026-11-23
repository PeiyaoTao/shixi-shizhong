package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;

/** Real input/IME checks against isolated scene preferences, preserving user data. */
final class InputChecks {
    private final Instrumentation runner;
    private MainActivity activity;
    private int assertions;

    private InputChecks(Instrumentation runner) { this.runner = runner; }

    static Bundle run(Instrumentation runner) { return new InputChecks(runner).run(); }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void sync(Runnable action) {
        Throwable[] failure = { null };
        runner.runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { failure[0] = t; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void call(String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, arguments);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private InputDialog dialog() {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("inputDialog");
            field.setAccessible(true);
            return (InputDialog) field.get(activity);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private View find(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private <T extends View> T type(View view, Class<T> wanted) {
        if (wanted.isInstance(view)) return wanted.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = type(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private EditText input(String label) {
        ArrayList<View> found = new ArrayList<>();
        dialog().getWindow().getDecorView().findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View view : found) if (view instanceof EditText && label.contentEquals(view.getContentDescription())) return (EditText) view;
        throw new AssertionError("Missing field " + label);
    }

    private void click(String text) {
        View button = find(dialog().getWindow().getDecorView(), text);
        check(button != null, "Action exists: " + text);
        button.performClick();
    }

    private void visible(View view, String label) {
        Rect rect = new Rect();
        check(view != null && view.getGlobalVisibleRect(rect) && rect.height() >= view.getHeight() - 1 && rect.width() >= view.getWidth() - 1,
            label + " is fully visible above keyboard");
    }

    private void settle() {
        runner.waitForIdleSync();
        SystemClock.sleep(650);
        runner.waitForIdleSync();
    }

    private void snapshot(String name) throws Exception {
        Bitmap bitmap = runner.getUiAutomation().takeScreenshot();
        check(bitmap != null, "Screenshot captured: " + name);
        File folder = new File(runner.getTargetContext().getFilesDir(), "qa-0.3.5");
        folder.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally { bitmap.recycle(); }
    }

    private Bundle run() {
        Bundle result = new Bundle();
        SharedPreferences prefs = runner.getTargetContext().getSharedPreferences("ui_checks", 0);
        Map<String, ?> original = prefs.getAll();
        try {
            prefs.edit().clear().commit();
            activity = (MainActivity) runner.startActivitySync(new Intent(runner.getTargetContext(), MainActivity.class)
                .putExtra("uiCheck", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            sync(() -> {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                Scene.Component todo = activity.draft.pages.get(0).slots[0];
                todo.type = "todo";
                Scene.put(todo.options, "tasks", new JSONArray());
                activity.selectForCheck(1, 0);
                call("show", new Class<?>[0]);
                View add = find(activity.getWindow().getDecorView(), "＋ 添加待办");
                check(add != null, "Local todo editor is available");
                add.performClick();
            });
            settle();
            sync(() -> {
                check(dialog().getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Opening a form shows the real keyboard");
                click("添加");
                check(dialog() != null && find(dialog().getWindow().getDecorView(), "请输入待办内容") != null, "Blank task stays in form with inline validation");
                check(TodoItems.all(activity.draft.pages.get(0).slots[0]).length() == 0, "Blank input creates no task");
                input("待办内容").setText("阅读二十分钟");
                visible(input("待办内容"), "Portrait task field");
                visible(find(dialog().getWindow().getDecorView(), "添加"), "Portrait submit");
            });
            snapshot("todo-portrait-keyboard");
            sync(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            settle();
            sync(() -> {
                check(input("待办内容").getText().toString().equals("阅读二十分钟"), "Input survives rotation");
                visible(input("待办内容"), "Landscape task field");
                visible(find(dialog().getWindow().getDecorView(), "添加"), "Landscape submit");
            });
            snapshot("todo-landscape-keyboard");
            sync(() -> {
                input("待办内容").onEditorAction(EditorInfo.IME_ACTION_DONE);
                Scene.Component todo = activity.draft.pages.get(0).slots[0];
                check(TodoItems.all(todo).length() == 1, "Keyboard Done adds exactly one task");
                Consumer<String> edit = value -> TodoItems.edit(todo, 0, value);
                call("input", new Class<?>[] { String.class, String.class, Consumer.class }, "待办内容", "阅读二十分钟", edit);
                input("待办内容").setText("取消的修改");
                click("取消");
                check(TodoItems.all(todo).optJSONObject(0).optString("text").equals("阅读二十分钟"), "Cancel preserves original task");
                call("input", new Class<?>[] { String.class, String.class, Consumer.class }, "待办内容", "阅读二十分钟", edit);
                input("待办内容").setText("阅读三十分钟");
                click("保存");
                check(TodoItems.all(todo).optJSONObject(0).optString("text").equals("阅读三十分钟"), "Edit updates task");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                call("city", new Class<?>[0]);
            });
            settle();
            snapshot("city-portrait-keyboard");
            sync(() -> {
                click("搜索");
                check(dialog() != null && find(dialog().getWindow().getDecorView(), "请输入城市名称") != null, "Blank city does not search or dismiss");
                input("城市名称").setText("Seattle");
                input("城市名称").onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            });
            for (int i = 0; i < 80; i++) {
                boolean[] ready = { false };
                sync(() -> ready[0] = dialog() != null && find(dialog().getWindow().getDecorView(), "选择城市") != null);
                if (ready[0]) break;
                SystemClock.sleep(200);
            }
            settle();
            sync(() -> check(dialog() != null && find(dialog().getWindow().getDecorView(), "选择城市") != null, "City search returns styled results"));
            snapshot("city-results");
            sync(() -> {
                ListView list = type(dialog().getWindow().getDecorView(), ListView.class);
                check(list.getAdapter().getCount() > 0, "Search results are selectable");
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                check(prefs.getString("city", "").equals("西雅图"), "Selected city persists simplified name");
                call("coordinates", new Class<?>[0]);
                input("纬度 · −90～90").setText("91");
                click("应用");
                check(input("纬度 · −90～90").isActivated(), "Latitude range error belongs to latitude");
                input("纬度 · −90～90").setText("47.6062");
                input("经度 · −180～180").setText("181");
                click("应用");
                check(input("经度 · −180～180").isActivated(), "Longitude range error belongs to longitude");
            });
            settle();
            snapshot("coordinates-portrait-keyboard");
            sync(() -> {
                visible(input("经度 · −180～180"), "Portrait coordinate field");
                visible(find(dialog().getWindow().getDecorView(), "应用"), "Portrait coordinate submit");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            settle();
            sync(() -> {
                check(dialog().getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Coordinate keyboard is open");
                visible(input("经度 · −180～180"), "Landscape coordinate field");
                visible(find(dialog().getWindow().getDecorView(), "应用"), "Landscape coordinate submit");
            });
            snapshot("coordinates-landscape-keyboard");
            sync(() -> {
                input("经度 · −180～180").setText("-122.3321");
                input("经度 · −180～180").onEditorAction(EditorInfo.IME_ACTION_DONE);
                check(prefs.getString("lon", "").equals("-122.3321"), "Signed coordinates apply via keyboard Done");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                call("zone", new Class<?>[] { Scene.Component.class }, activity.draft.pages.get(0).slots[0]);
                input("搜索时区").setText("Tokyo");
            });
            settle();
            snapshot("timezone-search-keyboard");
            sync(() -> {
                ListView list = type(dialog().getWindow().getDecorView(), ListView.class);
                check(list.getAdapter().getCount() > 0 && list.getAdapter().getCount() < 10, "Time zone results filter");
                String expected = list.getAdapter().getItem(0).toString();
                list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0));
                check(activity.draft.pages.get(0).slots[0].options.optString("zone").equals(expected), "Time zone selection persists");
                UiKit.customFontScale = 1.3f;
                Consumer<String> rename = value -> activity.draft.name = value;
                call("input", new Class<?>[] { String.class, String.class, Consumer.class }, "场景名称", "我的场景", rename);
            });
            settle();
            snapshot("rename-large-text-keyboard");
            sync(() -> {
                input("场景名称").setText("夜间阅读");
                click("保存");
                check(activity.draft.name.equals("夜间阅读"), "Name input saves");
            });
            result.putBoolean("passed", true);
            result.putString("result", "PASS: " + assertions + " input checks; real IME, rotation, add/edit/cancel, city HTTPS search/selection, coordinate validation, time zone filtering, large text");
        } catch (Throwable failure) {
            try { snapshot("input-failure"); } catch (Exception ignored) {}
            result.putString("result", android.util.Log.getStackTraceString(failure));
        } finally {
            sync(() -> { if (activity != null) { if (dialog() != null) dialog().dismiss(); activity.finish(); } UiKit.customFontScale = 1; });
            SharedPreferences.Editor restore = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Float) restore.putFloat(key, (Float) value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Set) restore.putStringSet(key, new HashSet<>((Set<String>) value));
            }
            restore.commit();
        }
        return result;
    }
}
