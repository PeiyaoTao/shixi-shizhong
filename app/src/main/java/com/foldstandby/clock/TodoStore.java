package com.foldstandby.clock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Device-local task data. Scene files contain only a reference to the selected list. */
final class TodoStore {
    static final String KEY = "todo_store_v1";
    static final String INBOX = "inbox";
    static final String ALL = "";
    static final long TRASH_RETENTION = TimeUnit.DAYS.toMillis(30);
    private static TodoStore instance;

    enum View {
        TODAY("今天"), UNDATED("未安排"), PLANNED("计划"), OVERDUE("已过期"),
        IMPORTANT("重要"), ALL_TASKS("全部待办"), COMPLETED("已完成"),
        ARCHIVED("已归档"), TRASH("回收站");

        final String title;
        View(String title) { this.title = title; }

        boolean includes(TodoTask task, LocalDate today) {
            switch (this) {
                case TODAY: return today.equals(task.date) && task.deleted == 0 && task.archived == 0;
                case TRASH: return task.deleted != 0;
                case ARCHIVED: return task.deleted == 0 && task.archived != 0;
                case COMPLETED: return task.deleted == 0 && task.archived == 0 && task.completed != 0;
                default: return task.active() && includesActive(task, today);
            }
        }

        private boolean includesActive(TodoTask task, LocalDate today) {
            switch (this) {
                case UNDATED: return task.date == null;
                case PLANNED: return task.date != null && task.date.isAfter(today);
                case OVERDUE: return task.date != null && task.date.isBefore(today);
                case IMPORTANT: return task.priority > 0;
                default: return true;
            }
        }
    }

    enum Action {
        COMPLETE("已完成"), REOPEN("已恢复为待办"), ARCHIVE("已归档"),
        UNARCHIVE("已取消归档"), DELETE("已移入回收站"), RESTORE("已从回收站恢复");

        final String message;
        Action(String message) { this.message = message; }

        boolean apply(TodoTask task, long now) {
            switch (this) {
                case COMPLETE:
                    if (!task.active()) return false;
                    task.completed = now;
                    return true;
                case REOPEN:
                    if (task.completed == 0 || task.deleted != 0 || task.archived != 0) return false;
                    task.completed = 0;
                    return true;
                case ARCHIVE:
                    if (task.archived != 0 || task.deleted != 0) return false;
                    task.archived = now;
                    return true;
                case UNARCHIVE:
                    if (task.archived == 0 || task.deleted != 0) return false;
                    task.archived = 0;
                    return true;
                case DELETE:
                    if (task.deleted != 0) return false;
                    task.deleted = now;
                    return true;
                case RESTORE:
                    if (task.deleted == 0) return false;
                    task.deleted = 0;
                    return true;
                default: return false;
            }
        }
    }

    static final class Undo {
        private final Map<String, TodoTask> before = new LinkedHashMap<>();
        private final Map<String, String> after = new LinkedHashMap<>();

        int count() { return before.size(); }

        void record(TodoTask previous, TodoTask current) {
            before.put(previous.id, previous);
            after.put(previous.id, current.json().toString());
        }
    }

    static final class Display {
        final List<TodoTask> today = new ArrayList<>();
        final List<TodoTask> undated = new ArrayList<>();
        int completed, overdue;
    }

    static final class LegacyList {
        final String id, name;
        final JSONArray tasks;

        LegacyList(String id, String name, JSONArray tasks) {
            this.id = id;
            this.name = name;
            this.tasks = tasks;
        }
    }

    private static final class Series {
        String id;
        TodoTask template;
        TodoTask.Repeat repeat;
        LocalDate last, end;

        JSONObject json() {
            JSONObject value = new JSONObject();
            Scene.put(value, "id", id);
            Scene.put(value, "template", template.json());
            Scene.put(value, "repeat", repeat.name());
            if (last != null) Scene.put(value, "last", last.toString());
            if (end != null) Scene.put(value, "end", end.toString());
            return value;
        }

        static Series read(JSONObject value) {
            if (value == null || value.optJSONObject("template") == null) return null;
            Series series = new Series();
            series.id = value.optString("id");
            series.template = TodoTask.read(value.optJSONObject("template"));
            series.repeat = TodoTask.Repeat.read(value.optString("repeat"));
            series.last = TodoTask.date(value.optString("last"));
            series.end = TodoTask.date(value.optString("end"));
            return series.template.date == null || series.repeat == TodoTask.Repeat.NONE ? null : series;
        }
    }

