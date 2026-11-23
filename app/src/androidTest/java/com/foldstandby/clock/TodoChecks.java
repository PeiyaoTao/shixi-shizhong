package com.foldstandby.clock;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/** Covers the migrations and state transitions where mistakes can lose live tasks. */
final class TodoChecks {
    static Bundle run(Instrumentation test) {
        Bundle result = new Bundle();
        try {
            Context target = test.getTargetContext();
            storedTasks(target);
            String ui = "model checks";
            if (target.getPackageName().endsWith(".todoqa")) ui = userFlow(test, target) + ", " + reminder(test, target);
            result.putBoolean("passed", true);
            result.putString("result", "PASS: task migration, dates, recurrence, archive, trash, undo, persistence and " + ui);
        } catch (Throwable failure) {
            result.putString("result", android.util.Log.getStackTraceString(failure));
        }
        return result;
    }

    private static void storedTasks(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("todo_lifecycle_checks", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        Scene current = Scene.load(prefs);
        Scene.Page page = current.pages.get(0);
        TodoItems.add(page.slots[2], "先前已完成");
        TodoItems.toggle(page.slots[2], 0);
        TodoItems.add(page.slots[2], "迁移隐藏事项");
        current.pages.add(page.duplicate());
        Scene historical = current.copy();
        TodoItems.add(historical.pages.get(0).slots[2], "只存在于旧快照");
        current.save(prefs);
        prefs.edit().putString("scene_saved_早期场景", historical.json()).commit();
        TodoStore store = new TodoStore(prefs);

        Scene migrated = Scene.load(prefs);
        Scene.Component first = migrated.pages.get(0).slots[2];
        Scene.Component other = migrated.pages.get(migrated.pages.size() - 1).slots[2];
        String list = store.listFor(first);
        check(!first.options.has("tasks"), "Legacy data removed from the live scene");
        check(!list.equals(store.listFor(other)), "Copies with the same legacy task IDs migrate independently");
        check(store.query(TodoStore.View.UNDATED, list, "").size() == 1, "Hidden tasks have no inferred date");
        check(store.query(TodoStore.View.COMPLETED, list, "").size() == 1, "Legacy completion is preserved");
        check(store.query(TodoStore.View.ALL_TASKS, list, "只存在于旧快照").isEmpty(), "History does not overwrite live data");
        String copied = store.importLegacy(store.legacyLists().get(0).id);
        check(store.query(TodoStore.View.UNDATED, copied, "只存在于旧快照").size() == 1, "Historical tasks can be imported to a copy");
        store.bind(historical);
        check(store.listFor(historical.pages.get(0).slots[2]).equals(list), "Old layouts reconnect to current list");

        LocalDate today = TodoStore.today();
        TodoTask dated = add(store, list, "今天要做", today, TodoTask.Repeat.NONE);
        TodoTask late = add(store, list, "逾期要确认", today.minusDays(1), TodoTask.Repeat.NONE);
        TodoTask future = add(store, list, "将来计划", today.plusDays(1), TodoTask.Repeat.NONE);
        TodoTask note = new TodoTask();
        note.list = list;
        note.title = "无日期也可以提醒";
        note.note = "编号 QZ-932";
        note.steps.add(new TodoTask.Step("收集素材", true));
        note.reminder = LocalDateTime.now().plusHours(2);
        TodoTask undated = store.add(note, TodoTask.Repeat.NONE);
        TodoStore.Display display = store.display(list);
        check(display.today.size() == 1 && display.today.get(0).id.equals(dated.id), "Today includes only the exact local date");
        check(display.overdue == 1 && display.undated.size() == 2, "Overdue and undated are distinct");
        check(store.query(TodoStore.View.PLANNED, list, "").get(0).id.equals(future.id), "Future lives in Planned");
        check(store.query(TodoStore.View.UNDATED, list, "QZ-932").size() == 1, "Search includes notes");
        check(store.query(TodoStore.View.UNDATED, list, "收集素材").size() == 1, "Search includes subtasks");
        check(store.pendingReminders().get(0).id.equals(undated.id), "A reminder can exist without a due date");

        TodoStore.Undo completed = store.change(ids(dated), TodoStore.Action.COMPLETE);
        check(store.display(list).today.size() == 1 && store.display(list).completed == 1
                && store.display(list).today.get(0).id.equals(dated.id), "Today's completed task remains in Today");
        check(store.query(TodoStore.View.TODAY, list, "").get(0).completed != 0, "Today selections include completed tasks");
        check(store.undo(completed) == 1 && store.display(list).today.size() == 1, "Undo restores Today");
        store.change(ids(late), TodoStore.Action.ARCHIVE);
        check(store.display(list).overdue == 0 && store.get(late.id).date.equals(today.minusDays(1)), "Archive keeps original date");
        TodoStore.Undo delete = store.change(ids(late), TodoStore.Action.DELETE);
        check(store.query(TodoStore.View.TRASH, list, "").get(0).id.equals(late.id), "Trash records archived context");
        check(store.undo(delete) == 1 && store.get(late.id).archived != 0, "Undo delete restores archive status");
        store.change(ids(late), TodoStore.Action.DELETE);
        store.change(ids(late), TodoStore.Action.RESTORE);
        store.change(ids(late), TodoStore.Action.UNARCHIVE);
        check(store.display(list).overdue == 1, "Unarchive restores original overdue state");
        store.reschedule(ids(late), today);
        check(store.display(list).overdue == 0 && store.display(list).today.size() == 2, "Reschedule uses the selected date");

        String repeatList = store.createList("重复事项");
        TodoTask series = add(store, repeatList, "每天一次", today.minusDays(2), TodoTask.Repeat.DAILY);
        check(store.display(repeatList).today.size() == 1 && store.display(repeatList).overdue == 2, "Missed occurrences do not block today's");
        store.change(ids(series), TodoStore.Action.DELETE);
        check(store.display(repeatList).overdue == 1 && store.display(repeatList).today.size() == 1, "Delete affects only one occurrence");
        TodoTask next = store.display(repeatList).today.get(0).copy();
        next.title = "工作日例行";
        store.update(next, TodoTask.Repeat.WEEKDAYS, true);
        check(store.display(repeatList).today.size() == 1 && store.get(next.id).title.equals("工作日例行"), "Editing a series replaces its future");
        check(TodoTask.Repeat.MONTHLY.next(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29)).equals(LocalDate.of(2024, 3, 31)),
                "Monthly repetition recovers the chosen day after February");

        TodoTask stale = add(store, list, "超过三十天的删除项", null, TodoTask.Repeat.NONE);
        store.change(ids(stale), TodoStore.Action.DELETE);
        JSONObject stored = new JSONObject(prefs.getString(TodoStore.KEY, ""));
        JSONArray records = stored.getJSONArray("tasks");
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.getJSONObject(i);
            if (stale.id.equals(item.getString("id"))) item.put("deleted", System.currentTimeMillis() - TodoStore.TRASH_RETENTION - 1000);
        }
        prefs.edit().putString(TodoStore.KEY, stored.toString()).commit();
        TodoStore reloaded = new TodoStore(prefs);
        check(reloaded.get(stale.id) == null, "Trash expires after thirty days");
        check(reloaded.get(undated.id).steps.get(0).done, "Subtasks survive a reload");
        check(reloaded.query(TodoStore.View.TRASH, repeatList, "").size() == 1, "Recent trash survives a reload");
        check(reloaded.display(repeatList).today.size() == 1, "Recurrences do not duplicate after a reload");
    }

    private static String userFlow(Instrumentation test, Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("clock", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("private", true).commit();
        TodoStore store = TodoStore.open(context);
        String list = store.createList("待办验收");
        add(store, list, "预计未来计划", TodoStore.today().plusDays(2), TodoTask.Repeat.NONE);
        add(store, list, "昨天遗漏事项", TodoStore.today().minusDays(1), TodoTask.Repeat.NONE);
        add(store, list, "未选日期事项", null, TodoTask.Repeat.NONE);
        Scene scene = Scene.load(prefs);
        Scene.Page home = scene.pages.get(scene.homeIndex());
        home.layout = 0;
        home.slots[0].setType("todo");
        Scene.put(home.slots[0].options, "todoList", list);
        scene.save(prefs);
        Intent intent = new Intent(context, ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ClockActivity clock = (ClockActivity) test.startActivitySync(intent);
        waitForWindow(test, clock);
        Instrumentation.ActivityMonitor monitor = test.addMonitor(TodoActivity.class.getName(), null, false);
        test.runOnMainSync(() -> {
            ClockStage stage = child(clock.getWindow().getDecorView(), ClockStage.class);
            check(stage != null && stage.content.getChildAt(0) instanceof ClockFace, "Standby opens the latest todo component");
            ClockFace face = (ClockFace) stage.content.getChildAt(0);
            AccessibilityNodeInfo info = face.createAccessibilityNodeInfo();
            for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
                if ("添加待办 · 待办验收".contentEquals(action.getLabel() == null ? "" : action.getLabel())) {
                    check(face.performAccessibilityAction(action.getId(), null), "Standby's quick add opens the manager");
                    return;
                }
            }
            throw new AssertionError("Standby's todo quick add action is unavailable");
        });
        TodoActivity activity = (TodoActivity) monitor.waitForActivityWithTimeout(5000);
        test.removeMonitor(monitor);
        check(activity != null, "A new task activity opens from standby");
        try {
            waitForWindow(test, activity);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            boolean locked = keyguard != null && (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked());
            test.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                EditText title = field(root, "待办标题");
                check(title != null, "Quick add focuses the title");
                check(find(root, "日期 · 无日期") != null, "Quick add never stamps today on open");
                View options = root.findViewWithTag("todo:editor-details");
                if (options != null) {
                    View basic = root.findViewWithTag("todo:editor-basic");
                    int[] contentPosition = new int[2], settingsPosition = new int[2];
                    basic.getLocationInWindow(contentPosition);
                    options.getLocationInWindow(settingsPosition);
                    check(contentPosition[0] + basic.getWidth() <= settingsPosition[0]
                            && field(options, "添加备注") != null && find(root, "保存") != null,
                            "Wide quick add separates basics and settings and offers a visible save action");
                }
                title.setText("实测当天待办");
                tap(root, "今天");
                title.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                check(find(root, "实测当天待办") != null, "Explicit Today appears immediately");
                check(find(root, "预计未来计划") == null && find(root, "昨天遗漏事项") == null, "Today excludes other dates");
                check(find(root, "未选日期事项") == null, "Undated section begins collapsed");
                check(find(root, "1 项已过期  ›") != null, "Today exposes an overdue count");
                tap(root, "未安排 · 1 项  ▾");
                check(find(root, "未选日期事项") != null, "Undated tasks can be expanded separately");
                tap(root, "完成 实测当天待办");
                TextView completedTitle = (TextView) find(root, "实测当天待办");
                check(completedTitle != null && (completedTitle.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG) != 0,
                        "Today's task remains visible with a strikethrough after completion");
                check(find(root, "已完成 1 / 1") != null, "Completion progress includes the retained task");
                tap(root, "取消完成 实测当天待办");
                TextView reopenedTitle = (TextView) find(root, "实测当天待办");
                check(reopenedTitle != null && (reopenedTitle.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG) == 0,
                        "The same checkbox removes the completion strikethrough");
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                screenshot(context, root);
                View more = find(root, "实测当天待办 的更多操作");
                check(more != null && more.performClick(), "Task options are reachable");
            });
            tapDialog(test, "归档");
            TodoTask archived = store.query(TodoStore.View.ARCHIVED, list, "实测").get(0);
            test.runOnMainSync(() -> tap(activity.getWindow().getDecorView(), "撤销"));
            test.waitForIdleSync();
            check(store.get(archived.id).active(), "Archive toast undo restores the task");
            test.runOnMainSync(() -> tap(activity.getWindow().getDecorView(), "实测当天待办 的更多操作"));
            tapDialog(test, "删除");
            check(store.get(archived.id).deleted > 0, "Single deletion enters the recycle bin");
            boolean[] wider = {false};
            test.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                View rail = root.findViewWithTag("todo:navigation");
                wider[0] = rail != null && rail.isShown();
                tap(root, wider[0] ? "回收站" : "更多视图 ▾");
            });
            if (!wider[0]) tapDialog(test, "回收站");
            test.runOnMainSync(() -> tap(activity.getWindow().getDecorView(), "实测当天待办 的更多操作"));
            tapDialog(test, "恢复");
            check(store.get(archived.id).active(), "The recycle bin restores the original state");
            return locked ? "locked-window standby, create, archive, undo, delete and restore"
                    : "device UI standby, create, archive, undo, delete and restore";
        } finally {
            test.runOnMainSync(() -> {
                activity.finish();
                clock.finish();
            });
        }
    }

    private static String reminder(Instrumentation test, Context context) throws InterruptedException {
        TodoStore store = TodoStore.open(context);
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            test.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(TodoReminders.CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_LOW));
        if (!TodoReminders.precise(context)) return "reminders persisted (exact alarm permission unavailable)";
        TodoTask draft = new TodoTask();
        draft.title = "待办 QA 定时通知";
        draft.reminder = LocalDateTime.now().plusSeconds(4);
        TodoTask task = store.add(draft, TodoTask.Repeat.NONE);
        try {
            TodoReminders.schedule(context);
            for (int i = 0; i < 50; i++) {
                for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
                    if (task.id.equals(notification.getTag())) return "scheduled device notification";
                }
                Thread.sleep(200);
            }
            throw new AssertionError("Scheduled device reminder did not arrive");
        } finally {
            store.change(ids(task), TodoStore.Action.DELETE);
            store.erase(ids(task));
            TodoReminders.cancel(context, ids(task));
            TodoReminders.schedule(context);
        }
    }

    private static void screenshot(Context context, View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = context.openFileOutput("todo-qa.png", Context.MODE_PRIVATE)) {
            view.draw(new Canvas(bitmap));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not save the test screen", error);
        } finally {
            bitmap.recycle();
        }
    }

    private static void waitForWindow(Instrumentation test, Activity activity) throws InterruptedException {
        for (int i = 0; i < 24; i++) {
            test.waitForIdleSync();
            if (activity.hasWindowFocus()) return;
            Thread.sleep(125);
        }
        throw new AssertionError("Todo manager window didn't gain focus");
    }

    private static <T extends View> T child(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T next = child(group.getChildAt(i), type);
                if (next != null) return next;
            }
        }
        return null;
    }

    private static void tapDialog(Instrumentation test, String value) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            test.waitForIdleSync();
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(value)) {
                    if (value.contentEquals(node.getText() == null ? "" : node.getText())
                            && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        test.waitForIdleSync();
                        return;
                    }
                }
            }
            Thread.sleep(120);
        }
        throw new AssertionError("Dialog action unavailable: " + value);
    }

    private static EditText field(View root, String hint) {
        if (root instanceof EditText && hint.contentEquals(((EditText) root).getHint())) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText value = field(group.getChildAt(i), hint);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static View find(View root, String label) {
        if (label.equals(root.getContentDescription())) return root;
        if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void tap(View root, String label) {
        View view = find(root, label);
        check(view != null && view.performClick(), "Can click " + label);
    }

    private static TodoTask add(TodoStore store, String list, String title, LocalDate date, TodoTask.Repeat repeat) {
        TodoTask task = new TodoTask();
        task.title = title;
        task.list = list;
        task.date = date;
        return store.add(task, repeat);
    }

    private static List<String> ids(TodoTask task) { return Collections.singletonList(task.id); }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
