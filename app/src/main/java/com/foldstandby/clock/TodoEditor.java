package com.foldstandby.clock;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Quick capture starts with only a title and explicit date choices. */
final class TodoEditor {
    private static final DateTimeFormatter REMINDER_LABEL = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final String TITLE_INPUT = "todo:editor-title";
    private static final String NOTE_INPUT = "todo:editor-note";
    private final TodoActivity owner;
    private final TodoTask draft;
    private final boolean future;
    private final String original;
    private final TodoTask.Repeat originalRepeat;
    private TodoTask.Repeat repeat;
    private boolean expanded;
    private EditText title;
    private ScrollView scroll, detailsScroll;
    private LinearLayout details;
    private TextView dateLabel, error;
    private int position, detailsPosition, selectionStart, selectionEnd;
    private Object focusedInput;
    private boolean keyboardVisible;

    TodoEditor(TodoActivity owner, TodoTask draft, boolean future, TodoTask.Repeat repeat) {
        this(owner, draft, future, repeat, draft.json().toString(), repeat, !draft.id.isEmpty());
    }

    private TodoEditor(TodoActivity owner, TodoTask draft, boolean future, TodoTask.Repeat repeat,
                       String original, TodoTask.Repeat originalRepeat, boolean expanded) {
        this.owner = owner;
        this.draft = draft;
        this.future = future;
        this.repeat = repeat;
        this.original = original;
        this.originalRepeat = originalRepeat;
        this.expanded = expanded;
    }

    Bundle state() {
        Bundle state = new Bundle();
        state.putString("draft", draft.json().toString());
        state.putString("original", original);
        state.putString("originalRepeat", originalRepeat.name());
        state.putString("repeat", repeat.name());
        state.putBoolean("future", future);
        state.putBoolean("expanded", expanded);
        rememberViewport();
        state.putInt("scroll", position);
        state.putInt("detailsScroll", detailsPosition);
        return state;
    }

    static TodoEditor restore(TodoActivity owner, Bundle state) {
        if (state == null) return null;
        try {
            TodoEditor result = new TodoEditor(owner, TodoTask.read(new JSONObject(state.getString("draft"))),
                    state.getBoolean("future"), TodoTask.Repeat.read(state.getString("repeat")),
                    state.getString("original"), TodoTask.Repeat.read(state.getString("originalRepeat")), state.getBoolean("expanded"));
            result.position = state.getInt("scroll");
            result.detailsPosition = state.getInt("detailsScroll");
            return result;
        } catch (JSONException error) {
            return null;
        }
    }

