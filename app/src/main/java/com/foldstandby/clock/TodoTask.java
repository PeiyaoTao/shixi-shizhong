package com.foldstandby.clock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

/** A single actionable occurrence. Dates and notification times are independent. */
final class TodoTask {
    enum Repeat {
        NONE("不重复"), DAILY("每天"), WEEKDAYS("工作日"), WEEKLY("每周"),
        MONTHLY("每月"), YEARLY("每年");

        final String label;

        Repeat(String label) { this.label = label; }

        LocalDate next(LocalDate anchor, LocalDate previous) {
            switch (this) {
                case DAILY: return previous.plusDays(1);
                case WEEKDAYS:
                    LocalDate next = previous.plusDays(1);
                    int weekday = next.getDayOfWeek().getValue();
                    return weekday <= 5 ? next : next.plusDays(8 - weekday);
                case WEEKLY: return previous.plusWeeks(1);
                case MONTHLY:
                    LocalDate month = previous.withDayOfMonth(1).plusMonths(1);
                    return month.withDayOfMonth(Math.min(anchor.getDayOfMonth(), month.lengthOfMonth()));
                case YEARLY: return anchor.withYear(previous.getYear() + 1);
                default: throw new IllegalStateException("No recurrence");
            }
        }

        static Repeat read(String name) {
            for (Repeat value : values()) if (value.name().equals(name)) return value;
            return NONE;
        }
    }

    static final class Step {
        String title;
        boolean done;

        Step(String title, boolean done) {
            this.title = title;
            this.done = done;
        }
    }

    String id = "", list = TodoStore.INBOX, title = "", note = "", series = "", family = "";
    LocalDate date, occurrence;
    LocalDateTime reminder, notified;
    long created, completed, archived, deleted;
    int priority;
    boolean customized;
    final ArrayList<Step> steps = new ArrayList<>();

    boolean active() { return completed == 0 && archived == 0 && deleted == 0; }

    TodoTask copy() { return read(json()); }

    JSONObject json() {
        JSONObject json = new JSONObject();
        Scene.put(json, "id", id);
        Scene.put(json, "list", list);
        Scene.put(json, "title", title);
        if (!note.isEmpty()) Scene.put(json, "note", note);
        if (date != null) Scene.put(json, "date", date.toString());
        if (reminder != null) Scene.put(json, "reminder", reminder.toString());
        if (notified != null) Scene.put(json, "notified", notified.toString());
        if (!series.isEmpty()) Scene.put(json, "series", series);
        if (!family.isEmpty()) Scene.put(json, "family", family);
        if (occurrence != null) Scene.put(json, "occurrence", occurrence.toString());
        if (customized) Scene.put(json, "customized", true);
        Scene.put(json, "priority", priority);
        Scene.put(json, "created", created);
        if (completed != 0) Scene.put(json, "completed", completed);
        if (archived != 0) Scene.put(json, "archived", archived);
        if (deleted != 0) Scene.put(json, "deleted", deleted);
        if (!steps.isEmpty()) {
            JSONArray values = new JSONArray();
            for (Step step : steps) {
                JSONObject item = new JSONObject();
                Scene.put(item, "title", step.title);
                Scene.put(item, "done", step.done);
                values.put(item);
            }
            Scene.put(json, "steps", values);
        }
        return json;
    }

    static TodoTask read(JSONObject json) {
        TodoTask task = new TodoTask();
        task.id = json.optString("id");
        task.list = json.optString("list", TodoStore.INBOX);
        task.title = json.optString("title");
        task.note = json.optString("note");
        task.series = json.optString("series");
        task.family = json.optString("family", task.series);
        task.date = date(json.optString("date"));
        task.occurrence = date(json.optString("occurrence"));
        task.reminder = dateTime(json.optString("reminder"));
        task.notified = dateTime(json.optString("notified"));
        task.priority = json.optInt("priority");
        task.created = json.optLong("created");
        task.completed = json.optLong("completed");
        task.archived = json.optLong("archived");
        task.deleted = json.optLong("deleted");
        task.customized = json.optBoolean("customized");
        JSONArray values = json.optJSONArray("steps");
        for (int i = 0; values != null && i < values.length(); i++) {
            JSONObject step = values.optJSONObject(i);
            if (step != null) task.steps.add(new Step(step.optString("title"), step.optBoolean("done")));
        }
        return task;
    }

    static LocalDate date(String value) {
        if (value.isEmpty()) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException error) { return null; }
    }

    private static LocalDateTime dateTime(String value) {
        if (value.isEmpty()) return null;
        try { return LocalDateTime.parse(value); }
        catch (DateTimeParseException error) { return null; }
    }
}
