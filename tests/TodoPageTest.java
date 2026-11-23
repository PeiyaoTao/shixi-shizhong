package com.foldstandby.clock;

import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class TodoPageTest {
    public static void main(String[] args) {
        completedTasksMoveBelowPending();
        layoutRespectsWindowAndFold();
        completedCardsStartBelowPending();
        System.out.println("PASS: Today completion, archive, persistence, narrow and wide windows, and fold safety");
    }

    private static void completedTasksMoveBelowPending() {
        SharedPreferences prefs = memoryPreferences();
        TodoStore store = new TodoStore(prefs);
        LocalDate today = TodoStore.today();
        TodoTask first = add(store, "第一项", today);
        TodoTask second = add(store, "第二项", today);
        TodoTask past = add(store, "昨天", today.minusDays(1));
        add(store, "明天", today.plusDays(1));
        add(store, "未安排", null);
        store.change(Collections.singleton(first.id), TodoStore.Action.COMPLETE);
        store.change(Collections.singleton(past.id), TodoStore.Action.COMPLETE);
        TodoStore.Display display = new TodoStore(prefs).display(TodoStore.ALL);
        check(display.today.size() == 2 && display.completed == 1, "Today contains exactly both due items");
        check(display.today.get(0).id.equals(second.id) && display.today.get(1).completed != 0
                && display.today.get(1).id.equals(first.id), "Completion and reload put pending items before completed items");
        check(store.query(TodoStore.View.TODAY, TodoStore.ALL, "").get(1).id.equals(first.id), "Today queries use the display order");
        check(display.undated.size() == 1 && display.overdue == 0, "Other days and undated items do not enter Today");
        store.change(Collections.singleton(first.id), TodoStore.Action.REOPEN);
        check(store.display(TodoStore.ALL).completed == 0 && store.display(TodoStore.ALL).today.get(0).id.equals(first.id),
                "Cancel completion returns the item to the pending sort order");
        store.change(Collections.singleton(first.id), TodoStore.Action.COMPLETE);
        store.change(Collections.singleton(first.id), TodoStore.Action.ARCHIVE);
        check(store.display(TodoStore.ALL).today.size() == 1, "Explicit archive takes a completed item out of Today");
        store.change(Collections.singleton(first.id), TodoStore.Action.UNARCHIVE);
        check(store.display(TodoStore.ALL).completed == 1, "Cancel archive restores the original completion state");
    }

    private static void layoutRespectsWindowAndFold() {
        TodoViewport portrait = TodoViewport.fit(420, 880, null, 1, 1, false, false);
        check(portrait.navigation == null && portrait.content.width == 420, "Portrait uses the full available width");
        TodoViewport landscape = TodoViewport.fit(850, 410, null, 1, 1, true, false);
        check(landscape.navigation != null && landscape.content.height == 410
                && landscape.navigation.left + landscape.navigation.width < landscape.content.left,
                "Wide landscape presents navigation and full-height content");
        check(TodoViewport.fit(540, 320, null, 1, 1, true, false).navigation == null,
                "Narrow landscape gives the list the full width");
        check(TodoViewport.fit(650, 380, null, 1, 1.3f, true, false).navigation == null,
                "Large text can fall back to a single pane");
        TodoViewport.Area split = new TodoViewport.Area(430, 0, 18, 560);
        TodoViewport folded = TodoViewport.fit(950, 560, split, 1, 1, true, false);
        check(folded.navigation != null && folded.navigation.left + folded.navigation.width <= split.left
                        && folded.content.left >= split.left + split.width, "Fold stays between the panes");
        TodoViewport hinge = TodoViewport.fit(800, 610, new TodoViewport.Area(0, 270, 800, 20), 1, 1, true, false);
        check(hinge.content.top >= 290 && hinge.content.top + hinge.content.height <= 610, "Horizontal fold is avoided");
        TodoViewport editor = TodoViewport.fit(900, 270, null, 1, 1, true, true);
        check(editor.navigation != null && editor.navigation.width >= 234 && editor.content.width >= 258
                && editor.navigation.height == editor.content.height, "The editor uses two columns even with the keyboard");
        TodoViewport foldedEditor = TodoViewport.fit(950, 560, split, 1, 1, true, true);
        check(foldedEditor.navigation != null && foldedEditor.navigation.left + foldedEditor.navigation.width <= split.left
                && foldedEditor.content.left >= split.left + split.width, "Editor columns stay clear of the hinge");
        check(TodoViewport.fit(480, 270, null, 1, 1, true, true).navigation == null, "A narrow editor uses one column");
        check(TodoViewport.fit(420, 820, null, 1, 1, false, true).navigation == null, "A portrait editor uses one column");
    }

    private static void completedCardsStartBelowPending() {
        Scene.Component component = new Scene.Component();
        component.type = "todo";
        component.style = "todo_cards";
        TodoLayout layout = TodoLayout.of(component);
        int leading = layout.managedLeadingItems(4, 1);
        check(leading == 3 && layout.rows(4, leading) == 3, "An odd pending card count uses its own rows");
        check(layout.index(1, 1, 4, leading) == -1 && layout.index(2, 0, 4, leading) == 3,
                "The completed card starts below the final pending card");
        check(layout.hit(layout.cellLeft(1) + 1, layout.top + layout.rowHeight + 1, 0, 4, leading) == -1,
                "The empty cell beside the final pending card has no completion target");
    }

    private static TodoTask add(TodoStore store, String title, LocalDate date) {
        TodoTask draft = new TodoTask();
        draft.title = title;
        draft.date = date;
        return store.add(draft, TodoTask.Repeat.NONE);
    }

    private static SharedPreferences memoryPreferences() {
        Map<String, String> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(TodoPageTest.class.getClassLoader(),
                new Class<?>[] { SharedPreferences.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "contains": return values.containsKey(args[0]);
                case "getAll": return new HashMap<>(values);
                case "getString": return values.getOrDefault((String) args[0], (String) args[1]);
                case "edit":
                    return Proxy.newProxyInstance(TodoPageTest.class.getClassLoader(),
                            new Class<?>[] { SharedPreferences.Editor.class }, (edit, operation, data) -> {
                        switch (operation.getName()) {
                            case "putString": values.put((String) data[0], (String) data[1]); return edit;
                            case "apply": return null;
                            case "commit": return true;
                            default: throw new AssertionError(operation.getName());
                        }
                    });
                default: throw new AssertionError(method.getName());
            }
        });
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
