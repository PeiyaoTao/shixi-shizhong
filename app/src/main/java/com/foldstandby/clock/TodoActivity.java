package com.foldstandby.clock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Task management and quick capture, available directly from the standby clock. */
public final class TodoActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION = 40;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final long NOTICE_TIME = 8_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final HashSet<String> expandedSeries = new HashSet<>();
    private final Map<String, Integer> scrollPositions = new LinkedHashMap<>();
    private SharedPreferences prefs;
    private TodoStore store;
    private TodoStore.View page = TodoStore.View.TODAY;
    private String list = TodoStore.ALL, taskId, search = "";
    private boolean undatedExpanded, searching, selecting, wide;
    private LinearLayout content, body, notice, navigation;
    private ScrollView scroll, railScroll;
    private int railPosition;
    private String scrollKey;
    private FrameLayout frame;
    private Rect fold;
    private FoldLayoutObserver folds;
    private TodoEditor editor;
    private String noticeText, noticeLabel;
    private Runnable noticeAction;
    private final Runnable clearNotice = () -> {
        noticeText = noticeLabel = null;
        noticeAction = null;
        renderNotice();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener privacyChanged = (p, key) -> {
        if ("private".equals(key)) updateLockAccess();
    };
    private final BroadcastReceiver dates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (editor == null) render();
        }
    };

    static Intent intent(Context context, String list, TodoStore.View view, String task, boolean add, boolean standby) {
        return new Intent(context, TodoActivity.class).putExtra("list", list).putExtra("view", view.name())
                .putExtra("task", task).putExtra("add", add).putExtra("standby", standby);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("clock", Context.MODE_PRIVATE);
        store = TodoStore.open(this);
        UiKit.customFontScale = prefs.getFloat("uiFontScale", 1f);
        updateLockAccess();
        prefs.registerOnSharedPreferenceChangeListener(privacyChanged);
        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        frame = new FrameLayout(this);
        frame.setBackgroundColor(UiKit.BG);
        navigation = UiKit.column(this);
        navigation.setVisibility(View.GONE);
        frame.addView(navigation);
        content = UiKit.column(this);
        frame.addView(content);
        frame.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateViewport());
        setContentView(frame);
        folds = new FoldLayoutObserver(this, value -> {
            fold = value;
            updateViewport();
        });

        list = getIntent().getStringExtra("list");
        if (list == null) list = TodoStore.ALL;
        if (getIntent().getStringExtra("view") != null) page = TodoStore.View.valueOf(getIntent().getStringExtra("view"));
        taskId = getIntent().getStringExtra("task");
        if (state != null) {
            list = state.getString("list", list);
            page = TodoStore.View.valueOf(state.getString("page", page.name()));
            taskId = state.getString("task");
            undatedExpanded = state.getBoolean("undated");
            searching = state.getBoolean("searching");
            search = state.getString("search", "");
            ArrayList<String> ids = state.getStringArrayList("selection");
            if (ids != null) selected.addAll(ids);
            selecting = state.getBoolean("selecting");
            if (state.containsKey("editor")) editor = TodoEditor.restore(this, state.getBundle("editor"));
        } else if (getIntent().getBooleanExtra("add", false)) {
            editNew();
            return;
        }
        render();
    }

    private void updateLockAccess() {
        setShowWhenLocked(getIntent().getBooleanExtra("standby", false) && prefs.getBoolean("private", false));
    }

    @Override protected void onStart() {
        super.onStart();
        folds.start();
        IntentFilter filter = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(dates, filter);
    }

    @Override protected void onResume() {
        super.onResume();
        TodoReminders.schedule(this);
        if (editor == null) render();
    }

    @Override protected void onStop() {
        folds.stop();
        unregisterReceiver(dates);
        super.onStop();
    }

    @Override protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(privacyChanged);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("list", list);
        state.putString("page", page.name());
        state.putString("task", taskId);
        state.putBoolean("undated", undatedExpanded);
        state.putBoolean("searching", searching);
        state.putString("search", search);
        state.putBoolean("selecting", selecting);
        state.putStringArrayList("selection", new ArrayList<>(selected));
        if (editor != null) state.putBundle("editor", editor.state());
    }

    private void updateViewport() {
        boolean wasWide = wide;
        if (editor != null) editor.rememberViewport();
        positionPanes();
        if (wasWide != wide) {
            EditText field = content.findViewWithTag("todo:search");
            boolean searchFocused = field != null && field.isFocused();
            render();
            if (searchFocused) focus(content.findViewWithTag("todo:search"));
        }
    }

    private void positionPanes() {
        int width = frame.getWidth(), height = frame.getHeight();
        if (width == 0 || height == 0) return;
        TodoViewport.Area obstruction = null;
        if (fold != null) {
            int[] origin = new int[2];
            frame.getLocationInWindow(origin);
            obstruction = new TodoViewport.Area(fold.left - origin[0], fold.top - origin[1], fold.width(), fold.height());
        }
        Configuration configuration = getResources().getConfiguration();
        float font = Math.max(1f, Math.min(1.35f, configuration.fontScale * UiKit.customFontScale));
        TodoViewport layout = TodoViewport.fit(width, height, obstruction, getResources().getDisplayMetrics().density,
                font, configuration.orientation == Configuration.ORIENTATION_LANDSCAPE, editor != null);
        wide = layout.navigation != null;
        navigation.setVisibility(wide ? View.VISIBLE : View.GONE);
        if (wide) place(navigation, layout.navigation);
        place(content, layout.content);
    }

    private void place(View view, TodoViewport.Area area) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params.width != area.width || params.height != area.height || params.leftMargin != area.left || params.topMargin != area.top) {
            params.width = area.width;
            params.height = area.height;
            params.leftMargin = area.left;
            params.topMargin = area.top;
            view.setLayoutParams(params);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        frame.post(this::updateViewport);
    }

    void render() {
        rememberScroll();
        positionPanes();
        navigation.removeAllViews();
        content.removeAllViews();
        body = null;
        scroll = null;
        notice = null;
        if (editor != null) {
            navigation.setBackground(null);
            navigation.setTag(null);
            navigation.setPadding(0, 0, 0, 0);
            railScroll = null;
            editor.render(wide ? navigation : content, wide ? content : null);
            return;
        }
        if (!TodoStore.ALL.equals(list) && !store.lists().containsKey(list)) list = TodoStore.ALL;
        if (wide) renderNavigation();
        TodoTask task = taskId == null ? null : store.get(taskId);
        if (task == null) {
            taskId = null;
            toolbar();
            makeBody(page.name() + ":" + list + ":" + searching);
            renderList();
        } else {
            titleBar("待办详情", this::back, task.deleted == 0 ? "编辑" : null, () -> edit(task));
            makeBody("detail:" + task.id);
            details(task);
        }
        notice = UiKit.row(this);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(dp(12), 0, dp(12), 0);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        renderNotice();
    }

    private void rememberScroll() {
        if (scroll != null && scrollKey != null) scrollPositions.put(scrollKey, scroll.getScrollY());
        if (railScroll != null && wide) railPosition = railScroll.getScrollY();
    }

    private void makeBody(String key) {
        scrollKey = key;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = UiKit.column(this);
        body.setPadding(dp(12), dp(8), dp(12), dp(16));
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = scrollPositions.getOrDefault(key, 0);
        ScrollView target = scroll;
        target.post(() -> target.scrollTo(0, position));
    }

    LinearLayout titleBar(String title, Runnable back, String action, Runnable callback) {
        return titleBar(content, title, back, action, callback);
    }

    LinearLayout titleBar(LinearLayout pane, String title, Runnable back, String action, Runnable callback) {
        LinearLayout row = UiKit.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), 0);
        if (back != null) {
            TextView close = button("‹", back);
            close.setContentDescription("返回");
            row.addView(close, new LinearLayout.LayoutParams(dp(48), -2));
        }
        TextView label = UiKit.text(this, title, 21, UiKit.TEXT);
        label.setTypeface(null, Typeface.BOLD);
        label.setAccessibilityHeading(true);
        label.setPadding(dp(10), 0, dp(6), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) row.addView(primary(action, callback));
        pane.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void toolbar() {
        if (selecting) {
            LinearLayout bar = titleBar("已选 " + selected.size() + " 项", this::clearSelection,
                    selected.isEmpty() ? null : "操作", this::bulk);
            addHeaderAction(bar, "全选", "全选当前列表", this::selectAll, !selected.isEmpty());
            return;
        }
        boolean add = !searching && page != TodoStore.View.TRASH;
        LinearLayout bar = titleBar(searching ? "搜索待办" : page.title, wide ? null : this::back,
                add ? "＋ 新增" : null, this::editNew);
        addHeaderAction(bar, searching ? "关闭" : "搜索", searching ? "关闭搜索" : "搜索待办",
                () -> toggleSearch(!searching), add);
        if (!wide && !searching) renderQuickNavigation();
        if (!wide) addListControl(content);
        if (searching) {
            EditText field = input("搜索标题、备注、子任务", search, true);
            UiKit.inputAction(field, EditorInfo.IME_ACTION_SEARCH);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.setMargins(dp(12), dp(6), dp(12), 0);
            content.addView(field, p);
            field.setOnEditorActionListener((v, id, event) -> {
                if (id != EditorInfo.IME_ACTION_SEARCH) return false;
                hideKeyboard();
                return true;
            });
            watch(field, value -> {
                search = value;
                if (body != null) {
                    scroll.scrollTo(0, 0);
                    renderList();
                }
            });
            field.setTag("todo:search");
        }
    }

    private void addHeaderAction(LinearLayout bar, String label, String description, Runnable click, boolean beforePrimary) {
        TextView action = button(label, click);
        action.setContentDescription(description);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        if (beforePrimary) size.rightMargin = dp(6);
        bar.addView(action, bar.getChildCount() - (beforePrimary ? 1 : 0), size);
    }

    private void renderQuickNavigation() {
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setFillViewport(true);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = UiKit.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), 0);
        for (TodoStore.View view : new TodoStore.View[] {
                TodoStore.View.TODAY, TodoStore.View.PLANNED, TodoStore.View.UNDATED }) {
            addQuickButton(row, navButton(view.title, page == view, () -> showPage(view, list)));
        }
        TextView more = navButton("更多视图 ▾", page != TodoStore.View.TODAY && page != TodoStore.View.PLANNED
                && page != TodoStore.View.UNDATED, this::choosePage);
        addQuickButton(row, more);
        strip.addView(row);
        content.addView(strip, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addQuickButton(LinearLayout row, TextView button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2, 1);
        if (row.getChildCount() > 0) size.leftMargin = dp(5);
        row.addView(button, size);
    }

    private void addListControl(LinearLayout target) {
        TextView choose = button(store.listName(list) + " ▾", this::chooseList);
        choose.setContentDescription("选择清单，当前" + store.listName(list));
        choose.setEllipsize(TextUtils.TruncateAt.END);
        choose.setMaxLines(1);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(target == content ? -2 : -1, -2);
        size.setMargins(dp(10), dp(5), dp(10), 0);
        target.addView(choose, size);
    }

    private void renderNavigation() {
        navigation.setTag("todo:navigation");
        navigation.setBackground(UiKit.surface(this, UiKit.PANEL, 16, UiKit.PANEL_BORDER, 1));
        navigation.setPadding(0, dp(6), 0, dp(6));
        LinearLayout heading = UiKit.row(this);
        heading.setPadding(dp(8), 0, dp(8), 0);
        TextView back = button("‹", this::finish);
        back.setContentDescription("退出待办管理");
        heading.addView(back, new LinearLayout.LayoutParams(dp(48), -2));
        TextView title = UiKit.text(this, "待办", 21, UiKit.TEXT);
        title.setPadding(dp(8), 0, 0, 0);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        navigation.addView(heading);
        addListControl(navigation);
        railScroll = new ScrollView(this);
        railScroll.setFillViewport(false);
        LinearLayout links = UiKit.column(this);
        links.setPadding(dp(8), dp(6), dp(8), 0);
        for (TodoStore.View view : new TodoStore.View[] {
                TodoStore.View.TODAY, TodoStore.View.PLANNED, TodoStore.View.UNDATED, TodoStore.View.OVERDUE,
                TodoStore.View.IMPORTANT, TodoStore.View.ALL_TASKS, TodoStore.View.COMPLETED,
                TodoStore.View.ARCHIVED, TodoStore.View.TRASH }) {
            if (view == TodoStore.View.COMPLETED) caption(links, "历史");
            TextView destination = navButton(view.title, page == view && !searching, () -> showPage(view, list));
            destination.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            links.addView(destination, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!store.legacyLists().isEmpty()) links.addView(button("旧待办迁移记录", this::legacy));
        links.addView(button("提醒设置", this::reminderSettings));
        railScroll.addView(links);
        navigation.addView(railScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView rail = railScroll;
        int position = railPosition;
        rail.post(() -> rail.scrollTo(0, position));
    }

    private TextView navButton(String label, boolean active, Runnable click) {
        TextView button = button(label, click);
        button.setTextSize(UiKit.sp(13));
        button.setSelected(active);
        button.setTextColor(active ? UiKit.ACCENT : UiKit.TEXT);
        setFeedback(button, UiKit.surface(this, active ? UiKit.ACTIVE_BG : UiKit.PANEL, 9), 9);
        return button;
    }

    private void toggleSearch(boolean enabled) {
        searching = enabled;
        search = "";
        if (!enabled) hideKeyboard();
        render();
        if (enabled) focus(content.findViewWithTag("todo:search"));
    }

    private void chooseList() {
        Map<String, Runnable> items = new LinkedHashMap<>();
        items.put("所有清单", () -> switchList(TodoStore.ALL));
        for (Map.Entry<String, String> entry : store.lists().entrySet()) {
            items.put(unique(items, entry.getValue()), () -> switchList(entry.getKey()));
        }
        items.put("＋ 新建清单", () -> listInput("新建清单", "", name -> switchList(store.createList(name))));
        if (!list.isEmpty() && !list.equals(TodoStore.INBOX)) {
            items.put("重命名当前清单", () -> listInput("重命名清单", store.listName(list), name -> {
                store.renameList(list, name);
                render();
            }));
            items.put("移除当前清单", () -> confirm("移除清单？", "该清单中的待办及历史将移到收件箱。", "移除", () -> {
                store.removeList(list);
                switchList(TodoStore.ALL);
            }));
        }
        menu("清单", items);
    }

    private void choosePage() {
        Map<String, Runnable> items = new LinkedHashMap<>();
        for (TodoStore.View view : TodoStore.View.values()) items.put(view.title, () -> showPage(view, list));
        if (!store.legacyLists().isEmpty()) items.put("旧待办迁移记录", this::legacy);
        items.put("提醒设置", this::reminderSettings);
        menu("待办视图", items);
    }

    void reminderSettings() {
        Map<String, Runnable> options = new LinkedHashMap<>();
        options.put(TodoReminders.enabled(this) ? "通知已开启 · 管理" : "开启待办通知", () -> {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            openReminderSetting(settings);
        });
        if (Build.VERSION.SDK_INT >= 31) {
            options.put(TodoReminders.precise(this) ? "准时提醒已开启 · 管理" : "开启准时提醒", () -> {
                Intent settings = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()));
                openReminderSetting(settings);
            });
        }
        menu("提醒设置", options);
    }

    private void openReminderSetting(Intent settings) {
        try { startActivity(settings); }
        catch (ActivityNotFoundException missing) { notifyUser("当前设备未提供此提醒设置", null, null); }
    }

    private void showPage(TodoStore.View view, String listId) {
        rememberScroll();
        page = view;
        list = listId;
        taskId = null;
        selecting = false;
        selected.clear();
        searching = false;
        search = "";
        hideKeyboard();
        render();
    }

    private void switchList(String id) {
        EditText field = content.findViewWithTag("todo:search");
        boolean searchFocused = field != null && field.isFocused();
        list = id;
        taskId = null;
        clearSelection();
        if (searchFocused) focus(content.findViewWithTag("todo:search"));
    }

    private void renderList() {
        body.removeAllViews();
        if (searching) {
            renderSearch();
            return;
        }
        if (page == TodoStore.View.TODAY) {
            TodoStore.Display display = store.display(list);
            if (display.overdue > 0) {
                TextView overdue = button(display.overdue + " 项已过期  ›", () -> showPage(TodoStore.View.OVERDUE, list));
                overdue.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                overdue.setTextColor(UiKit.WARN_TEXT);
                setFeedback(overdue, UiKit.surface(this, UiKit.WARN_BG, 10), 10);
                addSectionButton(overdue);
            }
            if (display.today.isEmpty()) empty("今天暂无待办");
            else {
                caption(body, "已完成 " + display.completed + " / " + display.today.size());
                int pending = display.today.size() - display.completed;
                for (int i = 0; i < display.today.size(); i++) {
                    if (i > 0 && i == pending) caption(body, "已完成");
                    appendTask(display.today.get(i), false);
                }
            }
            if (!display.undated.isEmpty()) {
                TextView toggle = button("未安排 · " + display.undated.size() + " 项  " + (undatedExpanded ? "▴" : "▾"), () -> {
                    undatedExpanded = !undatedExpanded;
                    renderList();
                });
                toggle.setContentDescription("未安排，" + display.undated.size() + " 项，" + (undatedExpanded ? "已展开" : "已折叠"));
                toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                addSectionButton(toggle);
                if (undatedExpanded) appendTasks(display.undated, false);
            }
            return;
        }
        List<TodoTask> tasks = store.query(page, list, "");
        if (tasks.isEmpty()) empty(page == TodoStore.View.TRASH ? "回收站为空" : "暂无" + page.title + "事项");
        else if (page == TodoStore.View.OVERDUE) appendOverdue(tasks);
        else appendTasks(tasks, false);
        if (page == TodoStore.View.TRASH && !tasks.isEmpty()) {
            body.addView(button("清空当前回收站", () -> erase(ids(tasks), true)));
        }
    }

    private void addSectionButton(TextView button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(6);
        body.addView(button, size);
    }

    private void renderSearch() {
        if (search.trim().isEmpty()) {
            empty("输入关键词，搜索当前清单范围内的待办和历史");
            return;
        }
        int count = 0;
        for (TodoStore.View view : new TodoStore.View[] { TodoStore.View.ALL_TASKS, TodoStore.View.COMPLETED,
                TodoStore.View.ARCHIVED, TodoStore.View.TRASH }) {
            List<TodoTask> matches = store.query(view, list, search);
            if (matches.isEmpty()) continue;
            caption(body, view.title + " · " + matches.size());
            appendTasks(matches, true);
            count += matches.size();
        }
        if (count == 0) empty("没有找到匹配的待办");
    }

    private void appendOverdue(List<TodoTask> tasks) {
        Map<String, List<TodoTask>> groups = new LinkedHashMap<>();
        for (TodoTask task : tasks) if (!task.family.isEmpty()) {
            groups.computeIfAbsent(task.family, ignored -> new ArrayList<>()).add(task);
        }
        HashSet<String> seen = new HashSet<>();
        for (TodoTask task : tasks) {
            List<TodoTask> group = groups.get(task.family);
            if (group == null || group.size() == 1) {
                appendTask(task, false);
                continue;
            }
            if (!seen.add(task.family)) continue;
            boolean expanded = expandedSeries.contains(task.family) || selecting;
            TextView heading = button(task.title + " · " + group.size() + " 项已过期 " + (expanded ? "▴" : "▾"), () -> {
                if (!expandedSeries.add(task.family)) expandedSeries.remove(task.family);
                renderList();
            });
            heading.setOnLongClickListener(v -> {
                selecting = true;
                selected.addAll(ids(group));
                render();
                return true;
            });
            body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            if (expanded) appendTasks(group, false);
        }
    }

    private void appendTasks(List<TodoTask> tasks, boolean showStatus) {
        for (TodoTask task : tasks) appendTask(task, showStatus);
    }

    private void appendTask(TodoTask task, boolean showStatus) {
        LinearLayout row = UiKit.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.surface(this, selected.contains(task.id) ? UiKit.ACTIVE_BG : UiKit.CARD, 14));
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.bottomMargin = dp(6);
        body.addView(row, margin);

        boolean done = task.completed != 0 && task.deleted == 0 && task.archived == 0;
        if (selecting || task.active() || done) {
            boolean chosen = selected.contains(task.id);
            TextView check = UiKit.text(this, selecting ? (chosen ? "☑" : "☐") : (done ? "✓" : "○"),
                    23, done && !selecting ? UiKit.MUTED : UiKit.ACCENT);
            check.setGravity(Gravity.CENTER);
            check.setMinHeight(dp(54));
            check.setContentDescription((selecting ? (chosen ? "取消选择 " : "选择 ")
                    : (done ? "取消完成 " : "完成 ")) + task.title);
            setFeedback(check, null, 14);
            check.setOnClickListener(v -> {
                if (selecting) select(task.id);
                else act(Collections.singletonList(task.id), done ? TodoStore.Action.REOPEN : TodoStore.Action.COMPLETE);
            });
            row.addView(check, new LinearLayout.LayoutParams(dp(50), -2));
        }
        LinearLayout labels = UiKit.column(this);
        labels.setPadding(dp(task.active() || done || selecting ? 0 : 14), dp(8), 0, dp(8));
        labels.setMinimumHeight(dp(58));
        labels.addView(taskTitle(task.title, 16, done));
        String text = taskMetadata(task, showStatus);
        if (!text.isEmpty()) {
            int color = TodoStore.View.OVERDUE.includes(task, TodoStore.today()) ? UiKit.WARN_TEXT : UiKit.MUTED;
            labels.addView(UiKit.text(this, text, 12, color));
        }
        labels.setOnClickListener(v -> {
            if (selecting) select(task.id);
            else openTask(task.id);
        });
        labels.setOnLongClickListener(v -> { select(task.id); return true; });
        setFeedback(labels, null, 10);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (!selecting) {
            TextView more = UiKit.text(this, "⋯", 22, UiKit.MUTED);
            more.setGravity(Gravity.CENTER);
            more.setMinHeight(dp(52));
            more.setContentDescription(task.title + " 的更多操作");
            setFeedback(more, null, 14);
            more.setOnClickListener(v -> actions(task));
            row.addView(more, new LinearLayout.LayoutParams(dp(48), -2));
        }
    }

    private String taskMetadata(TodoTask task, boolean showStatus) {
        ArrayList<String> parts = new ArrayList<>();
        if (TodoStore.View.OVERDUE.includes(task, TodoStore.today())) parts.add("已过期");
        if (task.date != null && (page != TodoStore.View.TODAY || showStatus)) parts.add(dateLabel(task.date));
        if (task.priority > 0) parts.add(priorityLabel(task.priority));
        if (!task.series.isEmpty()) parts.add("重复");
        if (task.reminder != null && task.active()) parts.add("提醒 " + task.reminder.format(DATE_TIME));
        if (task.deleted > 0) parts.add("剩余 " + remainingDays(task.deleted) + " 天");
        if (showStatus && task.deleted == 0) {
            if (task.archived > 0) parts.add("已归档");
            else if (task.completed > 0) parts.add("已完成");
        }
        if (list.equals(TodoStore.ALL) && !task.list.equals(TodoStore.INBOX)) parts.add(store.listName(task.list));
        return String.join(" · ", parts);
    }

    private void details(TodoTask task) {
        boolean done = task.completed != 0 && task.archived == 0 && task.deleted == 0;
        TextView title = taskTitle(task.title, 24, done);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextIsSelectable(true);
        body.addView(title);
        String metadata = taskMetadata(task, true);
        if (!metadata.isEmpty()) caption(body, metadata);
        List<String> ids = Collections.singletonList(task.id);
        if (task.active()) controls(body, "完成", () -> act(ids, TodoStore.Action.COMPLETE), "改期", () -> reschedule(ids));
        if (done) controls(body, "取消完成", () -> act(ids, TodoStore.Action.REOPEN), "归档", () -> act(ids, TodoStore.Action.ARCHIVE),
                "删除", () -> act(ids, TodoStore.Action.DELETE));
        else if (task.deleted > 0) controls(body, "恢复", () -> act(ids, TodoStore.Action.RESTORE), "永久删除", () -> erase(ids, false));
        else if (task.archived > 0) controls(body, "取消归档", () -> act(ids, TodoStore.Action.UNARCHIVE), "删除", () -> act(ids, TodoStore.Action.DELETE));
        else controls(body, "归档", () -> act(ids, TodoStore.Action.ARCHIVE), "删除", () -> act(ids, TodoStore.Action.DELETE));
        if (!task.note.isEmpty()) {
            caption(body, "备注");
            TextView note = UiKit.text(this, task.note, 16, UiKit.TEXT);
            note.setTextIsSelectable(true);
            body.addView(note);
        }
        if (!task.steps.isEmpty()) {
            caption(body, "子任务");
            for (TodoTask.Step step : task.steps) {
                TextView action = button((step.done ? "☑  " : "☐  ") + step.title, () -> {
                    if (!task.active()) return;
                    step.done = !step.done;
                    store.update(task, store.repeatOf(task), false);
                    render();
                });
                action.setEnabled(task.active());
                body.addView(action);
            }
        }
    }

    private void actions(TodoTask task) {
        List<String> ids = Collections.singletonList(task.id);
        Map<String, Runnable> items = new LinkedHashMap<>();
        if (task.deleted > 0) {
            items.put("恢复", () -> act(ids, TodoStore.Action.RESTORE));
            items.put("永久删除", () -> erase(ids, false));
        } else {
            items.put("编辑", () -> edit(task));
            if (task.active()) {
                items.put("改期", () -> reschedule(ids));
                items.put("完成", () -> act(ids, TodoStore.Action.COMPLETE));
                items.put("归档", () -> act(ids, TodoStore.Action.ARCHIVE));
            } else if (task.archived > 0) items.put("取消归档", () -> act(ids, TodoStore.Action.UNARCHIVE));
            else {
                items.put("取消完成", () -> act(ids, TodoStore.Action.REOPEN));
                items.put("归档", () -> act(ids, TodoStore.Action.ARCHIVE));
            }
            items.put("删除", () -> act(ids, TodoStore.Action.DELETE));
        }
        menu(task.title, items);
    }

    private void select(String id) {
        selecting = true;
        if (!selected.add(id)) selected.remove(id);
        render();
    }

    private void selectAll() {
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        if (searching) {
            if (search.trim().isEmpty()) return;
            for (TodoStore.View view : new TodoStore.View[] { TodoStore.View.ALL_TASKS, TodoStore.View.COMPLETED,
                    TodoStore.View.ARCHIVED, TodoStore.View.TRASH }) visible.addAll(ids(store.query(view, list, search)));
        } else {
            visible.addAll(ids(store.query(page, list, "")));
            if (page == TodoStore.View.TODAY && undatedExpanded) visible.addAll(ids(store.query(TodoStore.View.UNDATED, list, "")));
        }
        if (selected.containsAll(visible)) selected.removeAll(visible);
        else selected.addAll(visible);
        render();
    }

    private void clearSelection() {
        selecting = false;
        selected.clear();
        render();
    }

    private void bulk() {
        if (selected.isEmpty()) return;
        List<String> ids = new ArrayList<>(selected);
        Map<String, Runnable> items = new LinkedHashMap<>();
        for (String id : ids) {
            TodoTask task = store.get(id);
            if (task == null) continue;
            if (task.active()) {
                items.put("完成待办", () -> act(ids, TodoStore.Action.COMPLETE));
                items.put("改期", () -> reschedule(ids));
                items.put("归档", () -> act(ids, TodoStore.Action.ARCHIVE));
            }
            if (task.deleted > 0) {
                items.put("从回收站恢复", () -> act(ids, TodoStore.Action.RESTORE));
                items.put("永久删除", () -> erase(ids, false));
            } else if (task.archived > 0) items.put("取消归档", () -> act(ids, TodoStore.Action.UNARCHIVE));
            else if (task.completed > 0) {
                items.put("取消完成", () -> act(ids, TodoStore.Action.REOPEN));
                items.put("归档", () -> act(ids, TodoStore.Action.ARCHIVE));
            }
            if (task.deleted == 0) items.put("删除", () -> act(ids, TodoStore.Action.DELETE));
        }
        menu("已选 " + ids.size() + " 项", items);
    }

    private void act(List<String> ids, TodoStore.Action action) {
        TodoStore.Undo undo = store.change(ids, action);
        if (undo.count() == 0) return;
        if (action != TodoStore.Action.COMPLETE && action != TodoStore.Action.REOPEN) taskId = null;
        TodoReminders.cancel(this, ids);
        changed(undo, undo.count() == 1 ? action.message : action.message + " " + undo.count() + " 项");
    }

    private void reschedule(List<String> ids) {
        Map<String, Runnable> options = new LinkedHashMap<>();
        options.put("今天", () -> reschedule(ids, TodoStore.today()));
        options.put("明天", () -> reschedule(ids, TodoStore.today().plusDays(1)));
        options.put("选择日期", () -> pickDate(TodoStore.today(), date -> reschedule(ids, date)));
        options.put("设为无日期", () -> reschedule(ids, null));
        menu("改期", options);
    }

    private void reschedule(List<String> ids, LocalDate date) {
        TodoStore.Undo undo = store.reschedule(ids, date);
        if (undo.count() > 0) changed(undo, date == null ? "已移到未安排" : "日期已改为" + dateLabel(date));
    }

    private void changed(TodoStore.Undo undo, String message) {
        TodoReminders.schedule(this);
        notifyUser(message, "撤销", () -> {
            int count = store.undo(undo);
            TodoReminders.schedule(this);
            render();
            notifyUser(count > 0 ? "已撤销" : "事项已更新，无法撤销", null, null);
        });
        clearSelection();
    }

    private void erase(List<String> ids, boolean all) {
        List<String> deleted = new ArrayList<>();
        for (String id : ids) {
            TodoTask task = store.get(id);
            if (task != null && task.deleted > 0) deleted.add(id);
        }
        if (deleted.isEmpty()) return;
        confirm(all ? "清空回收站？" : "永久删除？", "将永久删除 " + deleted.size() + " 项待办。", "永久删除", () -> {
            store.erase(deleted);
            TodoReminders.schedule(this);
            taskId = null;
            clearSelection();
            notifyUser("已永久删除 " + deleted.size() + " 项", null, null);
        });
    }

    private void legacy() {
        Map<String, Runnable> items = new LinkedHashMap<>();
        for (TodoStore.LegacyList entry : store.legacyLists()) {
            items.put(unique(items, entry.name + " · " + entry.tasks.length() + " 项"), () -> previewLegacy(entry));
        }
        menu("旧待办迁移记录", items);
    }

    private void previewLegacy(TodoStore.LegacyList entry) {
        ScrollView wrapper = new ScrollView(this);
        LinearLayout entries = UiKit.column(this);
        entries.setPadding(dp(20), 0, dp(20), dp(8));
        wrapper.addView(entries);
        for (int i = 0; i < entry.tasks.length(); i++) {
            JSONObject item = entry.tasks.optJSONObject(i);
            if (item == null) continue;
            entries.addView(UiKit.text(this, (item.optBoolean("done") ? "☑  " : "☐  ") + item.optString("text"), 15, UiKit.TEXT));
        }
        AppDialogs.builder(this).setTitle(entry.name).setView(wrapper).setNegativeButton("关闭", null)
                .setPositiveButton("导入为清单副本", (d, which) -> {
                    showPage(TodoStore.View.ALL_TASKS, store.importLegacy(entry.id));
                    notifyUser("已导入清单副本", null, null);
                }).show();
    }

    private void openTask(String id) {
        hideKeyboard();
        taskId = id;
        render();
    }

    private void editNew() {
        hideKeyboard();
        TodoTask draft = new TodoTask();
        draft.list = list.equals(TodoStore.ALL) ? TodoStore.INBOX : list;
        editor = new TodoEditor(this, draft, false, TodoTask.Repeat.NONE);
        render();
        editor.focusTitle();
    }

    private void edit(TodoTask task) {
        if (store.canEditSeries(task)) {
            Map<String, Runnable> items = new LinkedHashMap<>();
            items.put("仅本次", () -> edit(task, false));
            items.put("本次及以后", () -> edit(task, true));
            menu("修改重复事项", items);
        } else edit(task, false);
    }

    private void edit(TodoTask task, boolean future) {
        taskId = task.id;
        editor = new TodoEditor(this, task, future, store.repeatOf(task));
        render();
    }

    void saveEditor(TodoTask draft, TodoTask.Repeat repeat, boolean future) {
        boolean adding = draft.id.isEmpty();
        TodoTask task;
        if (adding) task = store.add(draft, repeat);
        else {
            if (!store.update(draft, repeat, future)) {
                closeEditor();
                notifyUser("这条待办已无法编辑", null, null);
                return;
            }
            task = store.get(draft.id);
        }
        closeEditor();
        TodoReminders.cancel(this, Collections.singletonList(task.id));
        TodoReminders.schedule(this);
        String message = adding ? "已添加到" + destination(task.date).title : "已保存待办";
        notifyUser(message, adding ? "查看" : null, adding ? () -> {
            showPage(destination(task.date), list.equals(TodoStore.ALL) ? list : task.list);
            openTask(task.id);
        } : null);
        if (task.active() && task.reminder != null && !TodoReminders.enabled(this) && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATION_PERMISSION) TodoReminders.schedule(this);
    }

    private static TodoStore.View destination(LocalDate date) {
        if (date == null) return TodoStore.View.UNDATED;
        LocalDate today = TodoStore.today();
        if (date.isAfter(today)) return TodoStore.View.PLANNED;
        if (date.isBefore(today)) return TodoStore.View.OVERDUE;
        return TodoStore.View.TODAY;
    }

    TodoStore store() { return store; }

    void closeEditor() {
        hideKeyboard();
        editor = null;
        render();
    }

    @Override public void onBackPressed() { back(); }

    private void back() {
        if (editor != null) {
            editor.closeOrConfirm();
        } else if (taskId != null) {
            taskId = null;
            render();
        } else if (selecting) clearSelection();
        else if (searching) toggleSearch(false);
        else if (page != TodoStore.View.TODAY) showPage(TodoStore.View.TODAY, list);
        else finish();
    }

    private void notifyUser(String message, String label, Runnable action) {
        handler.removeCallbacks(clearNotice);
        noticeText = message;
        noticeLabel = label;
        noticeAction = action;
        renderNotice();
        handler.postDelayed(clearNotice, NOTICE_TIME);
    }

    private void renderNotice() {
        if (notice == null) return;
        notice.removeAllViews();
        notice.setVisibility(noticeText != null ? View.VISIBLE : View.GONE);
        if (noticeText == null) return;
        notice.setBackgroundColor(UiKit.ACTIVE_BG);
        TextView text = UiKit.text(this, noticeText, 14, UiKit.TEXT);
        text.setMinimumHeight(dp(50));
        notice.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (noticeAction != null) notice.addView(button(noticeLabel, () -> {
            Runnable action = noticeAction;
            clearNotice.run();
            if (action != null) action.run();
        }));
    }

    void menu(String title, Map<String, Runnable> items) {
        if (items.isEmpty()) return;
        String[] labels = items.keySet().toArray(new String[0]);
        AppDialogs.builder(this).setTitle(title).setItems(labels, (d, which) -> items.get(labels[which]).run())
                .setNegativeButton("取消", null).show();
    }

    void confirm(String title, String message, String action, Runnable callback) {
        AppDialogs.builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton(action, (d, which) -> callback.run()).show();
    }

    void pickDate(LocalDate initial, Consumer<LocalDate> picked) {
        DatePickerDialog widget = new DatePickerDialog(this, R.style.FoldDialogTheme,
                (view, year, month, day) -> picked.accept(LocalDate.of(year, month + 1, day)),
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        widget.show();
    }

    private void listInput(String title, String existing, Consumer<String> save) {
        EditText field = input("清单名称", existing, true);
        LinearLayout box = UiKit.column(this);
        box.setPadding(dp(18), dp(4), dp(18), dp(8));
        box.addView(field);
        AlertDialog dialog = AppDialogs.builder(this).setTitle(title).setView(box)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            if (name.isEmpty()) field.setError("请输入清单名称");
            else {
                dialog.dismiss();
                save.accept(name);
            }
        });
        focus(field);
    }

    void controls(LinearLayout target, String first, Runnable firstAction, String second, Runnable secondAction) {
        controls(target, new String[] { first, second }, new Runnable[] { firstAction, secondAction });
    }

    void controls(LinearLayout target, String first, Runnable firstAction, String second, Runnable secondAction,
                  String third, Runnable thirdAction) {
        controls(target, new String[] { first, second, third }, new Runnable[] { firstAction, secondAction, thirdAction });
    }

    private void controls(LinearLayout target, String[] labels, Runnable[] actions) {
        LinearLayout row = UiKit.row(this);
        row.setPadding(dp(10), dp(5), dp(10), dp(3));
        for (int i = 0; i < labels.length; i++) {
            LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) width.leftMargin = dp(5);
            row.addView(button(labels[i], actions[i]), width);
        }
        target.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    void caption(LinearLayout target, String label) {
        TextView text = UiKit.text(this, label, 13, UiKit.MUTED);
        text.setPadding(dp(2), dp(7), dp(2), dp(5));
        target.addView(text);
    }

    private void empty(String message) {
        TextView label = UiKit.text(this, message, 16, UiKit.MUTED);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), dp(28), dp(10), dp(28));
        body.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    TextView button(String label, Runnable action) {
        TextView button = UiKit.compactButton(this, label, UiKit.ROW, UiKit.ROW_BORDER, UiKit.TEXT, action);
        button.setMinHeight(dp(48));
        button.setMaxLines(2);
        setFeedback(button, button.getBackground(), 8);
        return button;
    }

    private TextView primary(String label, Runnable action) {
        TextView button = UiKit.compactButton(this, label, UiKit.ACCENT, UiKit.ACTIVE_BORDER, UiKit.BG, action);
        button.setMinHeight(dp(48));
        setFeedback(button, button.getBackground(), 8);
        return button;
    }

    private void setFeedback(View view, Drawable background, int radius) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x36b8e5c7), background,
                UiKit.surface(this, Color.WHITE, radius)));
    }

    private TextView taskTitle(String value, int size, boolean done) {
        TextView title = UiKit.text(this, value, size, done ? UiKit.MUTED : UiKit.TEXT);
        if (done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        return title;
    }

    EditText input(String hint, String value, boolean single) {
        EditText field = new EditText(this);
        field.setSingleLine(single);
        UiKit.styleInput(field, false);
        field.setHint(hint);
        field.setText(value);
        return field;
    }

    static void watch(EditText field, Consumer<String> change) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { change.accept(s.toString()); }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    void focus(EditText field) {
        if (field == null) return;
        field.requestFocus();
        field.setSelection(field.length());
        showKeyboard(field);
    }

    void showKeyboard(EditText field) {
        field.postDelayed(() -> {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null && field.isFocused()) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 160);
    }

    void hideKeyboard() {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(content.getWindowToken(), 0);
    }

    int dp(float value) { return UiKit.dp(this, value); }

    static String dateLabel(LocalDate date) {
        LocalDate today = TodoStore.today();
        if (date.equals(today)) return "今天";
        if (date.equals(today.plusDays(1))) return "明天";
        return date.format(DATE);
    }

    static String priorityLabel(int priority) {
        return priority >= 3 ? "高优先级" : priority == 2 ? "中优先级" : priority == 1 ? "低优先级" : "普通";
    }

    static String unique(Map<String, ?> values, String base) {
        String label = base;
        for (int i = 2; values.containsKey(label); i++) label = base + " (" + i + ")";
        return label;
    }

    private static List<String> ids(Collection<TodoTask> tasks) {
        ArrayList<String> ids = new ArrayList<>();
        for (TodoTask task : tasks) ids.add(task.id);
        return ids;
    }

    private static long remainingDays(long deleted) {
        long left = TodoStore.TRASH_RETENTION - (System.currentTimeMillis() - deleted);
        return Math.max(1, (left + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1));
    }
}