    private static final Comparator<TodoTask> TODAY_ORDER = Comparator
            .comparingInt((TodoTask task) -> task.priority).reversed()
            .thenComparing(task -> task.reminder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(task -> task.created);
    private static final Comparator<TodoTask> TODAY_DISPLAY_ORDER = Comparator
            .comparing((TodoTask task) -> task.completed != 0)
            .thenComparing(TODAY_ORDER);
    private static final Comparator<TodoTask> DATE_ORDER = Comparator
            .comparing((TodoTask task) -> task.date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TODAY_ORDER);

    private final SharedPreferences prefs;
    private final Map<String, String> lists = new LinkedHashMap<>();
    private final Map<String, TodoTask> tasks = new LinkedHashMap<>();
    private final Map<String, Series> series = new LinkedHashMap<>();
    private final Map<String, LegacyList> legacy = new LinkedHashMap<>();
    private final Map<String, Display> displayCache = new LinkedHashMap<>();
    private LocalDate lastRefresh;
    private long nextPurge = Long.MAX_VALUE;

    static synchronized TodoStore open(Context context) {
        if (instance == null) instance = new TodoStore(context.getSharedPreferences("clock", Context.MODE_PRIVATE));
        return instance;
    }

    TodoStore(SharedPreferences prefs) {
        this.prefs = prefs;
        lists.put(INBOX, "收件箱");
        if (!prefs.contains(KEY)) migrate();
        else load(prefs.getString(KEY, ""));
    }

    static LocalDate today() { return LocalDate.now(); }

    synchronized Map<String, String> lists() { return new LinkedHashMap<>(lists); }

    synchronized String listName(String id) {
        return ALL.equals(id) ? "所有清单" : lists.getOrDefault(id, lists.get(INBOX));
    }

    synchronized String listFor(Scene.Component component) {
        String selection = component.options.optString("todoList");
        if (lists.containsKey(selection)) return selection;
        String original = "legacy-" + component.id;
        return selection.isEmpty() && lists.containsKey(original) ? original : INBOX;
    }

    synchronized void bind(Scene scene) {
        for (Scene.Page page : scene.pages) {
            for (Scene.Component component : page.slots) {
                if (component.options.has("tasks")) {
                    Scene.put(component.options, "todoList", listFor(component));
                    component.options.remove("tasks");
                }
            }
        }
    }

    synchronized String createList(String name) {
        String id = newId();
        lists.put(id, name.trim());
        persist();
        return id;
    }

    synchronized void renameList(String id, String name) {
        if (!lists.containsKey(id) || id.equals(INBOX) || name.trim().isEmpty()) return;
        lists.put(id, name.trim());
        persist();
    }

    synchronized void removeList(String id) {
        if (id.equals(INBOX) || lists.remove(id) == null) return;
        for (TodoTask task : tasks.values()) if (id.equals(task.list)) task.list = INBOX;
        for (Series entry : series.values()) if (id.equals(entry.template.list)) entry.template.list = INBOX;
        persist();
    }

    synchronized List<TodoTask> query(View view, String list, String search) {
        refresh();
        LocalDate today = lastRefresh;
        String needle = search.trim().toLowerCase(java.util.Locale.ROOT);
        List<TodoTask> result = new ArrayList<>();
        for (TodoTask task : tasks.values()) {
            if (inList(task, list) && view.includes(task, today) && matches(task, needle)) result.add(task.copy());
        }
        if (view == View.COMPLETED) result.sort(Comparator.comparingLong((TodoTask task) -> task.completed).reversed());
        else if (view == View.ARCHIVED) result.sort(Comparator.comparingLong((TodoTask task) -> task.archived).reversed());
        else if (view == View.TRASH) result.sort(Comparator.comparingLong((TodoTask task) -> task.deleted).reversed());
        else if (view == View.TODAY) result.sort(TODAY_DISPLAY_ORDER);
        else if (view == View.UNDATED) result.sort(TODAY_ORDER);
        else result.sort(DATE_ORDER);
        return result;
    }

    private static boolean inList(TodoTask task, String list) {
        return ALL.equals(list) || task.list.equals(list);
    }

    private boolean matches(TodoTask task, String needle) {
        if (needle.isEmpty()) return true;
        if (contains(task.title, needle) || contains(task.note, needle) || contains(listName(task.list), needle)) return true;
        for (TodoTask.Step step : task.steps) if (contains(step.title, needle)) return true;
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    synchronized Display display(String list) {
        refresh();
        Display cached = displayCache.get(list);
        if (cached != null) return cached;
        Display display = new Display();
        for (TodoTask task : tasks.values()) {
            if (!inList(task, list) || task.deleted != 0 || task.archived != 0) continue;
            if (lastRefresh.equals(task.date)) {
                display.today.add(task.copy());
                if (task.completed != 0) display.completed++;
            } else if (task.active()) {
                if (task.date == null) display.undated.add(task.copy());
                else if (task.date.isBefore(lastRefresh)) display.overdue++;
            }
        }
        display.today.sort(TODAY_DISPLAY_ORDER);
        display.undated.sort(TODAY_ORDER);
        displayCache.put(list, display);
        return display;
    }

    synchronized TodoTask get(String id) {
        refresh();
        TodoTask task = tasks.get(id);
        return task == null ? null : task.copy();
    }

    synchronized TodoTask.Repeat repeatOf(TodoTask task) {
        Series entry = series.get(task.series);
        return entry == null ? TodoTask.Repeat.NONE : entry.repeat;
    }

    synchronized boolean canEditSeries(TodoTask task) {
        Series entry = series.get(task.series);
        return entry != null && task.occurrence != null
                && (entry.end == null || !task.occurrence.isAfter(entry.end));
    }

    synchronized TodoTask add(TodoTask draft, TodoTask.Repeat repeat) {
        TodoTask task = draft.copy();
        task.id = newId();
        task.created = System.currentTimeMillis();
        task.list = lists.containsKey(task.list) ? task.list : INBOX;
        task.title = task.title.trim();
        if (task.title.isEmpty()) throw new IllegalArgumentException("Empty task title");
        if (repeat != TodoTask.Repeat.NONE && task.date != null) startSeries(task, repeat, null);
        tasks.put(task.id, task);
        expand(today());
        persist();
        return task.copy();
    }

    synchronized boolean update(TodoTask draft, TodoTask.Repeat repeat, boolean changeSeries) {
        TodoTask prior = tasks.get(draft.id);
        if (prior == null || prior.deleted != 0 || draft.title.trim().isEmpty()) return false;
        TodoTask task = draft.copy();
        task.list = lists.containsKey(task.list) ? task.list : INBOX;
        task.title = task.title.trim();
        task.completed = prior.completed;
        task.archived = prior.archived;
        task.notified = Objects.equals(prior.reminder, task.reminder) ? prior.notified : null;
        task.customized = true;
        if (changeSeries && canEditSeries(prior)) {
            Series old = series.get(prior.series);
            LocalDate end = old.end;
            old.end = prior.occurrence.minusDays(1);
            tasks.values().removeIf(candidate -> prior.series.equals(candidate.series)
                    && candidate.occurrence.isAfter(prior.occurrence)
                    && candidate.active() && !candidate.customized);
            task.series = "";
            task.occurrence = null;
            if (repeat != TodoTask.Repeat.NONE && task.date != null) startSeries(task, repeat, end);
        } else if (prior.series.isEmpty() && repeat != TodoTask.Repeat.NONE && task.date != null) {
            startSeries(task, repeat, null);
        }
        tasks.put(task.id, task);
        expand(today());
        persist();
        return true;
    }

    private void startSeries(TodoTask task, TodoTask.Repeat repeat, LocalDate end) {
        Series entry = new Series();
        entry.id = newId();
        entry.repeat = repeat;
        if (task.family.isEmpty()) task.family = entry.id;
        entry.template = task.copy();
        for (TodoTask.Step step : entry.template.steps) step.done = false;
        entry.last = task.date;
        entry.end = end;
        series.put(entry.id, entry);
        task.series = entry.id;
        task.occurrence = task.date;
        task.customized = false;
    }

    synchronized Undo change(Collection<String> ids, Action action) {
        refresh();
        Undo undo = new Undo();
        long now = System.currentTimeMillis();
        for (String id : ids) {
            TodoTask task = tasks.get(id);
            if (task == null) continue;
            TodoTask previous = task.copy();
            if (action.apply(task, now)) undo.record(previous, task);
        }
        if (undo.count() > 0) persist();
        return undo;
    }

    synchronized Undo reschedule(Collection<String> ids, LocalDate date) {
        Undo undo = new Undo();
        for (String id : ids) {
            TodoTask task = tasks.get(id);
            if (task == null || !task.active() || Objects.equals(task.date, date)) continue;
            TodoTask previous = task.copy();
            task.date = date;
            task.customized = true;
            undo.record(previous, task);
        }
        if (undo.count() > 0) persist();
        return undo;
    }

    synchronized int undo(Undo undo) {
        int restored = 0;
        for (TodoTask before : undo.before.values()) {
            TodoTask current = tasks.get(before.id);
            if (current != null && current.json().toString().equals(undo.after.get(before.id))) {
                before.list = lists.containsKey(before.list) ? before.list : INBOX;
                tasks.put(before.id, before.copy());
                restored++;
            }
        }
        if (restored > 0) persist();
        return restored;
    }

    synchronized void erase(Collection<String> ids) {
        if (tasks.values().removeIf(task -> task.deleted != 0 && ids.contains(task.id))) persist();
    }

    synchronized List<TodoTask> pendingReminders() {
        refresh();
        List<TodoTask> result = new ArrayList<>();
        for (TodoTask task : tasks.values()) {
            if (task.active() && task.reminder != null && !task.reminder.equals(task.notified)) result.add(task.copy());
        }
        return result;
    }

    synchronized long nextTrashExpiry() {
        refresh();
        return nextPurge;
    }

    synchronized void markNotified(Collection<TodoTask> delivered) {
        boolean changed = false;
        for (TodoTask sent : delivered) {
            TodoTask task = tasks.get(sent.id);
            if (task == null || !Objects.equals(task.reminder, sent.reminder)) continue;
            task.notified = sent.reminder;
            changed = true;
        }
        if (changed) persist();
    }

    synchronized List<LegacyList> legacyLists() { return new ArrayList<>(legacy.values()); }

    synchronized String importLegacy(String id) {
        LegacyList entry = legacy.get(id);
        if (entry == null) return INBOX;
        String list = newId();
        lists.put(list, entry.name + " · 导入");
        importTasks(list, entry.tasks);
        persist();
        return list;
    }

    private void refresh() {
        LocalDate today = today();
        long now = System.currentTimeMillis();
        if (today.equals(lastRefresh) && now < nextPurge) return;
        lastRefresh = today;
        displayCache.clear();
        boolean changed = expand(today);
        changed |= tasks.values().removeIf(task -> task.deleted != 0 && now - task.deleted >= TRASH_RETENTION);
        if (changed) persist();
        else updatePurgeTime();
    }

    private boolean expand(LocalDate today) {
        boolean changed = false;
        LocalDate horizon = today.plusDays(14);
        HashSet<String> represented = new HashSet<>();
        for (TodoTask task : tasks.values()) {
            if (!task.family.isEmpty() && task.occurrence != null) represented.add(task.family + "/" + task.occurrence);
        }
        for (Series entry : series.values()) {
            if (entry.last == null) continue;
            LocalDate next = entry.repeat.next(entry.template.date, entry.last);
            while (!entry.last.isAfter(horizon) && (entry.end == null || !next.isAfter(entry.end))) {
                if (represented.add(entry.template.family + "/" + next)) {
                    TodoTask occurrence = entry.template.copy();
                    occurrence.id = newId();
                    occurrence.series = entry.id;
                    occurrence.date = occurrence.occurrence = next;
                    occurrence.created = next.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    occurrence.completed = occurrence.archived = occurrence.deleted = 0;
                    occurrence.notified = null;
                    if (occurrence.reminder != null) {
                        long days = ChronoUnit.DAYS.between(entry.template.date, next);
                        occurrence.reminder = occurrence.reminder.plusDays(days);
                    }
                    tasks.put(occurrence.id, occurrence);
                }
                entry.last = next;
                next = entry.repeat.next(entry.template.date, next);
                changed = true;
            }
        }
        return changed;
    }

    private void updatePurgeTime() {
        nextPurge = Long.MAX_VALUE;
        for (TodoTask task : tasks.values()) {
            if (task.deleted > 0) nextPurge = Math.min(nextPurge, task.deleted + TRASH_RETENTION);
        }
    }

    private void load(String value) {
        try {
            JSONObject root = new JSONObject(value);
            JSONArray listData = root.optJSONArray("lists"), taskData = root.optJSONArray("tasks");
            JSONArray seriesData = root.optJSONArray("series"), legacyData = root.optJSONArray("legacy");
            for (int i = 0; listData != null && i < listData.length(); i++) {
                JSONObject list = listData.getJSONObject(i);
                lists.put(list.getString("id"), list.getString("name"));
            }
            for (int i = 0; taskData != null && i < taskData.length(); i++) {
                TodoTask task = TodoTask.read(taskData.getJSONObject(i));
                tasks.put(task.id, task);
            }
            for (int i = 0; seriesData != null && i < seriesData.length(); i++) {
                Series entry = Series.read(seriesData.optJSONObject(i));
                if (entry != null) series.put(entry.id, entry);
            }
            for (int i = 0; legacyData != null && i < legacyData.length(); i++) {
                JSONObject entry = legacyData.getJSONObject(i);
                LegacyList list = new LegacyList(entry.getString("id"), entry.getString("name"), entry.getJSONArray("tasks"));
                legacy.put(list.id, list);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to read saved tasks", error);
        }
    }

    private void migrate() {
        Scene live = Scene.parse(prefs.getString(Scene.KEY, ""));
        if (live != null) {
            for (Scene.Page page : live.pages) {
                for (int index = 0; index < page.slots.length; index++) {
                    Scene.Component component = page.slots[index];
                    JSONArray entries = component.options.optJSONArray("tasks");
                    if (entries == null || entries.length() == 0) continue;
                    String id = "legacy-" + component.id;
                    lists.put(id, sourceName(page, index));
                    importTasks(id, entries);
                }
            }
            bind(live);
        }
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("scene_saved_") && entry.getValue() instanceof String) {
                saveLegacy(entry.getKey().substring(12), (String) entry.getValue());
            }
        }
        try {
            JSONArray points = new JSONArray(prefs.getString("studio_recovery", "[]"));
            for (int i = 0; i < points.length(); i++) {
                JSONObject point = points.optJSONObject(i);
                if (point == null) continue;
                JSONObject state = recoveryState(point);
                if (state == null) continue;
                String label = "恢复点 " + (i + 1) + " · " + point.optString("label");
                saveLegacy(label, state.optString("scene"));
                JSONObject snapshots = state.optJSONObject("snapshots");
                if (snapshots != null) {
                    for (java.util.Iterator<String> keys = snapshots.keys(); keys.hasNext();) {
                        String key = keys.next();
                        if (key.startsWith("scene_saved_")) saveLegacy(label + " · " + key.substring(12), snapshots.optString(key));
                    }
                }
            }
        } catch (JSONException ignored) {
            // A damaged recovery point does not affect live tasks or named snapshots.
        }
        SharedPreferences.Editor edit = prefs.edit().putString(KEY, encode());
        if (live != null) edit.putString(Scene.KEY, live.json());
        if (!edit.commit()) throw new IllegalStateException("Unable to migrate tasks");
    }

    private static JSONObject recoveryState(JSONObject point) {
        try { return new JSONObject(point.optString("state", "{}")); }
        catch (JSONException error) { return null; }
    }

    private void saveLegacy(String source, String json) {
        Scene snapshot = Scene.parse(json);
        if (snapshot == null) return;
        for (Scene.Page page : snapshot.pages) {
            for (int index = 0; index < page.slots.length; index++) {
                JSONArray entries = page.slots[index].options.optJSONArray("tasks");
                if (entries == null || entries.length() == 0) continue;
                String name = source + " · " + sourceName(page, index);
                boolean known = false;
                for (LegacyList existing : legacy.values()) {
                    if (existing.name.equals(name) && existing.tasks.toString().equals(entries.toString())) known = true;
                }
                if (!known) {
                    String id = newId();
                    legacy.put(id, new LegacyList(id, name, entries));
                }
            }
        }
    }

    private static String sourceName(Scene.Page page, int index) {
        return page.name + " · 待办 " + (index + 1);
    }

    private void importTasks(String list, JSONArray source) {
        for (int index = 0; index < source.length(); index++) {
            JSONObject old = source.optJSONObject(index);
            if (old == null || old.optString("text").trim().isEmpty()) continue;
            TodoTask task = new TodoTask();
            task.id = newId();
            task.list = list;
            task.title = old.optString("text").trim();
            task.created = System.currentTimeMillis();
            if (old.optBoolean("done")) task.completed = task.created;
            tasks.put(task.id, task);
        }
    }

    private String encode() {
        JSONObject root = new JSONObject();
        JSONArray listData = new JSONArray(), taskData = new JSONArray();
        JSONArray seriesData = new JSONArray(), legacyData = new JSONArray();
        lists.forEach((id, name) -> {
            JSONObject entry = new JSONObject();
            Scene.put(entry, "id", id);
            Scene.put(entry, "name", name);
            listData.put(entry);
        });
        for (TodoTask task : tasks.values()) taskData.put(task.json());
        for (Series entry : series.values()) seriesData.put(entry.json());
        for (LegacyList list : legacy.values()) {
            JSONObject entry = new JSONObject();
            Scene.put(entry, "id", list.id);
            Scene.put(entry, "name", list.name);
            Scene.put(entry, "tasks", list.tasks);
            legacyData.put(entry);
        }
        Scene.put(root, "lists", listData);
        Scene.put(root, "tasks", taskData);
        Scene.put(root, "series", seriesData);
        Scene.put(root, "legacy", legacyData);
        return root.toString();
    }

    private void persist() {
        displayCache.clear();
        updatePurgeTime();
        prefs.edit().putString(KEY, encode()).apply();
    }

    private static String newId() { return UUID.randomUUID().toString(); }
}