    void rememberViewport() {
        if (scroll == null || !scroll.isAttachedToWindow()) return;
        position = scroll.getScrollY();
        if (detailsScroll != null) detailsPosition = detailsScroll.getScrollY();
        View focus = owner.getCurrentFocus();
        focusedInput = null;
        keyboardVisible = false;
        if (focus instanceof EditText) {
            EditText input = (EditText) focus;
            focusedInput = input.getTag();
            selectionStart = input.getSelectionStart();
            selectionEnd = input.getSelectionEnd();
            WindowInsets insets = input.getRootWindowInsets();
            keyboardVisible = insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
    }

    void render(LinearLayout primary, LinearLayout secondary) {
        String heading = draft.id.isEmpty() ? "新增待办" : "编辑待办";
        boolean split = secondary != null;
        owner.titleBar(primary, heading, this::closeOrConfirm, split ? null : "保存", this::save);
        scroll = paneScroll(primary, "todo:editor-basic");
        LinearLayout fields = paneFields(scroll);
        renderBasic(fields);
        if (split) {
            owner.titleBar(secondary, "详细设置", null, "保存", this::save);
            detailsScroll = paneScroll(secondary, "todo:editor-details");
            details = paneFields(detailsScroll);
        } else {
            detailsScroll = null;
            if (focusedInput != null && !TITLE_INPUT.equals(focusedInput)) expanded = true;
            addDetailsToggle(fields);
        }
        renderDetails(null);
        ScrollView current = scroll, options = detailsScroll;
        int start = position, detailsStart = detailsPosition;
        Object focus = focusedInput;
        int cursorStart = selectionStart, cursorEnd = selectionEnd;
        boolean showKeyboard = keyboardVisible;
        current.post(() -> {
            if (current != scroll || !current.isAttachedToWindow()) return;
            current.scrollTo(0, start);
            if (options != null) options.scrollTo(0, detailsStart);
            if (focus == null) return;
            EditText input = primary.findViewWithTag(focus);
            if (input == null && secondary != null) input = secondary.findViewWithTag(focus);
            if (input != null && input.requestFocus()) {
                if (cursorStart >= 0 && cursorEnd >= 0) {
                    input.setSelection(Math.min(cursorStart, input.length()), Math.min(cursorEnd, input.length()));
                }
                if (showKeyboard) owner.showKeyboard(input);
            }
        });
    }

    private ScrollView paneScroll(LinearLayout pane, String tag) {
        ScrollView scroller = new ScrollView(owner);
        scroller.setTag(tag);
        scroller.setFillViewport(true);
        pane.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        return scroller;
    }

    private LinearLayout paneFields(ScrollView scroller) {
        LinearLayout fields = UiKit.column(owner);
        fields.setPadding(owner.dp(12), owner.dp(8), owner.dp(12), owner.dp(16));
        scroller.addView(fields);
        return fields;
    }

    private void renderBasic(LinearLayout fields) {
        title = owner.input("待办标题", draft.title, true);
        title.setTag(TITLE_INPUT);
        UiKit.inputAction(title, EditorInfo.IME_ACTION_DONE);
        TodoActivity.watch(title, value -> {
            draft.title = value;
            title.setActivated(false);
            if (error != null) error.setVisibility(View.GONE);
        });
        title.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save();
            return true;
        });
        fields.addView(title);
        error = UiKit.text(owner, "", 13, UiKit.DANGER_TEXT);
        error.setVisibility(View.GONE);
        fields.addView(error);
        dateLabel = UiKit.text(owner, "", 14, UiKit.MUTED);
        dateLabel.setPadding(owner.dp(2), owner.dp(10), owner.dp(2), 0);
        fields.addView(dateLabel);
        updateDate();
        owner.controls(fields, "今天", () -> chooseDate(TodoStore.today()),
                "明天", () -> chooseDate(TodoStore.today().plusDays(1)), "选日期", () -> owner.pickDate(
                        draft.date == null ? TodoStore.today() : draft.date, this::chooseDate));
        TextView clearDate = owner.button("清除日期", () -> chooseDate(null));
        fields.addView(clearDate);
        clearDate.setVisibility(draft.date == null ? View.GONE : View.VISIBLE);
        clearDate.setTag("todo:clearDate");
    }

    private void addDetailsToggle(LinearLayout fields) {
        TextView more = owner.button(expanded ? "收起详情  ▴" : "清单、提醒与更多  ▾", () -> {
            expanded = !expanded;
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            TextView toggle = fields.findViewWithTag("todo:detailsToggle");
            toggle.setText(expanded ? "收起详情  ▴" : "清单、提醒与更多  ▾");
            if (expanded) owner.hideKeyboard();
        });
        more.setTag("todo:detailsToggle");
        fields.addView(more);
        details = UiKit.column(owner);
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        fields.addView(details);
    }

    private void renderDetails(TodoTask.Step focus) {
        details.removeAllViews();
        details.addView(owner.button("清单 · " + owner.store().listName(draft.list) + " ▾", this::list));
        details.addView(owner.button("优先级 · " + TodoActivity.priorityLabel(draft.priority) + " ▾", this::priority));
        details.addView(owner.button("提醒 · " + (draft.reminder == null ? "未设置" : draft.reminder.format(REMINDER_LABEL)) + " ▾", this::reminder));
        if (draft.reminder != null && (!TodoReminders.enabled(owner) || !TodoReminders.precise(owner))) {
            details.addView(owner.button(!TodoReminders.enabled(owner) ? "通知已关闭 · 设置" : "提醒时间由系统安排 · 设置", owner::reminderSettings));
        }
        if (draft.series.isEmpty() || future) {
            details.addView(owner.button("重复 · " + repeat.label + " ▾", this::repeat));
        }

        owner.caption(details, "备注");
        EditText note = owner.input("添加备注", draft.note, false);
        note.setTag(NOTE_INPUT);
        UiKit.inputAction(note, EditorInfo.IME_ACTION_NONE);
        note.setGravity(Gravity.TOP);
        note.setMinLines(2);
        TodoActivity.watch(note, value -> draft.note = value);
        details.addView(note);
        owner.caption(details, "子任务");
        EditText newStep = null;
        for (TodoTask.Step step : draft.steps) {
            LinearLayout row = UiKit.row(owner);
            TextView check = owner.button(step.done ? "☑" : "☐", () -> {
                step.done = !step.done;
                TextView box = row.findViewWithTag("check");
                box.setText(step.done ? "☑" : "☐");
                box.setContentDescription(step.done ? "标为未完成" : "标为完成");
            });
            check.setTag("check");
            check.setContentDescription(step.done ? "标为未完成" : "标为完成");
            row.addView(check, new LinearLayout.LayoutParams(owner.dp(48), -2));
            EditText field = owner.input("子任务标题", step.title, true);
            field.setTag(step);
            UiKit.inputAction(field, EditorInfo.IME_ACTION_DONE);
            TodoActivity.watch(field, value -> step.title = value);
            row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = owner.button("×", () -> {
                draft.steps.remove(step);
                details.removeView(row);
            });
            remove.setContentDescription("移除子任务");
            row.addView(remove, new LinearLayout.LayoutParams(owner.dp(48), -2));
            details.addView(row);
            if (step == focus) newStep = field;
        }
        details.addView(owner.button("＋ 添加子任务", () -> {
            TodoTask.Step step = new TodoTask.Step("", false);
            draft.steps.add(step);
            renderDetails(step);
        }));
        if (newStep != null) owner.focus(newStep);
    }

    void focusTitle() { owner.focus(title); }

    private void chooseDate(LocalDate date) {
        draft.date = date;
        if (date == null && (draft.series.isEmpty() || future)) repeat = TodoTask.Repeat.NONE;
        updateDate();
        View clear = scroll.findViewWithTag("todo:clearDate");
        if (clear != null) clear.setVisibility(date == null ? View.GONE : View.VISIBLE);
        if (detailsScroll != null || expanded) renderDetails(null);
    }

    private void updateDate() {
        dateLabel.setText("日期 · " + (draft.date == null ? "无日期" : TodoActivity.dateLabel(draft.date)));
    }

    private void list() {
        Map<String, Runnable> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : owner.store().lists().entrySet()) {
            values.put(TodoActivity.unique(values, entry.getValue()), () -> {
                draft.list = entry.getKey();
                renderDetails(null);
            });
        }
        owner.menu("保存到清单", values);
    }

    private void priority() {
        Map<String, Runnable> values = new LinkedHashMap<>();
        for (int index = 0; index <= 3; index++) {
            int value = index;
            values.put(TodoActivity.priorityLabel(index), () -> {
                draft.priority = value;
                renderDetails(null);
            });
        }
        owner.menu("优先级", values);
    }

    private void reminder() {
        Map<String, Runnable> values = new LinkedHashMap<>();
        values.put("选择时间", () -> pickTime(draft.reminder != null ? draft.reminder.toLocalDate()
                : draft.date == null ? TodoStore.today() : draft.date));
        values.put("选择提醒日期和时间", () -> owner.pickDate(draft.reminder != null ? draft.reminder.toLocalDate()
                : draft.date == null ? TodoStore.today() : draft.date, this::pickTime));
        if (draft.reminder != null) values.put("关闭提醒", () -> {
            draft.reminder = null;
            renderDetails(null);
        });
        owner.menu("提醒", values);
    }

    private void pickTime(LocalDate date) {
        LocalTime time = draft.reminder == null ? LocalTime.now().plusMinutes(15) : draft.reminder.toLocalTime();
        new TimePickerDialog(owner, R.style.FoldDialogTheme, (view, hour, minute) -> {
            draft.reminder = date.atTime(hour, minute);
            renderDetails(null);
        }, time.getHour(), time.getMinute(), true).show();
    }

    private void repeat() {
        if (draft.date == null) {
            owner.pickDate(TodoStore.today(), date -> {
                chooseDate(date);
                repeat();
            });
            return;
        }
        Map<String, Runnable> values = new LinkedHashMap<>();
        for (TodoTask.Repeat value : TodoTask.Repeat.values()) values.put(value.label, () -> {
            repeat = value;
            renderDetails(null);
        });
        owner.menu("重复", values);
    }

    private void save() {
        if (draft.title.trim().isEmpty()) {
            title.setActivated(true);
            feedback("请输入待办标题");
            focusTitle();
            return;
        }
        if (draft.reminder != null && !draft.reminder.isAfter(LocalDateTime.now())) {
            TodoTask previous = draft.id.isEmpty() ? null : owner.store().get(draft.id);
            if (previous == null || !draft.reminder.equals(previous.reminder)) {
                feedback("请选择将来的提醒时间");
                return;
            }
        }
        draft.steps.removeIf(step -> step.title.trim().isEmpty());
        for (TodoTask.Step step : draft.steps) step.title = step.title.trim();
        owner.saveEditor(draft, repeat, future);
    }

    private void feedback(String value) {
        error.setText(value);
        error.setVisibility(View.VISIBLE);
        scroll.smoothScrollTo(0, 0);
    }

    void closeOrConfirm() {
        if (original.equals(draft.json().toString()) && repeat == originalRepeat) owner.closeEditor();
        else owner.confirm("放弃这次编辑？", "尚未保存的修改会丢失。", "放弃", owner::closeEditor);
    }
}
