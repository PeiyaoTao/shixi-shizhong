package com.foldstandby.clock;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/**
 * Unified Studio Workspace organized around a persistent 3D-perspective Smartwatch Page Carousel,
 * a collapsible navigation drawer, an object-focused Workbench (Content / Appearance / Palette), and
 * adaptive previews, explicit component/style selectors, local history and persistent recovery.
 */
public class MainActivity extends Activity implements UiKit.EditTransactions {
    private SharedPreferences prefs;
    private TodoStore todoStore;
    private boolean todoManagerOpened;
    Scene draft;
    private String baseline, pageId;
    private int tab = 1, slot, category, paletteScope = 2, inspectorGroup;
    private enum PreviewPicker {
        STYLE("选择组件样式"), COMPONENT("更换组件"), ADD_PAGE("添加页面"), PAGE_LAYOUT("页面布局");

        final String title;

        PreviewPicker(String title) { this.title = title; }
    }

    private boolean testMode, previewNight, pageOverview, navExpanded;
    private PreviewPicker previewPicker;
    private String libraryType;
    private int widthDp, heightDp, windowWidthDp, navigationWidthDp;
    private AdaptiveLayout policy;
    private float previewWidthDp, previewHeightDp, previewAspect;
    private ClockLayout.Area previewFold;
    private LinearLayout workspace, first, second, workbenchHost;
    private TextView brandButton;
    private WatchCarouselScrollView carouselScroll;
    private final ArrayList<ClockFace> previews = new ArrayList<>();
    private final ArrayList<ClockFace> carouselPreviews = new ArrayList<>();
    private final Map<String, ScrollView> scrollViews = new HashMap<>();
    private final Map<String, Integer> scrolls = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PaletteEditor colorEditor;
    private final PaletteEditor.State colorState = new PaletteEditor.State();
    private String colorContext = "";
    private final EditHistory history = new EditHistory();
    private String historyState;
    private boolean imeVisible, horizontalFold;
    private int studioPanel, contentInsetLeft, contentInsetTop, safeLeft, safeTop, safeWidth, safeHeight;
    private Rect localFold;
    private int previewPaneWidth, editorPaneWidth;
    private Dialog studioDialog;
    private TextView undoButton, savedStatus, pageTitle, pageHint;
    private FrameLayout noticeHost;
    private String noticeText;
    private long noticeUntil;
    private Rect foldBounds;
    private FoldLayoutObserver folds;
    private WeatherLocation.Request locationRequest;
    private AlertDialog locationDialog, searchDialog;
    private InputDialog inputDialog;
    private static final int WEATHER_LOCATION_PERMISSION = 14;
    private static final int WEATHER_LOCATION_SETTINGS_PERMISSION = 17;
    private static final int CALENDAR_PERMISSION = 12;
    private static final int SPECTRUM_PERMISSION = 16;
    private static final int NOTIFICATION_PERMISSION = 13;
    private static final int IMPORT_MUSIC_LYRICS = 21;
    private String pendingLyricsKey, pendingLyricsTitle;
    private String pendingStandbyPermission, standbyError;
    private boolean returningFromComponentSettings, retryWeatherOnReturn;
    private static final int DISPLAY_SETTINGS = 0, PAGE_SETTINGS = 1, DATA_SETTINGS = 2;
    private static final int STANDBY_SETTINGS = 3, SCENE_SETTINGS = 4, ABOUT_SETTINGS = 5;
    private static final int[] SETTINGS_ORDER = { STANDBY_SETTINGS, DISPLAY_SETTINGS, PAGE_SETTINGS, DATA_SETTINGS, SCENE_SETTINGS, ABOUT_SETTINGS };
    private static final String[] SETTINGS_TITLES = {
        "显示与夜间", "翻页与动效", "数据与权限", "自动待机", "场景与恢复", "关于"
    };
    private static final String[][] PAGE_PRESET_COMPONENTS = {
        {"clock"}, {"clock", "weather"}, {"clock", "date"}, {"clock", "weather", "date", "battery"}
    };
    private static final String WEATHER_SETTINGS = "settings:weather";
    private static final String FOCUS_SETTINGS = "settings:focus";
    private static final String PERMISSION_SETTINGS = "settings:permissions";
    private boolean settingsDetail, settingsLandscape;
    private Rect settingsHinge;
    private String pendingSettingsAnchor;
    private final Rect visiblePreviewRect = new Rect();
    private boolean pulseRunning;
    private final Runnable refreshSceneData = () -> { if (!testMode) DeskData.refresh(this, false); };
    private final Runnable alarmChanged = () -> {
        for (ClockFace f : previews) f.invalidate();
        updateAlarmStatus();
    };
    private void updateAlarmStatus() {
        View status = getWindow().getDecorView().findViewWithTag("alarm:status");
        if (status instanceof TextView) ((TextView) status).setText(AlarmData.status(System.currentTimeMillis()));
    }
    private final Runnable clockPulse = new Runnable() {
        public void run() {
            if (!testMode) {
                AlarmData.refresh(MainActivity.this, false);
                DeskData.refreshWorldDaylight(MainActivity.this);
            }
            updateAlarmStatus();
            boolean showingStatus = tab == 3;
            if (showingStatus) updateSettingsStatus();
            long delay = showingStatus ? 1000L : 15000L;
            for (ClockFace f : previews) {
                if (!f.isAttachedToWindow() || !f.isShown()) continue;
                if (f.getWidth() == 0) {
                    delay = 1000L;
                } else if (f.getGlobalVisibleRect(visiblePreviewRect)) {
                    f.invalidate();
                    delay = Math.min(delay, f.nextDelayMs());
                }
            }
            delay = Math.max(1000L, Math.min(15000L, delay));
            handler.postDelayed(this, delay);
        }
    };

    private int dp(float n) { return UiKit.dp(this, n); }

    private ScrollView scroll(LinearLayout host, String key, boolean fill) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(fill);
        s.setClipToPadding(true);
        s.setPadding(dp(10), dp(8), dp(10), dp(14));
        s.setVerticalScrollBarEnabled(true);
        host.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
        scrollViews.put(key, s);
        Integer y = scrolls.get(key);
        if (y != null) s.post(() -> s.scrollTo(0, y));
        return s;
    }

    private LinearLayout scrollColumn(LinearLayout host, String key) {
        ScrollView s = scroll(host, key, false);
        LinearLayout c = UiKit.column(this);
        s.addView(c);
        return c;
    }

    private void rememberScroll() {
        for (Map.Entry<String, ScrollView> e : scrollViews.entrySet()) {
            scrolls.put(e.getKey(), e.getValue().getScrollY());
        }
    }

    private Scene.Page page() { return draft.pages.get(draft.index(pageId)); }

    private Scene.Component component() {
        slot = Math.min(slot, page().count() - 1);
        return page().slots[slot];
    }

    private void changed() {
        for (Scene.Page p : draft.pages) p.refreshName();
        String next = captureEditorState();
        history.record(historyState, next);
        historyState = next;
        persistDraft();
        if (undoButton != null) { undoButton.setEnabled(history.canUndo()); undoButton.setAlpha(history.canUndo() ? 1f : .35f); }
        if (savedStatus != null) savedStatus.setText("已自动保存");
        if (brandButton != null) {
            brandButton.setText(getString(R.string.brand_name) + " / " + draft.name + " ▾");
        }
        for (ClockFace f : previews) f.invalidate();
        if (pulseRunning) {
            handler.removeCallbacks(clockPulse);
            handler.postDelayed(clockPulse, 1000);
        }
    }

    /** Only committed, valid form values enter the model; incomplete input stays in its field. */
    private void persistDraft() {
        if (todoStore != null) todoStore.bind(draft);
        String json = draft.json();
        if (json.equals(baseline)) return;
        prefs.edit().putString(Scene.KEY, json).apply();
        baseline = json;
        handler.removeCallbacks(refreshSceneData);
        handler.postDelayed(refreshSceneData, 350);
    }

    private void saveNamedScene(String name) {
        if(prefs.contains("scene_saved_"+name)){
            AppDialogs.builder(this).setTitle("已有同名快照").setMessage("快照包含页面、配色、待办内容与完成状态。请选择覆盖或另存副本。")
                .setPositiveButton("覆盖快照",(d,n)->{saveRecoveryPoint("覆盖「"+name+"」前");completeSave(name);})
                .setNeutralButton("保存副本", (d, n) -> completeSave(snapshotCopyName(name)))
                .setNegativeButton("取消",null).show();
        }else completeSave(name);
    }

    private String snapshotCopyName(String name) {
        String copy = name + " 副本";
        for (int i = 2; prefs.contains("scene_saved_" + copy); i++) copy = name + " 副本 " + i;
        return copy;
    }
    private void completeSave(String name){
        Scene snapshot = draft.copy();
        snapshot.name = name;
        saveSnapshot(name, snapshot.json(), "已保存快照");
    }

    private void saveSnapshot(String name, String snapshot, String notice) {
        history.finish();
        prefs.edit().putString("scene_saved_" + name, snapshot).apply();
        changed();
        show();
        showNotice(notice);
    }

    private void launchFullscreenPreview() {
        startActivity(
            new Intent(this, ClockActivity.class)
                .putExtra("scenePreview", draft.json())
                .putExtra("previewPage", pageId)
                .putExtra("previewNight", previewNight)
                .putExtra("uiCheck", testMode)
        );
    }

    private void launchStandby() {
        history.finish();
        startActivity(new Intent(this, ClockActivity.class).putExtra("uiCheck", testMode));
    }

    public void beginEdit() { history.begin(); }
    public void endEdit() { history.end(); }
    private String captureEditorState() {
        if (todoStore != null) todoStore.bind(draft);
        JSONObject state = new JSONObject(), snapshots = new JSONObject();
        Scene.put(state, "scene", draft.json()); Scene.put(state, "page", pageId); Scene.put(state, "slot", slot);
        for (String key : new TreeSet<>(prefs.getAll().keySet())) if (key.startsWith("scene_saved_")) Scene.put(snapshots, key, prefs.getString(key, ""));
        Scene.put(state, "snapshots", snapshots); return state.toString();
    }
    private void selectionBaseline() { history.finish(); historyState = captureEditorState(); }
    private void restoreEditorState(String value) {
        restoreEditorState(value, true);
    }

    private boolean restoreEditorState(String value, boolean includeLibrary) {
        if (value == null) return false;
        try {
            JSONObject state = new JSONObject(value);
            Scene scene = Scene.parse(state.getString("scene"));
            if (scene == null) {
                message("恢复点不可用，当前配置已保留。");
                return false;
            }
            if (scene.ownNightColors == null) scene.ownNightColors = prefs.getBoolean("nightOwnColors", false);
            draft = scene; pageId = state.optString("page", scene.home); pageId = page().id;
            slot = Math.max(0, Math.min(state.optInt("slot"), page().count()-1));
            if (includeLibrary) {
                JSONObject snapshots = state.optJSONObject("snapshots");
                if (snapshots != null) {
                    SharedPreferences.Editor edit = prefs.edit();
                    for (String name : savedSceneNames()) edit.remove("scene_saved_" + name);
                    for (Iterator<String> keys = snapshots.keys(); keys.hasNext();) {
                        String key = keys.next();
                        if (key.startsWith("scene_saved_")) edit.putString(key, snapshots.optString(key));
                    }
                    edit.apply();
                }
            }
            historyState = captureEditorState(); libraryType = component().type;
            colorState.hex = null; colorContext = ""; previewPicker = null;
            persistDraft(); show();
            return true;
        } catch (JSONException e) {
            message("恢复点不可用，当前配置已保留。");
            return false;
        }
    }
    private void undoEdit() { history.finish(); restoreEditorState(history.undo()); }
    private void redoEdit() { history.finish(); restoreEditorState(history.redo()); }
    private void saveRecoveryPoint(String label) {
        history.finish();
        JSONArray points;
        try { points = new JSONArray(prefs.getString("studio_recovery", "[]")); } catch (JSONException e) { points = new JSONArray(); }
        JSONArray next = new JSONArray(); JSONObject point = new JSONObject();
        Scene.put(point,"label",label); Scene.put(point,"time",System.currentTimeMillis()); Scene.put(point,"state",captureEditorState()); next.put(point);
        for(int i=0;i<Math.min(4,points.length());i++)next.put(points.optJSONObject(i));
        prefs.edit().putString("studio_recovery",next.toString()).apply();
    }
    private void showRecoveryPoints() {
        try {
            JSONArray points = new JSONArray(prefs.getString("studio_recovery", "[]"));
            if(points.length()==0){message("暂无恢复点。载入、恢复默认或覆盖快照前会自动保留。");return;}
            String[] names=new String[points.length()];
            for(int i=0;i<names.length;i++){JSONObject p=points.getJSONObject(i);names[i]=p.optString("label")+" · "+android.text.format.DateFormat.format("MM-dd HH:mm",p.optLong("time"));}
            AppDialogs.builder(this).setTitle("选择恢复点").setItems(names, (d, n) ->
                showRecoveryActions(points.optJSONObject(n).optString("state")))
                .setNegativeButton("关闭", null).show();
        }catch(JSONException e){message("暂无可用的恢复点。");}
    }

    private void showRecoveryActions(String value) {
        try {
            JSONObject point = new JSONObject(value);
            JSONObject library = point.optJSONObject("snapshots");
            List<String> actions = new ArrayList<>();
            List<Runnable> restore = new ArrayList<>();
            actions.add("恢复当时的场景");
            restore.add(() -> confirmSceneReplacement("恢复当时的场景？", "恢复场景",
                () -> applyRecovery(value, false)));
            if (library != null) {
                if (library.length() > 0) {
                    actions.add("找回单个快照");
                    restore.add(() -> chooseRecoveredSnapshot(library));
                }
                actions.add("整体恢复…");
                restore.add(() -> AppDialogs.builder(this).setTitle("整体恢复到此恢复点？")
                    .setMessage("将同时替换当前场景和整份已保存的快照列表。此恢复点之后新增的快照会从列表移除。应用会先保留恢复点。")
                    .setPositiveButton("整体恢复", (d, n) -> applyRecovery(value, true))
                    .setNegativeButton("取消", null).show());
            }
            AppDialogs.builder(this).setTitle("选择恢复范围")
                .setItems(actions.toArray(new String[0]), (d, n) -> restore.get(n).run())
                .setNegativeButton("取消", null).show();
        } catch (JSONException e) { message("恢复点无法读取。"); }
    }

    private void applyRecovery(String value, boolean includeLibrary) {
        history.finish();
        String before = captureEditorState();
        saveRecoveryPoint("恢复前的配置");
        if (restoreEditorState(value, includeLibrary)) {
            history.record(before, historyState);
            showNotice(includeLibrary ? "已恢复场景与快照列表" : "已恢复场景");
        }
    }

    private void chooseRecoveredSnapshot(JSONObject library) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> keys = library.keys(); keys.hasNext();) {
            String key = keys.next();
            if (key.startsWith("scene_saved_")) names.add(key.substring(12));
        }
        Collections.sort(names);
        AppDialogs.builder(this).setTitle("找回哪个快照？")
            .setItems(names.toArray(new String[0]), (d, n) -> {
                String name = names.get(n);
                String value = library.optString("scene_saved_" + name);
                if (Scene.parse(value) == null) {
                    message("此快照无法读取。");
                    return;
                }
                if (prefs.contains("scene_saved_" + name)) {
                    AppDialogs.builder(this).setTitle("已有同名快照")
                        .setMessage("覆盖会替换「" + name + "」。也可以另存副本，保留两个版本。")
                        .setPositiveButton("覆盖", (dialog, which) -> {
                            saveRecoveryPoint("覆盖「" + name + "」前");
                            saveSnapshot(name, value, "已找回快照");
                        })
                        .setNeutralButton("另存副本", (dialog, which) -> {
                            Scene copy = Scene.parse(value);
                            String copyName = snapshotCopyName(name);
                            copy.name = copyName;
                            saveSnapshot(copyName, copy.json(), "已找回快照副本");
                        }).setNegativeButton("取消", null).show();
                } else saveSnapshot(name, value, "已找回快照");
            }).setNegativeButton("取消", null).show();
    }
    private void showNotice(String text) {
        noticeText=text; noticeUntil=SystemClock.uptimeMillis()+5500; renderNotice();
        handler.postDelayed(()->{if(SystemClock.uptimeMillis()>=noticeUntil&&noticeHost!=null){noticeHost.removeAllViews();noticeHost.setVisibility(View.GONE);}},5600);
    }
    private void renderNotice() {
        if(noticeHost==null)return; noticeHost.removeAllViews();
        boolean visible=noticeText!=null&&SystemClock.uptimeMillis()<noticeUntil;
        noticeHost.setVisibility(visible?View.VISIBLE:View.GONE); if(!visible)return;
        LinearLayout row=UiKit.row(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),dp(4),dp(10),dp(4));row.setBackground(UiKit.surface(this,UiKit.ACTIVE_BG,10,UiKit.ACTIVE_BORDER,1));
        TextView text=UiKit.text(this,noticeText,11,UiKit.TEXT);text.setMaxLines(2);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(UiKit.compactButton(this,"撤销",UiKit.ACTIVE_BG,0,UiKit.ACCENT,this::undoEdit));
        noticeHost.addView(row,new FrameLayout.LayoutParams(-1,-2));noticeHost.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    public void onCreate(Bundle state) {
        super.onCreate(state);
        testMode = getIntent().getBooleanExtra("uiCheck", false);
        prefs = getSharedPreferences(testMode ? "ui_checks" : "clock", 0);
        if (!testMode) todoStore = TodoStore.open(this);
        draft = Scene.load(prefs);
        baseline = draft.json();
        prefs.edit().putString(Scene.KEY,baseline).apply();
        pageId = draft.home;
        historyState = captureEditorState();
        if (state != null) {
            pendingStandbyPermission = state.getString("pendingStandbyPermission");
            pendingLyricsKey = state.getString("pendingLyricsKey");
            pendingLyricsTitle = state.getString("pendingLyricsTitle");
            returningFromComponentSettings = state.getBoolean("returningFromComponentSettings");
            retryWeatherOnReturn = state.getBoolean("retryWeatherOnReturn");
            studioPanel = state.getInt("studioPanel");
            history.restore(state.getStringArrayList("undoStates"), state.getStringArrayList("redoStates"));
            pageOverview = state.getBoolean("pageOverview");
            navExpanded = state.getBoolean("navExpanded");
            String savedPicker = state.getString("previewPicker");
            if (savedPicker != null) previewPicker = PreviewPicker.valueOf(savedPicker);
            else if (state.getBoolean("portraitStyleScreen")) {
                previewPicker = state.getBoolean("componentPicker") ? PreviewPicker.COMPONENT : PreviewPicker.STYLE;
            }
            libraryType = state.getString("libraryType");
            Scene restored = Scene.parse(state.getString("draft", ""));
            if (restored != null) draft = restored;
            baseline = state.getString("baseline", baseline);
            pageId = state.getString("page", draft.home);
            tab = state.getInt("tab", 1);
            slot = state.getInt("slot");
            category = state.getInt("category");
            settingsDetail = state.getBoolean("settingsDetail", tab == 3);
            inspectorGroup = state.getInt("inspectorGroup");
            paletteScope = state.getInt("paletteScope", 2);
            colorState.mode = state.getInt("colorMode");
            colorState.role = state.getInt("colorRole", 1);
            colorState.hex = state.getString("colorHex");
            colorContext = state.getString("colorContext", "");
            previewNight = state.getBoolean("previewNight");
            try {
                JSONObject j = new JSONObject(state.getString("scrolls", "{}"));
                Iterator<String> keys = j.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    scrolls.put(key, j.optInt(key));
                }
            } catch (Exception ignored) {}
        }
        readStandbyEntry();
        historyState = captureEditorState();
        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        show();
        if (!testMode) {
            DeskData.refresh(this, false);
            folds = new FoldLayoutObserver(this, bounds -> {
                foldBounds = bounds;
                rememberScroll();
                show();
            });
            folds.start();
        }
    }

    private void show() {
        if(getWindow()==null)return;
        rememberScroll();
        WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
        );
        float density = getResources().getDisplayMetrics().density;
        contentInsetLeft = insets.left; contentInsetTop = insets.top;
        int w = Math.round((metrics.getBounds().width() - insets.left - insets.right) / density);
        int h = Math.round((metrics.getBounds().height() - insets.top - insets.bottom) / density);
        Insets cutout = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
        Rect editorFold = foldDp(w, h);
        Rect clockFold = foldBounds == null ? null : new Rect(foldBounds);
        if (clockFold != null) clockFold.offset(-cutout.left, -cutout.top);
        setPreviewViewport(
                (metrics.getBounds().width() - cutout.left - cutout.right) / density,
                (metrics.getBounds().height() - cutout.top - cutout.bottom) / density,
                clockFold == null ? null : new ClockLayout.Area(clockFold.left / density, clockFold.top / density,
                        clockFold.right / density, clockFold.bottom / density));
        setContentView(createUi(w, h, editorFold));
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, value) -> {
            boolean visible = value.isVisible(WindowInsets.Type.ime());
            if (imeVisible != visible) { imeVisible = visible; if (activeStage != null) activeStage.requestLayout(); }
            return v.onApplyWindowInsets(value);
        });
    }

    private int leftPaneWidthDp() { return horizontalFold ? widthDp - 12 : policy.parallel ? policy.left : widthDp - 12; }

    private int paneInnerWidth(boolean left) { return Math.max(100,(left?previewPaneWidth:editorPaneWidth)-28); }

    private WatchStageContainer activeStage;
    private Rect foldDp(int w, int h) {
        if(foldBounds==null)return null;
        float density=getResources().getDisplayMetrics().density;
        Rect r=new Rect(Math.round((foldBounds.left-contentInsetLeft)/density),Math.round((foldBounds.top-contentInsetTop)/density),
            Math.round((foldBounds.right-contentInsetLeft)/density),Math.round((foldBounds.bottom-contentInsetTop)/density));
        if(r.right<0||r.bottom<0||r.left>w||r.top>h)return null;
        return r;
    }
    private Rect largestRegion(int w,int h,Rect fold) {
        if(fold==null||fold.right<0||fold.bottom<0||fold.left>=w||fold.top>=h)return new Rect(0,0,w,h);
        Rect a,b;
        if(fold.width()>fold.height()){a=new Rect(0,0,w,Math.min(h,Math.max(0,fold.top)));b=new Rect(0,Math.min(h,fold.bottom),w,h);}
        else{a=new Rect(0,0,Math.min(w,Math.max(0,fold.left)),h);b=new Rect(Math.min(w,fold.right),0,w,h);}
        return a.width()*a.height()>=b.width()*b.height()?a:b;
    }
    View createUi(int w, int h) {
        Rect fold = foldDp(w, h);
        setPreviewViewport(w, h, fold == null ? null : new ClockLayout.Area(fold.left, fold.top, fold.right, fold.bottom));
        return createUi(w, h, fold);
    }

    private void setPreviewViewport(float w, float h, ClockLayout.Area fold) {
        previewWidthDp = Math.max(1, Math.max(w, h));
        previewHeightDp = Math.max(1, Math.min(w, h));
        previewAspect = previewWidthDp / previewHeightDp;
        previewFold = ClockLayout.landscapeFold(fold, w, h);
    }

    private View createUi(int w, int h, Rect fold) {
        int originalW=w,originalH=h;
        settingsLandscape = w > h;
        UiKit.customFontScale = prefs != null ? prefs.getFloat("uiFontScale", 1.0f) : 1.0f;
        float scale=getResources().getConfiguration().fontScale*UiKit.customFontScale;
        Rect region=new Rect(0,0,w,h);
        int nav=w>h&&navExpanded?Math.min(220,Math.max(160,Math.round(w*.22f))):0;
        boolean canSplit=fold!=null&&(fold.width()>fold.height()?fold.top>=120&&h-fold.bottom>=220:
            fold.left-nav>=220&&w-fold.right>=Math.round(250*Math.max(1f,scale*.8f)));
        settingsHinge = tab == 3 && settingsLandscape && fold != null && fold.width() <= fold.height()
                && AdaptiveLayout.settingsCanSpanHinge(fold.left - nav, w - fold.right, h, scale)
                ? fold : null;
        if(fold!=null&&settingsHinge==null&&(!canSplit||tab==3||previewPicker!=null))region=largestRegion(w,h,fold);
        safeLeft=region.left;safeTop=region.top;safeWidth=w=region.width();safeHeight=h=region.height();
        windowWidthDp=w;
        navigationWidthDp=w>h&&navExpanded?Math.min(220,Math.max(150,Math.round(w*.22f))):0;
        widthDp=w-navigationWidthDp;heightDp=h;
        localFold=fold==null?null:new Rect(fold.left-safeLeft-navigationWidthDp-6,fold.top-safeTop-5,
            fold.right-safeLeft-navigationWidthDp-6,fold.bottom-safeTop-5);
        horizontalFold=canSplit&&region.left==0&&region.top==0&&fold.width()>fold.height()&&tab!=3&&previewPicker==null;
        policy=new AdaptiveLayout(widthDp,h,scale);
        boolean split=canSplit&&region.left==0&&region.top==0&&tab!=3&&previewPicker==null;
        previewPaneWidth=editorPaneWidth=widthDp-12;
        if(split&&!horizontalFold){previewPaneWidth=localFold.left;editorPaneWidth=widthDp-12-localFold.right;}
        else if(policy.parallel&&!horizontalFold){previewPaneWidth=policy.left;editorPaneWidth=widthDp-policy.left-20;}
        previews.clear();carouselPreviews.clear();scrollViews.clear();colorEditor=null;brandButton=null;carouselScroll=null;workbenchHost=null;activeStage=null;
        LinearLayout root=UiKit.column(this);root.setBackgroundColor(UiKit.BG);
        if(tab==3)renderSettingsShell(root);
        else if(previewPicker!=null)renderPreviewPicker(root, originalW > originalH, scale);
        else{
            workspace=new FoldWorkspace(this);workspace.setOrientation(LinearLayout.HORIZONTAL);workspace.setPadding(dp(6),dp(5),dp(6),dp(6));
            root.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));
            first=UiKit.column(this);first.setBackground(UiKit.surface(this,UiKit.PANEL,14,UiKit.PANEL_BORDER,1));
            if(policy.parallel||split){
                workspace.addView(first,new LinearLayout.LayoutParams(dp(leftPaneWidthDp()),-1));
                second=new EditorPane(this);second.setBackground(UiKit.surface(this,UiKit.PANEL,14,UiKit.PANEL_BORDER,1));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.leftMargin=dp(8);workspace.addView(second,lp);
                if(split)applyFoldPanes();
                buildWatchCarouselStage(first,true);workbenchHost=second;buildIntegratedWorkbench(second,true);
            }else{
                workspace.addView(first,new LinearLayout.LayoutParams(-1,-1));second=first;
                activeStage=new WatchStageContainer(this,0);activeStage.setBackground(UiKit.surface(this,0xff0d121a,12,UiKit.CARD_BORDER,1));
                first.addView(activeStage,new LinearLayout.LayoutParams(-1,-2));buildWatchCarouselStage(activeStage,false);
                workbenchHost=new EditorPane(this);first.addView(workbenchHost,new LinearLayout.LayoutParams(-1,0,1));buildIntegratedWorkbench(workbenchHost,false);
            }
        }
        FrameLayout content=new FrameLayout(this);content.addView(withNavigation(root),new FrameLayout.LayoutParams(-1,-1));
        noticeHost=new FrameLayout(this);FrameLayout.LayoutParams toastLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);toastLp.setMargins(dp(12),0,dp(12),dp(10));content.addView(noticeHost,toastLp);renderNotice();
        if(region.left==0&&region.top==0&&region.width()==originalW&&region.height()==originalH)return content;
        FrameLayout safe=new SafeRegionContainer(this,fold);safe.setBackgroundColor(UiKit.BG);safe.addView(content,new FrameLayout.LayoutParams(dp(w),dp(h)));safe.setTag("fold:safe");return safe;
    }

    final class EditorPane extends LinearLayout {
        LinearLayout fixedHeader,extras,body;LinearLayout.LayoutParams extrasParams;boolean compact;
        EditorPane(Context c){super(c);setOrientation(LinearLayout.VERTICAL);}
        protected void onMeasure(int w,int h){
            boolean small=MeasureSpec.getSize(h)<dp(260*Math.max(1f,Math.min(1.5f,getResources().getConfiguration().fontScale*UiKit.customFontScale)));
            if(extras!=null&&body!=null&&small!=compact){
                ((ViewGroup)extras.getParent()).removeView(extras);compact=small;
                if(small){extras.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));body.addView(extras,0);}else fixedHeader.addView(extras,new LinearLayout.LayoutParams(extrasParams));
            }
            super.onMeasure(w,h);
        }
    }

    final class SafeRegionContainer extends FrameLayout {
        private final Rect fold;
        SafeRegionContainer(Context c,Rect bounds){super(c);fold=bounds==null?null:new Rect(dp(bounds.left),dp(bounds.top),dp(bounds.right),dp(bounds.bottom));}
        protected void onMeasure(int w,int h){
            Rect region=largestRegion(MeasureSpec.getSize(w),MeasureSpec.getSize(h),fold);
            if(getChildCount()>0){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)getChildAt(0).getLayoutParams();lp.width=region.width();lp.height=region.height();lp.leftMargin=region.left;lp.topMargin=region.top;}
            super.onMeasure(w,h);
        }
    }
    final class FoldWorkspace extends LinearLayout {
        FoldWorkspace(Context c){super(c);}
        protected void onMeasure(int w,int h){
            if(horizontalFold&&localFold!=null&&first!=null&&second!=null){
                int available=MeasureSpec.getSize(h),top=dp(localFold.top),bottom=dp(localFold.bottom);
                if(available-bottom>=dp(200)){
                    setPadding(dp(6),dp(5),dp(6),dp(6));first.setVisibility(View.VISIBLE);first.getLayoutParams().height=top;
                    View tools=first.findViewWithTag("preview:tools");if(tools!=null)tools.setVisibility(View.VISIBLE);
                    if(carouselScroll!=null&&carouselScroll.getChildCount()>0){ViewGroup track=(ViewGroup)carouselScroll.getChildAt(0);for(int i=0;i<track.getChildCount();i++)((ViewGroup)track.getChildAt(i)).getChildAt(0).setVisibility(View.VISIBLE);}
                    LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)second.getLayoutParams();lp.topMargin=dp(Math.max(1,localFold.height()));
                }else{
                    Rect area=largestRegion(MeasureSpec.getSize(w),available,new Rect(0,top+dp(5),MeasureSpec.getSize(w),bottom+dp(5)));
                    setPadding(dp(6),Math.max(dp(5),area.top),dp(6),Math.max(dp(6),available-area.bottom));
                    int preview=Math.min(dp(110),Math.max(0,area.height()-dp(200)));
                    first.setVisibility(preview>=dp(76)?View.VISIBLE:View.GONE);first.getLayoutParams().height=preview;
                    ((LinearLayout.LayoutParams)second.getLayoutParams()).topMargin=0;
                    View tools=first.findViewWithTag("preview:tools");if(tools!=null)tools.setVisibility(View.GONE);
                    if(carouselScroll!=null&&carouselScroll.getChildCount()>0){ViewGroup track=(ViewGroup)carouselScroll.getChildAt(0);for(int i=0;i<track.getChildCount();i++)((ViewGroup)track.getChildAt(i)).getChildAt(0).setVisibility(View.GONE);}
                }
            }
            super.onMeasure(w,h);
        }
    }

    /** Landscape reserves drawer space; portrait keeps the modal overlay. */
    private View withNavigation(View content) {
        content.setTag("workspace:content");
        if (navigationWidthDp > 0) {
            LinearLayout split = UiKit.row(this);
            View drawer = buildCollapsibleNav();
            drawer.setTag("navigation:drawer");
            drawer.setAccessibilityPaneTitle("导航菜单");
            split.addView(drawer, new LinearLayout.LayoutParams(dp(navigationWidthDp), -1));
            split.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
            return split;
        }
        FrameLayout overlay = new FrameLayout(this);
        overlay.addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (!navExpanded) return overlay;
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setContentDescription("关闭导航菜单");
        scrim.setOnClickListener(v -> { navExpanded = false; show(); });
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        View drawer = buildCollapsibleNav();
        drawer.setTag("navigation:drawer");
        drawer.setAccessibilityPaneTitle("导航菜单");
        drawer.setElevation(dp(16));
        overlay.addView(drawer, new FrameLayout.LayoutParams(dp(Math.min(280, windowWidthDp - 48)), -1, Gravity.LEFT));
        return overlay;
    }

    private void refreshWorkbenchInPlace() {
        if (tab == 3 || workbenchHost == null) {
            show();
            return;
        }
        rememberScroll();
        scrollViews.clear();
        colorEditor = null;
        previews.clear();
        previews.addAll(carouselPreviews);
        workbenchHost.removeAllViews();
        buildIntegratedWorkbench(workbenchHost, policy.parallel);
    }

    private void selectPageInPlace(int pageIdx, int nextSlot, boolean switchFromPageTab, boolean smoothScroll) {
        history.finish();
        if (pageIdx < 0 || pageIdx >= draft.pages.size()) return;
        pageId = draft.pages.get(pageIdx).id;
        slot = Math.max(0, Math.min(nextSlot, page().count() - 1));
        libraryType = component().type;
        if (switchFromPageTab && tab == 2) tab = 1;
        selectionBaseline();
        if(pageTitle!=null){pageTitle.setText((draft.index(pageId)+1)+"/"+draft.pages.size()+" · "+page().name+" ▾");pageTitle.setContentDescription("选择页面 · "+page().name);}
        if(pageHint!=null)pageHint.setText(neighborHint());
        for(ClockFace f:carouselPreviews){f.editorSlot=f.pageIdForEditor().equals(pageId)?slot:-1;f.invalidate();}
        refreshWorkbenchInPlace();
        if (carouselScroll != null) {
            carouselScroll.setSelectedIndex(pageIdx, smoothScroll);
        }
    }

    private void applyFoldPanes() {
        if(localFold==null)return;
        if(horizontalFold){
            workspace.setOrientation(LinearLayout.VERTICAL);
            first.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(Math.max(0,localFold.top))));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1);lp.topMargin=dp(Math.max(1,localFold.height()));second.setLayoutParams(lp);
        }else{
            first.setLayoutParams(new LinearLayout.LayoutParams(dp(Math.max(0,localFold.left)),-1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.leftMargin=dp(Math.max(1,localFold.width()));second.setLayoutParams(lp);
        }
    }
    Rect dialogSafeBounds() { return dialogSafeBounds(null); }
    Rect dialogSafeBounds(WindowInsets override) {
        WindowMetrics metrics=getWindowManager().getCurrentWindowMetrics();
        WindowInsets value=override!=null?override:getWindow().getDecorView().getRootWindowInsets();if(value==null)value=metrics.getWindowInsets();
        Insets bars=value.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
        Rect area=new Rect(bars.left,bars.top,metrics.getBounds().width()-bars.right,metrics.getBounds().height()-bars.bottom);
        if(foldBounds!=null){Rect f=new Rect(foldBounds);f.offset(-area.left,-area.top);Rect safe=largestRegion(area.width(),area.height(),f);safe.offset(area.left,area.top);return safe;}
        return area;
    }

    /** Collapsible navigation drawer replacing permanent top and side navigation chrome. */
    private View buildCollapsibleNav() {
        LinearLayout drawer = UiKit.column(this);
        drawer.setBackground(UiKit.surface(this, UiKit.PANEL, 0, UiKit.PANEL_BORDER, 1f));
        drawer.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout top = UiKit.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = UiKit.text(this, "导航菜单", 12, UiKit.TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(UiKit.compactButton(this, "收起 ×", UiKit.ROW, UiKit.ROW_BORDER, UiKit.MUTED, () -> {
            navExpanded = false;
            show();
        }));
        drawer.addView(top);
        UiKit.gap(this, drawer, 6);

        brandButton = UiKit.compactButton(
            this,
            getString(R.string.brand_name) + " / " + draft.name + " ▾",
            UiKit.SELECTOR_BG,
            UiKit.SELECTOR_BORDER,
            UiKit.TEXT,
            () -> openSettings(SCENE_SETTINGS, true, null)
        );
        brandButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.bottomMargin = dp(6);
        drawer.addView(brandButton, bp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(true);
        LinearLayout col = UiKit.column(this);
        populateNavItems(col);
        sv.addView(col);
        drawer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return drawer;
    }

    private void populateNavItems(LinearLayout host) {
        UiKit.action(this, host, "⌂ 页面工作台", () -> {
            navExpanded = false;
            if (tab == 3) tab = 1;
            show();
        });
        UiKit.action(this, host, "开始待机", this::launchStandby).setTag("standby:start");
        if (todoStore != null) UiKit.action(this, host, "待办管理", () -> openTodoManager(TodoStore.ALL, false));
        UiKit.action(this, host, "设置", () -> openSettings(category, false, null));
    }

    private void openTodoManager(String list, boolean add) {
        todoManagerOpened = true;
        startActivity(TodoActivity.intent(this, list, TodoStore.View.TODAY, null, add, false));
    }

    /** Smartwatch-style 3D perspective page carousel acting as the sole real-time preview stage. */
    private void buildWatchCarouselStage(LinearLayout host, boolean fullHeight) {
        LinearLayout header=UiKit.row(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(6),dp(4),dp(6),0);
        header.addView(icon(navExpanded?"✕":"☰","导航菜单",()->{navExpanded=!navExpanded;show();}));
        TextView title=pageTitle=UiKit.text(this,(draft.index(pageId)+1)+"/"+draft.pages.size()+" · "+page().name+" ▾",12,UiKit.TEXT);
        title.setTypeface(null,Typeface.BOLD);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setMinHeight(dp(40));
        title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(6),0,dp(4),0);title.setContentDescription("浏览页面 · "+page().name);
        title.setOnClickListener(v->showPageChooser());header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(UiKit.compactButton(this,"布局",UiKit.PANEL,0,UiKit.TEXT,()->showLayoutPicker(false)));
        undoButton=icon("↶","撤销上一步",this::undoEdit);undoButton.setEnabled(history.canUndo());undoButton.setAlpha(history.canUndo()?1f:.35f);header.addView(undoButton);
        header.addView(icon("⋯","页面更多操作",this::showPageMenu));host.addView(header,new LinearLayout.LayoutParams(-1,-2));
        // 2. Scrollable Middle Carousel (enlarged selected page + shrunk 3D perspective neighbors, no outer boxes)
        carouselScroll = new WatchCarouselScrollView(this, previewAspect);
        carouselScroll.setVerticalScrollBarEnabled(false);
        carouselScroll.setClipToPadding(true);
        carouselScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout track = UiKit.column(this);
        track.setGravity(Gravity.CENTER_HORIZONTAL);
        track.setPadding(dp(8), dp(2), dp(8), dp(2));

        int availW = paneInnerWidth(true);
        int selectedIndex = draft.index(pageId);
        // Initial dimensions are refined from the actual viewport in carousel.onMeasure.
        int selectedH = Math.round(availW / previewAspect);

        final ArrayList<LinearLayout> itemViews = new ArrayList<>();
        for (int i = 0; i < draft.pages.size(); i++) {
            final int pageIdx = i;
            final Scene.Page p = draft.pages.get(i);
            boolean selected = p.id.equals(pageId);

            LinearLayout item = UiKit.column(this);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setTag("page:" + p.id);
            item.setContentDescription("页面预览 · " + p.name + " · 长按拖动排序");

            // Minimal floating label directly above preview (preserves exact "2  日历" hook)
            LinearLayout labelRow = UiKit.row(this);
            labelRow.setGravity(Gravity.CENTER);
            TextView label = UiKit.text(
                this,
                (i + 1) + "  " + p.name,
                11.5f,
                selected ? UiKit.ACCENT : UiKit.MUTED
            );
            label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            label.setMaxLines(1);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER);
            label.setPadding(dp(4), dp(1), dp(4), dp(2));
            labelRow.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if (p.id.equals(draft.home)) {
                TextView homeTag = UiKit.badge(this, "主页", UiKit.ACTIVE_BG, UiKit.ACTIVE_BORDER, UiKit.ACCENT);
                labelRow.addView(homeTag);
            }
            item.addView(labelRow, new LinearLayout.LayoutParams(-1, -2));

            // Use the current window's landscape proportions for the whole carousel.
            int previewH = selectedH;
            int previewW = Math.min(availW - 4, Math.round(previewH * previewAspect));
            AspectPreview frame = new AspectPreview(this);
            int borderPad = dp(2);
            frame.setPadding(borderPad, borderPad, borderPad, borderPad);
            GradientDrawable frameBg = UiKit.surface(
                this,
                0xff000000,
                10,
                selected ? UiKit.ACCENT : 0xff222d3d,
                selected ? 2.2f : 1.0f
            );
            frame.setBackground(frameBg);
            ClockFace face = new ClockFace(this, prefs);
            face.managedRefresh = true;
            face.todoStore = todoStore;
            face.bind(draft, i);
            face.nightPreview = previewNight;
            face.editorSlot=selected?slot:-1;
            face.setContentDescription("实时预览 · " + p.name);
            frame.addView(face);
            carouselPreviews.add(face);
            previews.add(face);
            item.addView(frame, new LinearLayout.LayoutParams(dp(previewW), dp(previewH)));

            // Apply initial 3D smartwatch perspective relative to selected page
            float density = getResources().getDisplayMetrics().density;
            item.setCameraDistance(4200 * density);
            if (selected) {
                item.setScaleX(1.0f);
                item.setScaleY(1.0f);
                item.setRotationX(0f);
                item.setTranslationZ(dp(6));
                item.setAlpha(1.0f);
            } else {
                int diff = i - selectedIndex;
                item.setScaleX(0.62f);
                item.setScaleY(0.62f);
                item.setRotationX(diff < 0 ? 20f : -20f);
                item.setTranslationZ(0f);
                item.setAlpha(0.44f);
            }

            // Selecting a page or slot updates workbench in-place with smooth centering
            label.setOnClickListener(v -> selectPageInPlace(pageIdx, 0, false, true));
            labelRow.setOnClickListener(v -> selectPageInPlace(pageIdx, 0, false, true));
            item.setOnClickListener(v -> selectPageInPlace(pageIdx, 0, false, true));
            face.slotTap = n -> selectPageInPlace(pageIdx, n, true, true);
            face.setOnClickListener(v -> selectPageInPlace(pageIdx, p.id.equals(pageId) ? slot : 0, true, true));

            View.OnLongClickListener drag = v -> {
                pageOverview = true;
                if (v.isAttachedToWindow()) {
                    return v.startDragAndDrop(
                        ClipData.newPlainText("page", p.id),
                        new View.DragShadowBuilder(frame),
                        p.id,
                        0
                    );
                }
                return true;
            };
            item.setOnLongClickListener(drag);
            face.setOnLongClickListener(drag);
            label.setOnLongClickListener(drag);

            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
            ip.bottomMargin = (i + 1 < draft.pages.size()) ? dp(fullHeight ? 6 : 4) : 0;
            track.addView(item, ip);
            itemViews.add(item);
        }

        carouselScroll.bind(itemViews, selectedIndex, pageIdx -> {
            if (pageIdx >= 0 && pageIdx < draft.pages.size()) {
                String nextId = draft.pages.get(pageIdx).id;
                if (!nextId.equals(pageId)) {
                    selectPageInPlace(pageIdx, 0, false, false);
                }
            }
        });
        carouselScroll.onMove = (id, target) -> {
            draft.movePage(id, target);
            pageId = id;
            changed();
            handler.post(this::show);
        };
        carouselScroll.addView(track, new ViewGroup.LayoutParams(-1, -2));
        host.addView(carouselScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        WatchCarouselScrollView stageScroll = carouselScroll;
        stageScroll.post(() -> {
            if (carouselScroll == stageScroll && stageScroll.snapAnimator == null) {
                stageScroll.centerIndex(stageScroll.selectedIndex, false);
            }
        });

        LinearLayout footer=UiKit.row(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setTag("preview:tools");footer.setPadding(dp(6),dp(3),dp(6),dp(4));
        footer.addView(UiKit.compactButton(this,"＋ 页面",UiKit.ROW,UiKit.ROW_BORDER,UiKit.ACCENT,()->showLayoutPicker(true)));
        pageHint=UiKit.text(this,neighborHint(),10,UiKit.MUTED);pageHint.setMaxLines(1);pageHint.setEllipsize(android.text.TextUtils.TruncateAt.END);pageHint.setGravity(Gravity.CENTER);footer.addView(pageHint,new LinearLayout.LayoutParams(0,-2,1));
        footer.addView(icon(previewNight?"☾":"☀\uFE0E",previewNight?"切换日间预览":"切换夜间预览",()->{history.finish();previewNight=!previewNight;colorState.hex=null;colorContext="";show();}));
        footer.addView(icon("⛶","全屏预览当前页",this::launchFullscreenPreview));
        host.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView icon(String text,String description,Runnable action){
        TextView view=UiKit.compactButton(this,text,UiKit.PANEL,0,UiKit.TEXT,action);view.setContentDescription(description);view.setTooltipText(description);
        view.setMinWidth(dp(40));view.setMinHeight(dp(40));view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,UiKit.sp(16));return view;
    }
    private String regionName(int index){
        if(page().layout==0)return "整页";
        if(page().layout==3)return new String[]{"左上","右上","左下","右下"}[index];
        return index==0?"左侧":"右侧";
    }
    private String neighborHint(){int index=draft.index(pageId);return draft.pages.size()==1?"仅此一页":index==0?"下一页 ↓":index==draft.pages.size()-1?"↑ 上一页":"↑ 上一页 · 下一页 ↓";}
    private void showPageChooser(){
        String[] labels=new String[draft.pages.size()];for(int i=0;i<labels.length;i++)labels[i]=(i+1)+" · "+draft.pages.get(i).name;
        AppDialogs.builder(this).setTitle("选择页面 · 长按预览可排序").setSingleChoiceItems(labels,draft.index(pageId),(d,n)->{d.dismiss();selectPageInPlace(n,0,false,true);}).setNegativeButton("关闭",null).show();
    }
    private void showComponentMenu(){
        String[] labels=new String[page().count()+1];for(int i=0;i<page().count();i++)labels[i]=(i==slot?"✓ ":"")+regionName(i)+" · "+Scene.typeName(page().slots[i].type);
        labels[labels.length-1]="更换当前组件…";
        AppDialogs.builder(this).setTitle("选择编辑区域").setItems(labels,(d,n)->{if(n==page().count()){openSelector(true);return;}slot=n;libraryType=component().type;selectionBaseline();refreshWorkbenchInPlace();for(ClockFace face:carouselPreviews){face.editorSlot=face.pageIdForEditor().equals(pageId)?slot:-1;face.invalidate();}}).setNegativeButton("关闭",null).show();
    }
    private void showPageMenu(){
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        labels.add("选择页面");actions.add(this::showPageChooser);
        labels.add(page().id.equals(draft.home)?"✓ 当前主页":"设为主页");actions.add(()->{draft.home=pageId;changed();show();});
        labels.add("重命名页面");actions.add(()->input("页面名称",page().name,value->{page().name=value;page().autoName=false;changed();show();}));
        labels.add("名称跟随组件 · "+(page().autoName?"开":"关"));actions.add(()->{page().autoName=!page().autoName;changed();show();});
        labels.add("复制此页");actions.add(()->{Scene.Page copy=page().duplicate();draft.pages.add(draft.index(pageId)+1,copy);pageId=copy.id;changed();show();showNotice("已复制页面");});
        labels.add("移动到…");actions.add(()->{String[] positions=new String[draft.pages.size()];for(int i=0;i<positions.length;i++)positions[i]="第 "+(i+1)+" 页";AppDialogs.builder(this).setTitle("移动页面").setItems(positions,(d,n)->{draft.movePage(pageId,n);changed();show();}).show();});
        labels.add("添加页面");actions.add(()->showLayoutPicker(true));
        labels.add("全屏预览当前页");actions.add(this::launchFullscreenPreview);
        labels.add(previewNight?"切换日间预览":"切换夜间预览");actions.add(()->{previewNight=!previewNight;colorState.hex=null;show();});
        labels.add("重做"+(history.canRedo()?"":" · 暂无"));actions.add(this::redoEdit);
        labels.add("删除此页");actions.add(()->{if(draft.pages.size()==1){showNotice("至少保留一个页面");return;}int index=draft.index(pageId);Scene.Page old=page();draft.remove(old);pageId=draft.pages.get(Math.min(index,draft.pages.size()-1)).id;slot=0;changed();show();showNotice("已删除「"+old.name+"」");});
        AppDialogs.builder(this).setTitle(page().name).setItems(labels.toArray(new String[0]),(d,n)->actions.get(n).run()).setNegativeButton("关闭",null).show();
    }
    private void showLayoutPicker(boolean adding) {
        openPreviewPicker(adding ? PreviewPicker.ADD_PAGE : PreviewPicker.PAGE_LAYOUT);
    }

    /** Compact object selection and Content / Appearance / Palette controls. */
    private void buildIntegratedWorkbench(LinearLayout host, boolean parallel) {
        int innerWidth=paneInnerWidth(false);
        LinearLayout header=UiKit.column(this);header.setPadding(dp(10),dp(4),dp(10),dp(2));
        LinearLayout controls=UiKit.row(this);
        float font=getResources().getConfiguration().fontScale*UiKit.customFontScale;
        boolean inline=innerWidth>=Math.round(320*Math.max(1f,font*.82f));
        if(!inline)controls.setOrientation(LinearLayout.VERTICAL);
        TextView target=UiKit.compactButton(this,regionName(slot)+" · "+Scene.typeName(component().type)+" ▾",UiKit.ROW,0,UiKit.ACCENT,this::showComponentMenu);
        target.setMaxLines(1);target.setEllipsize(android.text.TextUtils.TruncateAt.END);target.setTypeface(null,Typeface.BOLD);target.setTag("studio:object");target.setTooltipText("选择区域或更换组件");
        target.setContentDescription("当前编辑："+page().name+" · "+regionName(slot)+" · "+Scene.typeName(component().type)+"，点击切换");
        controls.addView(target,inline?new LinearLayout.LayoutParams(0,-2,1.3f):new LinearLayout.LayoutParams(-1,-2));
        LinearLayout tabs=UiKit.row(this);String[] names={"内容","外观","配色"};
        for(int i=0;i<3;i++){final int n=i;TextView button=UiKit.compactButton(this,names[i],studioPanel==i?UiKit.ACTIVE_BG:UiKit.PANEL,0,studioPanel==i?UiKit.ACCENT:UiKit.MUTED,()->{history.finish();studioPanel=n;show();});button.setTag("studio:tab:"+i);tabs.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
        LinearLayout.LayoutParams tabsLp=inline?new LinearLayout.LayoutParams(0,-2,2.5f):new LinearLayout.LayoutParams(-1,-2);
        controls.addView(tabs,tabsLp);header.addView(controls);host.addView(header,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout body=scrollColumn(host,"studio:"+studioPanel+":"+pageId+":"+slot);body.setTag("studio:body");
        if(host instanceof EditorPane){EditorPane pane=(EditorPane)host;pane.fixedHeader=controls;pane.extras=tabs;pane.body=body;pane.extrasParams=new LinearLayout.LayoutParams(tabsLp);pane.compact=false;}
        if(!prefs.getBoolean("studio_guided",false)){UiKit.note(this,body,"点击预览中的区域选择组件；外观和配色随改随存，可撤销。");UiKit.action(this,body,"知道了",()->{prefs.edit().putBoolean("studio_guided",true).apply();show();});}
        if(studioPanel==2)renderStudioPalette(body,innerWidth);
        else if(studioPanel==1){
            LinearLayout style=UiKit.card(this,body,"组件外观",null);UiKit.action(this,style,"更换外观 · "+ComponentStyles.names(component().type)[ComponentStyles.selectedIndex(component())]+"  →",()->openSelector(false));
            if(component().type.equals("clock")){ComponentInspector.render(this,prefs,body,component(),0,paletteScope,innerWidth,inspectorHost());ComponentInspector.render(this,prefs,body,component(),1,paletteScope,innerWidth,inspectorHost());}
            else{
                Scene.Component selected = component();
                if (selected.type.equals("music")) {
                    boolean lyricsShown = selected.bool(MusicLyrics.SHOW, false);
                    UiKit.toggle(this, style, "显示歌词", lyricsShown, shown -> {
                        Scene.put(selected.options, MusicLyrics.SHOW, shown);
                        changed();
                        show();
                    });
                    if (lyricsShown) musicLyricsAppearance(style, selected);
                }
                if (ComponentStyles.hasMusicArtwork(selected)) {
                    UiKit.choice(this, style, "造型", new String[]{"写实", "矢量"},
                            ComponentStyles.vectorArtwork(selected) ? 1 : 0, choice -> {
                                ComponentStyles.setVectorArtwork(selected, choice == 1);
                                changed();
                                show();
                            });
                }
                UiKit.slider(this,style,"显示大小",selected.options.optInt("scale",100),75,125,"%",v->{Scene.put(selected.options,"scale",v);changed();});
                UiKit.toggle(this,style,"显示组件边框",selected.bool("card",false),v->{Scene.put(selected.options,"card",v);changed();});
            }
        }else ComponentInspector.render(this,prefs,body,component(),component().type.equals("clock")?3:0,paletteScope,innerWidth,inspectorHost());
        savedStatus=UiKit.text(this,"已自动保存",10,UiKit.MUTED);savedStatus.setPadding(dp(2),dp(8),0,0);body.addView(savedStatus);

    }

    private void musicLyricsAppearance(LinearLayout host, Scene.Component component) {
        MusicLyrics.Position[] positions = MusicLyrics.Position.OPTIONS;
        String[] labels = new String[positions.length];
        for (int i = 0; i < positions.length; i++) labels[i] = positions[i].label;
        UiKit.choice(this, host, "歌词位置", labels, MusicLyrics.position(component).ordinal(), choice -> {
            Scene.put(component.options, MusicLyrics.POSITION, positions[choice].id);
            changed();
            show();
        });
        UiKit.slider(this, host, "歌词字号", MusicLyrics.fontSize(component),
                MusicLyrics.MIN_FONT_SIZE, MusicLyrics.MAX_FONT_SIZE, "%", value -> {
                    Scene.put(component.options, MusicLyrics.FONT_SIZE, value);
                    changed();
                });
    }

    private void openSelector(boolean types) {
        libraryType = component().type;
        openPreviewPicker(types ? PreviewPicker.COMPONENT : PreviewPicker.STYLE);
    }

    private void openPreviewPicker(PreviewPicker picker) {
        history.finish();
        navExpanded = false;
        previewPicker = picker;
        show();
    }

    private void closePreviewPicker() {
        previewPicker = null;
        tab = 1;
        show();
    }
    private void renderStudioPalette(LinearLayout body,int innerWidth){
        String[] names={"全场景","当前页","当前组件"};
        LinearLayout card=UiKit.card(this,body,previewNight?"夜间配色":"日间配色",null);
        UiKit.action(this,card,"应用范围 · "+names[Math.min(2,paletteScope)]+" ▾",()->AppDialogs.builder(this).setTitle("配色应用范围").setSingleChoiceItems(names,Math.min(2,paletteScope),(d,n)->{history.finish();paletteScope=n;colorState.hex=null;colorContext="";d.dismiss();show();}).show());
        if(previewNight){UiKit.toggle(this,card,"此场景使用独立夜间配色",Boolean.TRUE.equals(draft.ownNightColors),v->{draft.ownNightColors=v;changed();show();});if(!Boolean.TRUE.equals(draft.ownNightColors)){UiKit.note(this,card,"当前沿用日间配色。开启后可独立编辑夜间颜色。");return;}}
        JSONObject selected=theme(paletteScope);UiKit.note(this,card,selected.length()==0?"颜色来自上层主题":"此范围已有独立配色 · 可恢复跟随上层");
        int overrides=0;for(Scene.Page page:draft.pages){if(paletteScope==0&&(previewNight?page.nightTheme:page.theme).length()>0)overrides++;if(paletteScope==0||page.id.equals(pageId)&&paletteScope==1)for(int i=0;i<page.count();i++)if((previewNight?page.slots[i].nightTheme:page.slots[i].theme).length()>0)overrides++;}
        if(overrides>0){final int scope=paletteScope;UiKit.note(this,card,"有 "+overrides+" 处独立配色，会优先于本次修改。");UiKit.action(this,card,"让下级恢复跟随",()->{for(Scene.Page page:draft.pages){if(scope==0)clearTheme(previewNight?page.nightTheme:page.theme);if(scope==0||scope==1&&page.id.equals(pageId))for(Scene.Component c:page.slots)clearTheme(previewNight?c.nightTheme:c.theme);}changed();show();showNotice("已恢复跟随");});}
        palette(card,paletteScope);
    }
    private void clearTheme(JSONObject theme){ArrayList<String> keys=new ArrayList<>();for(Iterator<String> i=theme.keys();i.hasNext();)keys.add(i.next());for(String key:keys)theme.remove(key);}

    private void renderPreviewPicker(LinearLayout root, boolean landscape, float fontScale) {
        PreviewPicker picker = previewPicker;
        boolean adding = picker == PreviewPicker.ADD_PAGE;
        boolean pageLayouts = adding || picker == PreviewPicker.PAGE_LAYOUT;
        boolean types = picker == PreviewPicker.COMPONENT;
        String[] names;
        String subtitle;
        Scene.Component current = pageLayouts ? null : component();
        if (pageLayouts) {
            names = Dashboard.LAYOUTS;
            subtitle = adding ? "选择布局与默认搭配" : "切换布局会保留暂时隐藏的区域内容";
        } else {
            if (libraryType == null) libraryType = current.type;
            names = types ? Scene.TYPE_NAMES : ComponentStyles.names(libraryType);
            subtitle = Scene.typeName(current.type) + " · " + regionName(slot);
        }
        float aspect = pageLayouts ? AdaptiveLayout.PAGE_PREVIEW_ASPECT : AdaptiveLayout.COMPONENT_PREVIEW_ASPECT;
        int availableWidth = Math.max(1, widthDp - 24);
        int columns = AdaptiveLayout.galleryColumns(availableWidth, heightDp, fontScale, names.length, landscape, aspect);
        int galleryWidth = AdaptiveLayout.galleryWidth(availableWidth, heightDp, fontScale, columns, landscape, aspect);
        int gap = dp(AdaptiveLayout.GALLERY_GAP);
        int tileWidth = (dp(galleryWidth) - (columns - 1) * gap) / columns;
        int previewHeight = Math.max(dp(1), Math.round(tileWidth / aspect));

        LinearLayout top = UiKit.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(6), 0, dp(6));
        TextView back = icon("‹", "返回编辑", this::closePreviewPicker);
        back.setMinWidth(dp(48));
        back.setMinHeight(dp(48));
        top.addView(back);
        LinearLayout heading = UiKit.column(this);
        heading.setPadding(dp(12), 0, 0, 0);
        TextView title = UiKit.text(this, picker.title, 16, UiKit.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        heading.addView(title);
        heading.addView(UiKit.text(this, subtitle, 11, UiKit.MUTED));
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(dp(Math.min(availableWidth,
                Math.max(320, galleryWidth))), -2);
        toolbarParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(top, toolbarParams);

        String scrollKey = pageLayouts ? "selector:" + picker : "selector:" + types + ":" + libraryType;
        ScrollView scroll = scroll(root, scrollKey, false);
        scroll.setPadding(dp(12), dp(4), dp(12), dp(12));
        LinearLayout gallery = UiKit.column(this);
        scroll.addView(gallery, new FrameLayout.LayoutParams(dp(galleryWidth), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        for (int start = 0; start < names.length; start += columns) {
            LinearLayout row = UiKit.row(this);
            for (int column = 0; column < columns; column++) {
                int index = start + column;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                if (column > 0) params.setMarginStart(gap);
                if (index < names.length) {
                    View tile = pageLayouts ? pageLayoutTile(index, adding, previewHeight)
                            : componentTile(current, types ? Scene.TYPES[index] : libraryType,
                                    index, names[index], types, previewHeight);
                    row.addView(tile, params);
                } else {
                    row.addView(new Space(this), params);
                }
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            if (start + columns < names.length) rowParams.bottomMargin = gap;
            gallery.addView(row, rowParams);
        }
    }

    private View pageLayoutTile(int layout, boolean adding, int previewHeight) {
        Scene sample = draft.copy();
        Scene.Page example = adding ? new Scene.Page() : sample.pages.get(sample.index(pageId));
        example.layout = layout;
        if (adding) {
            String[] defaults = PAGE_PRESET_COMPONENTS[layout];
            for (int i = 0; i < defaults.length; i++) example.slots[i].setType(defaults[i]);
            example.refreshName();
            sample.pages.add(example);
        }
        String name = Dashboard.LAYOUTS[layout];
        String label = adding ? name + " · " + example.contentName() : name;
        ClockFace face = galleryFace(sample, sample.index(example.id));
        // Lay out the entire page before scaling it down, including all four slots in small windows.
        face.setLayoutTarget(800, 800 / AdaptiveLayout.PAGE_PREVIEW_ASPECT, null);
        PreviewTile tile = previewTile(face, label, !adding && page().layout == layout, previewHeight);
        tile.setTag("page-layout:" + layout);
        tile.setContentDescription((adding ? "添加页面 · " : "使用布局 · ") + label);
        tile.setOnClickListener(v -> {
            history.finish();
            if (adding) {
                Scene.Page added = Scene.Page.from(example.json());
                draft.pages.add(added);
                pageId = added.id;
                slot = 0;
            } else {
                page().layout = layout;
                slot = Math.min(slot, page().count() - 1);
            }
            changed();
            closePreviewPicker();
            if (adding) showNotice("页面已添加 · 点击区域编辑");
        });
        return tile;
    }

    private View componentTile(Scene.Component current, String type, int index, String label, boolean types, int previewHeight) {
        boolean active = current.type.equals(type) && (types || ComponentStyles.selectedIndex(current) == index);
        Scene sample = draft.copy();
        int pageIndex = sample.index(pageId);
        Scene.Page page = sample.pages.get(pageIndex);
        page.layout = 0;
        Scene.Component example = Scene.Component.from(current.json());
        page.slots[0] = example;
        if (types) example.setType(type);
        else ComponentStyles.select(example, type, index);
        if (type.equals("todo") && TodoItems.all(example).length() == 0) {
            TodoItems.add(example, "准备明天的计划");
            TodoItems.add(example, "阅读二十分钟");
            TodoItems.add(example, "整理桌面");
            TodoItems.toggle(example, 2);
        }

        PreviewTile tile = previewTile(galleryFace(sample, pageIndex), label, active, previewHeight);
        String note = types ? "" : ComponentStyles.notes(type)[index];
        tile.setContentDescription((types ? "更换组件 · " : "应用外观 · ") + label + (note.isEmpty() ? "" : " · " + note));
        tile.setTag(types ? "component:" + type : "style:" + type + ":" + ComponentStyles.ids(type)[index]);
        tile.setOnClickListener(v -> {
            history.finish();
            Scene.Component selected = component();
            if (types) selected.setType(type);
            else ComponentStyles.select(selected, type, index);
            libraryType = selected.type;
            changed();
            closePreviewPicker();
        });
        return tile;
    }

    private ClockFace galleryFace(Scene sample, int pageIndex) {
        ClockFace face = new ClockFace(this, prefs);
        face.managedRefresh = true;
        face.demo = true;
        face.nightPreview = previewNight;
        face.bind(sample, pageIndex);
        previews.add(face);
        if (!testMode) {
            DeskData.refreshWorldDaylightPreview(this, sample.pages.get(pageIndex), prefs, () -> {
                for (ClockFace preview : previews) preview.invalidate();
            });
        }
        return face;
    }

    private PreviewTile previewTile(ClockFace face, String label, boolean active, int previewHeight) {
        PreviewTile tile = new PreviewTile(this);
        tile.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33b8e5c7), null,
                UiKit.surface(this, Color.WHITE, 14)));
        tile.setFocusable(true);
        tile.setSelected(active);
        face.setBackground(UiKit.surface(this, Color.BLACK, 9));
        face.setClipToOutline(true);
        face.setForeground(UiKit.surface(this, Color.TRANSPARENT, 9,
                active ? UiKit.ACCENT : UiKit.PANEL_BORDER, active ? 2 : 1));
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(face, new LinearLayout.LayoutParams(-1, previewHeight));

        LinearLayout caption = UiKit.row(this);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setMinimumHeight(dp(34));
        caption.setPadding(dp(5), dp(3), dp(5), 0);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = UiKit.text(this, label, 13, active ? UiKit.ACCENT : UiKit.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        caption.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (active) {
            TextView check = UiKit.text(this, "✓", 13, UiKit.ACCENT);
            check.setPadding(dp(5), 0, 0, 0);
            caption.addView(check);
        }
        tile.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        return tile;
    }

    private static final class PreviewTile extends LinearLayout {
        PreviewTile(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            // The tile owns taps across the preview and caption.
            return true;
        }

        @Override public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }
    }

    private ComponentInspector.Host inspectorHost() {
        return new ComponentInspector.Host() {
            public void onDraftChanged() { changed(); }
            public void onRebuild() { show(); }
            public void onSwitchComponentType(String newType) {
                component().setType(newType);
                inspectorGroup = 0;
                libraryType = newType;
                changed();
                show();
            }
            public void onSelectPaletteScope(int scope) {
                paletteScope = scope;
                colorState.hex = null;
                if (scope == 3) { paletteScope=0;previewNight=true; }
                show();
            }
            public void onEmbedPalette(LinearLayout host, int scope) { palette(host, scope); }
            public void onOpenTimezone(Scene.Component c) { zone(c); }
            public void onOpenWeatherSettings() { openSettings(DATA_SETTINGS, true, WEATHER_SETTINGS); }
            public void onPickFocusDuration() { showFocusDuration(); }
            public void onOpenFocusSettings() { openSettings(DATA_SETTINGS, true, FOCUS_SETTINGS); }
            public void onOpenPermissionSettings() { openSettings(DATA_SETTINGS, true, PERMISSION_SETTINGS); }
            public void onCalendarAccess() { calendarPermission(calendarGranted()); }
            public void onMusicAccess() { openMusicAccess(); }
            public void onImportMusicLyrics() { importMusicLyrics(); }
            public void onChooseMusicLyrics() { chooseMusicLyrics(); }
            public void onAdjustMusicLyrics() { adjustMusicLyrics(); }
            public void onSpectrumAccess(boolean enabled) { setSpectrumAccess(enabled); }
            public void onOpenMotionSettings() { openSettings(PAGE_SETTINGS, true, null); }
            public void onOpenTodoInput(String title, String initial, Consumer<String> onValue) { input(title, initial, onValue); }
            public TodoStore todoStore() { return todoStore; }
            public void onOpenTodoManager(String list, boolean add) { openTodoManager(list, add); }
        };
    }

    private void openSettings(int group, boolean detail, String anchor) {
        navExpanded = false;
        previewPicker = null;
        category = group;
        settingsDetail = detail;
        pendingSettingsAnchor = anchor;
        tab = 3;
        show();
    }

    private boolean parallelSettings() {
        float font = getResources().getConfiguration().fontScale * UiKit.customFontScale;
        return settingsHinge != null || AdaptiveLayout.settingsUseTwoPanes(settingsLandscape, widthDp, heightDp, font);
    }

    private void backFromSettings() {
        if (settingsDetail && !parallelSettings()) settingsDetail = false;
        else tab = 1;
        pendingSettingsAnchor = null;
        show();
    }

    private void renderSettingsShell(LinearLayout root) {
        boolean parallel = parallelSettings();
        boolean overview = !parallel && !settingsDetail;
        int sidePadding = settingsLandscape && !parallel ? Math.max(6, (widthDp - 650) / 2) : 6;

        LinearLayout shell = UiKit.column(this);
        shell.setPadding(dp(sidePadding), dp(5), dp(sidePadding), dp(6));
        root.addView(shell, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout toolbar = UiKit.row(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        String backLabel = !parallel && settingsDetail ? "返回设置分类" : "返回页面工作台";
        toolbar.addView(icon("‹", backLabel, this::backFromSettings));
        TextView title = UiKit.text(this, parallel || overview ? "设置" : SETTINGS_TITLES[category], 15, UiKit.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        title.setPadding(dp(8), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!parallel) shell.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout panes = UiKit.row(this);
        shell.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        int menuWidth = settingsHinge == null ? Math.min(220, Math.max(140, Math.round(widthDp * .27f)))
                : settingsHinge.left - navigationWidthDp - sidePadding - 4;
        if (parallel || overview) {
            LinearLayout menu = settingsPane("settings:categories");
            panes.addView(menu, new LinearLayout.LayoutParams(parallel ? dp(menuWidth) : -1, -1));
            if (parallel) {
                toolbar.setPadding(dp(8), dp(6), dp(8), dp(4));
                menu.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout list = scrollColumn(menu, "settingsMenu");
            for (int group : SETTINGS_ORDER) {
                settingsCategory(list, group, parallel && group == category);
            }
        }
        if (overview) return;

        LinearLayout detail = settingsPane("settings:detail");
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, -1, 1);
        if (parallel) detailParams.leftMargin = dp(settingsHinge == null ? 8 : settingsHinge.width() + 8);
        panes.addView(detail, detailParams);
        LinearLayout body = scrollColumn(detail, "settings:" + category);
        if (parallel) UiKit.title(this, body, SETTINGS_TITLES[category], null);

        switch (category) {
            case DISPLAY_SETTINGS: renderDisplaySettings(body); break;
            case PAGE_SETTINGS: renderPageSettings(body); break;
            case DATA_SETTINGS: renderDataSettings(body); break;
            case STANDBY_SETTINGS: renderStandbySettings(body); break;
            case SCENE_SETTINGS: renderSceneSettings(body); break;
            default: renderAboutSettings(body); break;
        }
        if (pendingSettingsAnchor != null) {
            View anchor = body.findViewWithTag(pendingSettingsAnchor);
            pendingSettingsAnchor = null;
            if (anchor != null) anchor.post(() -> anchor.requestRectangleOnScreen(
                new Rect(0, 0, anchor.getWidth(), Math.min(anchor.getHeight(), dp(150))), true));
        }
    }

    private LinearLayout settingsPane(String tag) {
        LinearLayout pane = UiKit.column(this);
        pane.setTag(tag);
        pane.setBackground(UiKit.surface(this, UiKit.PANEL, 14, UiKit.PANEL_BORDER, 1));
        return pane;
    }

    private void settingsCategory(LinearLayout host, int group, boolean selected) {
        String summary;
        switch (group) {
            case DISPLAY_SETTINGS:
                summary = prefs.getBoolean("night", true) ? "亮度 · " + nightPeriodLabel() : "亮度与字号 · 夜间关闭";
                break;
            case PAGE_SETTINGS:
                summary = prefs.getBoolean("autoplay", false)
                    ? "自动轮播 · " + prefs.getInt("pageInterval", 20) + " 秒" : "主页、自动轮播与动效";
                break;
            case DATA_SETTINGS:
                summary = prefs.contains("city") ? "天气 · " + weatherCity() : "天气、专注、闹钟与权限";
                break;
            case STANDBY_SETTINGS: summary = standbySummary(); break;
            case SCENE_SETTINGS: summary = draft.name + " · " + savedSceneNames().size() + " 个快照"; break;
            default: summary = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME; break;
        }
        LinearLayout row = UiKit.column(this);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(UiKit.surface(this, selected ? UiKit.ACTIVE_BG : UiKit.ROW, 10,
            selected ? UiKit.ACTIVE_BORDER : UiKit.ROW_BORDER, 1));
        row.setOnClickListener(v -> openSettings(group, true, null));
        row.setFocusable(true);
        row.setSelected(selected);
        TextView title = UiKit.text(this, SETTINGS_TITLES[group], 13, selected ? UiKit.ACCENT : UiKit.TEXT);
        row.addView(title);
        TextView detail = UiKit.text(this, summary, 10.5f, selected ? UiKit.ACCENT : UiKit.MUTED);
        if (group == STANDBY_SETTINGS) detail.setTag("settings:standby-summary");
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(detail);
        row.setContentDescription(SETTINGS_TITLES[group] + "，" + summary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(6);
        host.addView(row, params);
    }

    private void renderDisplaySettings(LinearLayout body) {
        LinearLayout display = UiKit.card(this, body, "界面与日间显示", null);
        float[] scales = { .85f, 1f, 1.15f, 1.30f };
        int selected = 1;
        for (int i = 0; i < scales.length; i++) {
            if (Math.abs(scales[i] - prefs.getFloat("uiFontScale", 1f)) < .04f) selected = i;
        }
        UiKit.choice(this, display, "界面与文字大小",
            new String[] { "小 85%", "标准 100%", "大 115%", "特大 130%" }, selected, n -> {
                prefs.edit().putFloat("uiFontScale", scales[n]).apply();
                show();
            });
        UiKit.slider(this, display, "日间亮度", Math.round(prefs.getFloat("brightness", .25f) * 100),
            10, 100, "%", v -> prefs.edit().putFloat("brightness", v / 100f).apply());

        LinearLayout night = UiKit.card(this, body, "夜间显示", null);
        boolean nightEnabled = prefs.getBoolean("night", true);
        rebuildingToggle(night, "按时段启用夜间模式", "night", true);
        if (nightEnabled) {
            UiKit.action(this, night, "夜间时段 · " + nightPeriodLabel() + "  ›", this::showNightPeriod);
            UiKit.nightBrightnessSlider(this, night, "夜间亮度", prefs.getFloat("nightBrightness", .06f),
                v -> prefs.edit().putFloat("nightBrightness", v).apply());
            prefToggle(night, "夜间联动系统极暗", "nightExtraDim", true);
            rebuildingToggle(night, "夜间闲置时允许息屏", "nightSleep", true);
            if (prefs.getBoolean("nightSleep", true)) {
                prefChoice(night, "息屏前等待", new String[] { "10 秒", "20 秒", "30 秒", "60 秒" },
                    new int[] { 10, 20, 30, 60 }, "nightViewSeconds", 20);
            } else UiKit.note(this, night, "待机时不按此计时息屏。");
        } else UiKit.note(this, night, "开启后可设置夜间时段、亮度与息屏时间。");

        UiKit.action(this, night, "编辑「" + draft.name + "」的夜间配色  ›", () -> {
            previewNight = true;
            paletteScope = 0;
            studioPanel = 2;
            tab = 1;
            colorState.hex = null;
            show();
        });
    }

    private void renderPageSettings(LinearLayout body) {
        boolean autoplay = prefs.getBoolean("autoplay", false);
        LinearLayout paging = UiKit.card(this, body, "主页与轮播", null);
        UiKit.note(this, paging, "当前场景的主页 · " + draft.pages.get(draft.homeIndex()).name);
        UiKit.action(this, paging, "指定当前场景主页  ›", () -> {
            String[] names = new String[draft.pages.size()];
            for (int i = 0; i < names.length; i++) names[i] = (i + 1) + " · " + draft.pages.get(i).name;
            AppDialogs.builder(this).setTitle("指定当前场景主页")
                .setSingleChoiceItems(names, draft.homeIndex(), (d, n) -> {
                    d.dismiss();
                    draft.home = draft.pages.get(n).id;
                    changed();
                    show();
                }).setNegativeButton("取消", null).show();
        });
        rebuildingToggle(paging, "自动轮播", "autoplay", false);
        if (autoplay) {
            prefChoice(paging, "轮播间隔", new String[] { "10 秒", "20 秒", "30 秒", "60 秒" },
                new int[] { 10, 20, 30, 60 }, "pageInterval", 20);
        }
        if (!autoplay || prefs.getBoolean("night", true)) {
            prefChoice(paging, autoplay ? "夜间闲置回主页" : "闲置回主页",
                new String[] { "关闭", "15 秒", "30 秒", "60 秒", "120 秒" },
                new int[] { 0, 15, 30, 60, 120 }, "homeAfter", 60);
        }
        if (autoplay) UiKit.note(this, paging, "自动轮播只在日间待机生效。");

        LinearLayout motion = UiKit.card(this, body, "显示动效", null);
        rebuildingToggle(motion, "启用动态效果", "motion", true);
        if (!prefs.getBoolean("motion", true)) {
            UiKit.note(this, motion, "当前停用页面过渡、充电光效等动画。");
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            UiKit.note(this, motion, "系统已关闭动画。开启系统动画后，此处的过渡设置会继续生效。");
        } else {
            prefChoice(motion, "切换动画", PageMotion.NAMES, new int[] { 0, 1, 2 }, "pageMotion", 0);
            if (prefs.getInt("pageMotion", 0) != 2) {
                prefChoice(motion, "切换速度", new String[] { "轻快", "自然", "舒缓" },
                    new int[] { 240, 360, 520 }, "pageMotionDuration", 360);
            }
        }
    }

    private void renderDataSettings(LinearLayout body) {
        LinearLayout weather = UiKit.card(this, body, "天气", "天气地点与温标应用到所有场景。");
        weather.setTag(WEATHER_SETTINGS);
        UiKit.action(this, weather, "天气地点 · " + weatherCity() + "  ›", this::showWeatherSources);
        TextView weatherStatus = UiKit.text(this, weatherSummary(), 11, UiKit.MUTED);
        weatherStatus.setTag("settings:weather-status");
        weather.addView(weatherStatus);
        UiKit.choice(this, weather, "温标", new String[] { "摄氏 (°C)", "华氏 (°F)" },
            prefs.getBoolean("fahrenheit", false) ? 1 : 0, n -> {
                prefs.edit().putBoolean("fahrenheit", n == 1).apply();
                show();
            });
        if (prefs.contains("city")) {
            TextView refresh = UiKit.action(this, weather, "刷新天气", () -> {
                if (!testMode) DeskData.refresh(this, true);
                updateSettingsStatus();
            });
            refresh.setTag("settings:weather-refresh");
            setWeatherRefreshState(refresh);
        }

        LinearLayout focus = UiKit.card(this, body, "专注计时", "默认时长从下一轮计时生效。");
        focus.setTag(FOCUS_SETTINGS);
        TextView focusDuration = UiKit.action(this, focus,
            "默认专注时长 · " + FocusDuration.label(DeskData.focusMinutes(prefs)) + "  ›",
            this::showFocusDuration);
        focusDuration.setTag("settings:focus-duration");
        UiKit.action(this, focus, "重置当前计时", () -> {
            if (DeskData.remaining(prefs) < DeskData.focusTotal(prefs) && DeskData.remaining(prefs) > 0) {
                AppDialogs.builder(this).setTitle("重置当前计时？")
                    .setMessage("本轮进度会清除，并按默认专注时长重新准备。")
                    .setPositiveButton("重置", (d, n) -> resetCurrentFocus()).setNegativeButton("取消", null).show();
            } else resetCurrentFocus();
        });
        ComponentInspector.alarmDataCard(this, body);

        LinearLayout lyrics = UiKit.card(this, body, "音乐歌词", null);
        prefToggle(lyrics, "联网查找歌词", MusicLyrics.ONLINE, MusicLyrics.ONLINE_DEFAULT);
        UiKit.note(this, lyrics, "优先读取播放器原生歌词（元数据、状态栏歌词、蓝牙歌词与歌词广播）；未提供时向 LRCLIB 自动匹配，仅发送歌名和歌手。");

        LinearLayout permissions = UiKit.card(this, body, "权限与锁屏隐私", null);
        permissions.setTag(PERMISSION_SETTINGS);
        prefToggle(permissions, "锁屏显示日程并允许管理待办", "private", false);
        boolean calendarGranted = calendarGranted();
        UiKit.action(this, permissions, "日历访问 · " + (calendarGranted ? "已允许  ›" : "未授权  ›"),
            () -> calendarPermission(calendarGranted));
        UiKit.action(this, permissions, "音乐访问 · " + (DeskData.hasMusicAccess(this) ? "已开启  ›" : "未开启  ›"),
            this::openMusicAccess);
        UiKit.note(this, permissions, "音乐访问用于读取播放状态与播放器歌词通知，不读取其他应用通知。");
        UiKit.toggle(this, permissions, "第三方实时频谱（可选）", MusicSpectrum.enabled(this, prefs),
            this::setSpectrumAccess);
        UiKit.action(this, permissions, "天气定位 · " + (WeatherLocation.allowed(this) ? "已允许  ›" : "按需授权  ›"),
            this::weatherLocationPermission);
        boolean notificationsEnabled = notificationsEnabled();
        UiKit.action(this, permissions, "应用通知 · " + (notificationsEnabled ? "已允许  ›" : "已关闭  ›"),
            this::openNotificationAccess);
        UiKit.action(this, permissions, "系统应用权限  ›", this::openAppPermissions);
    }

    private void resetCurrentFocus() {
        DeskData.resetFocus(prefs);
        Toast.makeText(this, "已重置当前计时", Toast.LENGTH_SHORT).show();
    }

    private void showFocusDuration() {
        InputDialog dialog = new InputDialog(this, "默认专注时长", "左右选择小时与分钟，默认时长从下一轮计时生效。");
        FocusDurationPicker picker = new FocusDurationPicker(this, DeskData.focusMinutes(prefs));
        dialog.content().addView(picker);
        dialog.primary("应用", android.view.inputmethod.EditorInfo.IME_ACTION_DONE, () -> {
            int total = picker.totalMinutes();
            if (total < FocusDuration.MIN_MINUTES) {
                picker.showZeroError();
                return;
            }
            DeskData.setFocusMinutes(prefs, total);
            dialog.dismiss();
            show();
        });
        openInputDialog(dialog);
    }

    private String weatherCity() {
        return WeatherCities.simplified(prefs.getString("city", "未设置"));
    }

    private String weatherSummary() {
        if (!prefs.contains("city")) return "设置天气地点后可更新。";
        boolean hasCache = DeskData.weatherAt > 0;
        String updated = hasCache ? "上次更新 · " + android.text.format.DateFormat.format("MM-dd HH:mm", DeskData.weatherAt)
            : "尚未获取天气";
        if (DeskData.weatherBusy) return "正在更新 · " + updated;
        if (!DeskData.weatherError.isEmpty()) return DeskData.weatherError + "\n" + updated;
        return updated;
    }

    private void showWeatherSources() {
        String location = WeatherLocation.allowed(this) ? "使用当前位置（近似定位）" : "使用当前位置（需授权）";
        String[] methods = { "搜索城市", location, "手动输入经纬度" };
        AppDialogs.builder(this).setTitle("天气地点 · " + weatherCity()).setItems(methods, (d, n) -> {
            if (n == 0) city();
            else if (n == 1) useCurrentLocation();
            else coordinates();
        }).setNegativeButton("取消", null).show();
    }

    private boolean calendarGranted() {
        return checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void calendarPermission(boolean granted) {
        if (granted || prefs.getBoolean("calendarPermissionRequested", false)
            && !shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CALENDAR)) {
            openAppPermissions();
        } else {
            prefs.edit().putBoolean("calendarPermissionRequested", true).apply();
            requestPermissions(new String[] { android.Manifest.permission.READ_CALENDAR }, CALENDAR_PERMISSION);
        }
    }

    private void openAppPermissions() {
        openSystemSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
                "此设备未提供应用权限设置");
    }

    private void openMusicAccess() {
        openSystemSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "此设备未提供通知使用权设置");
    }

    private MusicLyrics.Track currentLyricsTrack() {
        if (!testMode) DeskData.refresh(this, false);
        MediaController controller = DeskData.musicAllowed ? DeskData.media : null;
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        MusicLyrics.Track track = MusicLyrics.track(controller, metadata, MusicLyrics.key(controller, metadata));
        if (track == null) {
            message(DeskData.musicAllowed ? "请先在播放器中选择一首曲目。"
                    : "请先开启音乐访问，以识别当前曲目。");
        }
        return track;
    }

    private void chooseMusicLyrics() {
        MusicLyrics.Track track = currentLyricsTrack();
        if (track == null) return;
        if (!track.searchable()) {
            message("播放器没有提供完整的歌名和歌手。可以导入这首歌的 LRC 歌词。");
            return;
        }
        if (!prefs.getBoolean(MusicLyrics.ONLINE, MusicLyrics.ONLINE_DEFAULT)) {
            AppDialogs.builder(this).setTitle("开启联网查找歌词？")
                    .setMessage("开启后，时栖时钟会向 LRCLIB 发送当前曲目的歌名和歌手。可在「设置 → 数据与权限」关闭。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开启并查找", (dialog, which) -> {
                        prefs.edit().putBoolean(MusicLyrics.ONLINE, true).apply();
                        findLyricsVersions(track);
                    }).show();
        } else findLyricsVersions(track);
    }

    private void findLyricsVersions(MusicLyrics.Track track) {
        AlertDialog progress = AppDialogs.builder(this).setTitle("查找同步歌词")
                .setMessage(track.title + " · " + track.artist).setNegativeButton("取消", null).show();
        OnlineLyrics.search(this, track, result -> {
            if (!progress.isShowing() || isFinishing() || isDestroyed()) return;
            progress.dismiss();
            if (!prefs.getBoolean(MusicLyrics.ONLINE, MusicLyrics.ONLINE_DEFAULT)) return;
            String title = "换歌词版本 · " + track.title;
            AlertDialog.Builder options = AppDialogs.builder(this).setTitle(title).setNegativeButton("关闭", null);
            if (OnlineLyrics.selected(this, track) != null) {
                options.setNeutralButton("恢复自动选择", (dialog, which) -> MusicLyrics.library(this).edit()
                        .remove(MusicLyrics.selectedOnlineKey(track.key)).apply());
            }
            if (result.candidates.isEmpty()) {
                options.setMessage(result.failure == null ? "未找到带时间戳的歌词。可以导入 LRC 文件。"
                        : result.failure.label + "，请稍后再试。");
            } else {
                String[] labels = new String[result.candidates.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = result.candidates.get(i).label();
                options.setItems(labels, (dialog, which) -> {
                    OnlineLyrics.select(this, track, result.candidates.get(which));
                    showNotice("已选择「" + track.title + "」的歌词版本");
                });
            }
            options.show();
        });
    }

    private void adjustMusicLyrics() {
        MusicLyrics.Track track = currentLyricsTrack();
        if (track == null) return;
        LinearLayout body = UiKit.column(this);
        body.setPadding(dp(20), dp(4), dp(20), dp(4));
        UiKit.note(this, body, "正值提前，负值延后。调整只应用于当前曲目。");
        int[] offset = { MusicLyrics.offset(this, track.key) };
        UiKit.slider(this, body, "时间偏移", offset[0], -MusicLyrics.MAX_OFFSET, MusicLyrics.MAX_OFFSET,
                " 毫秒", value -> offset[0] = value);
        AppDialogs.builder(this).setTitle("调整歌词时间 · " + track.title).setView(body)
                .setNeutralButton("归零", (dialog, which) -> MusicLyrics.setOffset(this, track.key, 0))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> MusicLyrics.setOffset(this, track.key, offset[0])).show();
    }

    private void importMusicLyrics() {
        MusicLyrics.Track track = currentLyricsTrack();
        if (track == null) return;
        pendingLyricsKey = track.key;
        pendingLyricsTitle = track.title;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(picker, IMPORT_MUSIC_LYRICS);
        } catch (ActivityNotFoundException unavailable) {
            pendingLyricsKey = pendingLyricsTitle = null;
            message("此设备未提供文件选择器。");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_MUSIC_LYRICS) return;
        String key = pendingLyricsKey, title = pendingLyricsTitle;
        pendingLyricsKey = pendingLyricsTitle = null;
        Uri file = result == RESULT_OK && data != null ? data.getData() : null;
        if (key == null || file == null) return;
        Context context = getApplicationContext();
        DeskData.IO.execute(() -> {
            String notice;
            try (InputStream input = context.getContentResolver().openInputStream(file)) {
                if (input == null) throw new IOException();
                String lrc = LrcLyrics.read(input).replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
                if (!LrcLyrics.parse(lrc).hasLines()) notice = "文件中没有带时间戳的歌词。";
                else if (MusicLyrics.library(context).edit().putString(key, lrc)
                        .remove(MusicLyrics.selectedOnlineKey(key)).commit()) {
                    notice = title == null || title.isEmpty() ? "已导入当前曲目的歌词"
                            : "已导入「" + title + "」的歌词";
                } else notice = "歌词保存失败，请重试。";
            } catch (IOException | SecurityException unavailable) {
                notice = "无法导入歌词，请选择 256 KB 以内的 LRC 文件。";
            }
            String status = notice;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                for (ClockFace preview : previews) preview.invalidate();
                showNotice(status);
            });
        });
    }

    private void setSpectrumAccess(boolean enabled) {
        if (!enabled || MusicSpectrum.allowed(this)) {
            prefs.edit().putBoolean(MusicSpectrum.ENABLED, enabled).apply();
            return;
        }
        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, SPECTRUM_PERMISSION);
    }

    private boolean notificationsEnabled() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    private void openNotificationAccess() {
        String permission = android.Manifest.permission.POST_NOTIFICATIONS;
        if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled()
                && (!prefs.getBoolean("notificationPermissionRequested", false)
                || shouldShowRequestPermissionRationale(permission))) {
            prefs.edit().putBoolean("notificationPermissionRequested", true).apply();
            requestPermissions(new String[]{permission}, NOTIFICATION_PERMISSION);
            return;
        }
        openSystemSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), "此设备未提供应用通知设置");
    }

    private boolean openSystemSettings(Intent intent, String unavailable) {
        try {
            startActivity(intent);
            returningFromComponentSettings = true;
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            message(unavailable);
            return false;
        }
    }

    private String standbySummary() {
        if (standbyError != null) return standbyError;
        if (!standbyEnabled()) return "自动待机已关闭";
        if (!Settings.canDrawOverlays(this)) return "需要悬浮窗授权";
        if (!prefs.getBoolean(StandbyService.CHARGING_ENABLED, false) && !StandbyService.hasRequiredSensors(this)) {
            return "当前没有可用的折叠姿态传感器";
        }
        if (StandbyService.running) return StandbyService.status;
        return "检测未运行，返回本页可恢复";
    }

    private boolean standbyEnabled() {
        return prefs.getBoolean(StandbyService.CHARGING_ENABLED, false)
                || prefs.getBoolean(StandbyService.FOLD_ENABLED, false);
    }

    private void renderStandbySettings(LinearLayout body) {
        LinearLayout card = UiKit.card(this, body, "待机方式", null);
        UiKit.action(this, card, "开始待机", this::launchStandby).setTag("standby:start");
        UiKit.action(this, card, "全屏预览时钟  ›", this::launchFullscreenPreview);

        card = UiKit.card(this, body, "自动待机", null);
        TextView status = UiKit.text(this, standbySummary(), 12, UiKit.MUTED);
        status.setTag("settings:standby-status");
        card.addView(status);
        standbyToggle(card, "充电时自动待机", StandbyService.CHARGING_ENABLED);
        UiKit.note(this, card, "接通电源并锁屏，静置后自动启动。拔电结束充电待机。");
        if (standbyEnabled() && !Settings.canDrawOverlays(this)) {
            UiKit.action(this, card, "去授权悬浮窗  ›", () -> requestStandbyOverlay(null));
        } else if (standbyEnabled() && !StandbyService.running) {
            UiKit.action(this, card, "恢复自动待机检测", () -> { syncStandbyService(); show(); });
        }
        if (!notificationsEnabled()) {
            UiKit.action(this, card, "开启应用通知  ›", this::openNotificationAccess);
        }

        if (StandbyService.hasRequiredSensors(this) || prefs.getBoolean(StandbyService.FOLD_ENABLED, false)) {
            LinearLayout folded = UiKit.card(this, body, "折叠姿态触发", "半折支起设备并锁屏，外屏就绪后启动。");
            standbyToggle(folded, "折叠姿态自动待机", StandbyService.FOLD_ENABLED);
            if (prefs.getBoolean(StandbyService.FOLD_ENABLED, false)) {
                rebuildingToggle(folded, "识别帐篷姿态", "detectTent", true);
                rebuildingToggle(folded, "识别悬停与支架姿态", "detectStand", true);
            }
            UiKit.action(this, folded, "查看设备与检测状态  ›",
                () -> message(Fold.diagnostics(this) + "\n" + standbySummary()));
            if (Fold.supported(this)) UiKit.action(this, folded, "恢复屏幕", () -> {
                try { Fold.release(this); }
                catch (Exception error) { message("恢复失败，请合上后重新展开。"); }
            });
        }
    }

    private void standbyToggle(LinearLayout card, String title, String key) {
        UiKit.toggle(this, card, title, prefs.getBoolean(key, false), enabled -> {
            standbyError = null;
            if (enabled && !Settings.canDrawOverlays(this)) requestStandbyOverlay(key);
            else {
                prefs.edit().putBoolean(key, enabled).apply();
                syncStandbyService();
            }
            show();
        });
    }

    private void requestStandbyOverlay(String key) {
        AppDialogs.builder(this).setTitle("允许在锁屏后启动待机")
                .setMessage("在系统「显示在其他应用上层」中找到时栖时钟并授权，然后返回。")
                .setPositiveButton("去系统授权", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                        pendingStandbyPermission = key;
                    } catch (android.content.ActivityNotFoundException | SecurityException error) {
                        standbyError = "此设备未提供悬浮窗授权页面，可手动开始待机";
                        updateSettingsStatus();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void syncStandbyService() {
        if (testMode) return;
        Intent service = new Intent(this, StandbyService.class);
        if (!standbyEnabled() || !Settings.canDrawOverlays(this)) {
            stopService(service);
        } else {
            try {
                startForegroundService(service);
                standbyError = null;
            } catch (RuntimeException error) {
                standbyError = "系统暂未允许启动检测，可手动开始待机";
            }
        }
    }

    private void updateSettingsStatus() {
        if (getWindow() == null) return;
        View decor = getWindow().getDecorView();
        TextView weather = decor.findViewWithTag("settings:weather-status");
        if (weather != null) setStatusText(weather, weatherSummary());
        TextView refresh = decor.findViewWithTag("settings:weather-refresh");
        if (refresh != null) setWeatherRefreshState(refresh);
        TextView standby = decor.findViewWithTag("settings:standby-status");
        TextView summary = decor.findViewWithTag("settings:standby-summary");
        if (standby != null || summary != null) {
            String status = standbySummary();
            if (standby != null) setStatusText(standby, status);
            if (summary != null) setStatusText(summary, status);
        }
    }

    private void setStatusText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private void setWeatherRefreshState(TextView refresh) {
        refresh.setEnabled(!DeskData.weatherBusy);
        refresh.setAlpha(DeskData.weatherBusy ? .55f : 1f);
    }

    private List<String> savedSceneNames() {
        List<String> names = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("scene_saved_")) names.add(key.substring(12));
        }
        Collections.sort(names);
        return names;
    }

    private void renderSceneSettings(LinearLayout body) {
        LinearLayout current = UiKit.card(this, body, draft.name, "当前场景 · 修改自动保存");
        UiKit.action(this, current, "保存当前快照  ›", () -> input("快照名称", draft.name, this::saveNamedScene));
        UiKit.action(this, current, "重命名当前场景  ›", () -> input("场景名称", draft.name, value -> {
            draft.name = value;
            changed();
            show();
        }));

        LinearLayout saved = UiKit.card(this, body, "已保存的快照", "快照包含页面、配色和待办。载入后会替换当前场景。");
        List<String> names = savedSceneNames();
        if (names.isEmpty()) UiKit.note(this, saved, "暂无快照");
        for (String name : names) UiKit.action(this, saved, name + "  ›", () -> showSnapshotActions(name));

        List<String> legacy = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("saved_")) legacy.add(key.substring(6));
        }
        Collections.sort(legacy);
        if (!legacy.isEmpty()) {
            LinearLayout old = UiKit.card(this, body, "旧版搭配", null);
            for (String name : legacy) UiKit.action(this, old, name + "  ›", () -> {
                confirmSceneReplacement("载入旧版搭配「" + name + "」？", "载入", () -> {
                    SharedPreferences migration = getSharedPreferences("legacy_import", 0);
                    migration.edit().clear().apply();
                    Dashboard.restore(migration, prefs.getString("saved_" + name, ""));
                    Scene scene = Scene.load(migration);
                    scene.name = name;
                    replaceScene(scene, "已载入旧版搭配");
                });
            });
        }

        LinearLayout recovery = UiKit.card(this, body, "恢复", null);
        UiKit.action(this, recovery, "找回场景或快照  ›", this::showRecoveryPoints);
        UiKit.dangerAction(this, recovery, "恢复默认场景  ›", () ->
            confirmSceneReplacement("恢复默认场景？", "恢复默认", () -> {
                SharedPreferences defaults = getSharedPreferences("default_template", 0);
                defaults.edit().clear().apply();
                replaceScene(Scene.load(defaults), "已恢复默认场景");
            }));
    }

    private void confirmSceneReplacement(String title, String action, Runnable confirmed) {
        AppDialogs.builder(this).setTitle(title)
            .setMessage("将替换当前场景「" + draft.name + "」。应用会先保留恢复点，已保存快照和全局设置会保留。")
            .setPositiveButton(action, (d, n) -> confirmed.run()).setNegativeButton("取消", null).show();
    }

    private void showSnapshotActions(String name) {
        AppDialogs.builder(this).setTitle(name)
            .setItems(new String[] { "载入为当前场景", "删除此快照" }, (d, n) -> {
                if (n == 0) confirmSceneReplacement("载入「" + name + "」？", "载入", () -> {
                    Scene scene = Scene.parse(prefs.getString("scene_saved_" + name, ""));
                    if (scene == null) message("此快照无法读取。");
                    else replaceScene(scene, "已载入快照");
                });
                else {
                    saveRecoveryPoint("删除快照「" + name + "」前");
                    prefs.edit().remove("scene_saved_" + name).apply();
                    changed();
                    show();
                    showNotice("已删除快照");
                }
            }).setNegativeButton("取消", null).show();
    }

    private void replaceScene(Scene scene, String notice) {
        saveRecoveryPoint("替换「" + draft.name + "」前");
        if (scene.ownNightColors == null) scene.ownNightColors = prefs.getBoolean("nightOwnColors", false);
        draft = scene;
        pageId = scene.home;
        slot = 0;
        libraryType = component().type;
        changed();
        show();
        showNotice(notice);
    }

    private void renderAboutSettings(LinearLayout body) {
        LinearLayout info = UiKit.card(this, body, getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
            "桌面时钟 · 无账号、广告或遥测");
        UiKit.note(this, info, "天气数据来自 Open-Meteo。可选联网歌词来自 LRCLIB。外屏控制使用 fold-toggle（MIT）。");
    }

    private void prefToggle(LinearLayout host, String label, String key, boolean fallback) {
        UiKit.toggle(this, host, label, prefs.getBoolean(key, fallback), value -> prefs.edit().putBoolean(key, value).apply());
    }

    private void rebuildingToggle(LinearLayout host, String label, String key, boolean fallback) {
        UiKit.toggle(this, host, label, prefs.getBoolean(key, fallback), value -> {
            prefs.edit().putBoolean(key, value).apply();
            show();
        });
    }

    private void prefChoice(LinearLayout host, String label, String[] names, int[] values, String key, int fallback) {
        int at = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.getInt(key, fallback)) at = i;
        UiKit.choice(this, host, label, names, at, n -> {
            prefs.edit().putInt(key, values[n]).apply();
            show();
        });
    }

    private java.time.LocalTime nightTime(String key, int fallback) {
        return java.time.LocalTime.of(prefs.getInt(key, fallback), prefs.getInt(key + "Minute", 0));
    }

    private String nightPeriodLabel() {
        java.time.LocalTime start = nightTime("nightStart", 22), end = nightTime("nightEnd", 7);
        if (start.equals(end)) return "全天";
        return start + "–" + (end.isBefore(start) ? "次日 " : "") + end;
    }

    private void showNightPeriod() {
        InputDialog dialog = new InputDialog(this, "夜间时段", "使用 24 小时制。结束早于开始时，持续到次日。");
        int type = InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
        InputDialog.Field start = dialog.field("开始时间", "22:00", nightTime("nightStart", 22).toString(), type);
        InputDialog.Field end = dialog.field("结束时间", "07:00", nightTime("nightEnd", 7).toString(), type);
        dialog.primary("应用", android.view.inputmethod.EditorInfo.IME_ACTION_DONE, () -> {
            java.time.LocalTime from = parseNightTime(start);
            if (from == null) return;
            java.time.LocalTime to = parseNightTime(end);
            if (to == null) return;
            Runnable apply = () -> {
                prefs.edit().putInt("nightStart", from.getHour()).putInt("nightStartMinute", from.getMinute())
                    .putInt("nightEnd", to.getHour()).putInt("nightEndMinute", to.getMinute()).apply();
                dialog.dismiss();
                show();
            };
            if (from.equals(to)) {
                AppDialogs.builder(this).setTitle("全天启用夜间模式？")
                    .setMessage("开始与结束时间相同，夜间亮度及息屏规则将全天生效。")
                    .setPositiveButton("全天启用", (d, n) -> apply.run()).setNegativeButton("继续编辑", null).show();
            } else apply.run();
        });
        openInputDialog(dialog);
    }

    private java.time.LocalTime parseNightTime(InputDialog.Field field) {
        String value = field.value().replace('：', ':');
        if (!value.matches("(?:[01]?[0-9]|2[0-3]):[0-5][0-9]")) {
            field.error("请输入有效时间，例如 22:30");
            return null;
        }
        String[] parts = value.split(":");
        return java.time.LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private JSONObject theme(int scope) {
        boolean night=scope==3||previewNight;
        return scope==0?(night?draft.nightTheme:draft.theme):scope==1?(night?page().nightTheme:page().theme):scope==2?(night?component().nightTheme:component().theme):draft.nightTheme;
    }

    private int resolvedColor(int scope,int role){
        if(role==0)return Color.BLACK;
        String key=Scene.ROLES[role];boolean night=scope==3||previewNight;
        int value=draft.theme.optInt(key,Scene.DEFAULT_COLORS[role]);
        if(night)value=draft.nightTheme.optInt(key,value);
        if(scope==1||scope==2)value=(night?page().nightTheme:page().theme).optInt(key,value);
        if(scope==2)value=(night?component().nightTheme:component().theme).optInt(key,value);return value;
    }

    private void rememberColor(String value) {
        LinkedHashSet<String> colors = new LinkedHashSet<>();
        colors.add(value);
        String previous = prefs.getString("recentColors", "");
        if (!previous.isEmpty()) colors.addAll(Arrays.asList(previous.split(",")));
        ArrayList<String> list = new ArrayList<>(colors);
        if (list.size() > 6) list = new ArrayList<>(list.subList(0, 6));
        prefs.edit().putString("recentColors", TextUtils.join(",", list)).apply();
    }

    private void palette(LinearLayout host, int scope) {
        String key = tab + ":" + pageId + ":" + component().id + ":" + scope + ":" + previewNight;
        if (!key.equals(colorContext)) {
            colorState.hex = null;
            colorContext = key;
        }
        colorEditor = new PaletteEditor(
            this,
            theme(scope),
            role -> resolvedColor(scope, role),
            colorState,
            prefs.getString("recentColors", ""),
            this::changed,
            this::rememberColor,
            scope==2?component():null, scope==3||previewNight, scope==0||scope==3?"恢复默认":"恢复跟随"
        );
        host.addView(colorEditor, new LinearLayout.LayoutParams(-1, -2));
    }

    private void zone(Scene.Component c) {
        InputDialog dialog = new InputDialog(this, "世界时区", "输入城市英文名或时区，点击结果即可选择");
        InputDialog.Field search = dialog.field("搜索时区", "例如 Shanghai / Asia", "", InputType.TYPE_CLASS_TEXT);
        ArrayList<String> all = new ArrayList<>(Arrays.asList(TimeZone.getAvailableIDs()));
        ListView list = dialog.list(new ArrayList<>(all));
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) list.getAdapter();
        list.setOnItemClickListener((p, v, n, id) -> {
            Scene.put(c.options, "zone", adapter.getItem(n));
            changed();
            dialog.dismiss();
            show();
        });
        search.input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                ArrayList<String> matches = new ArrayList<>();
                for (String zone : all) if (zone.toLowerCase(Locale.ROOT).contains(query)) matches.add(zone);
                adapter.setNotifyOnChange(false);
                adapter.clear();
                adapter.addAll(matches);
                adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e) {}
        });
        UiKit.inputAction(search.input, android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        search.input.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        openInputDialog(dialog);
    }

    private void input(String label, String initial, Consumer<String> cb) {
        boolean todo = label.equals("待办内容");
        boolean adding = todo && initial.isEmpty();
        InputDialog dialog = new InputDialog(this,
            todo ? (adding ? "添加待办" : "修改待办") : label,
            todo ? "记下一件要做的事，添加后自动保存" : "起一个便于辨认的名称");
        InputDialog.Field field = dialog.field(label, todo ? "例如：阅读二十分钟" : "请输入" + label, initial,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        dialog.primary(adding ? "添加" : "保存", android.view.inputmethod.EditorInfo.IME_ACTION_DONE, () -> {
            if (!field.require(todo ? "请输入待办内容" : "请输入名称")) return;
            cb.accept(field.value());
            dialog.dismiss();
        });
        openInputDialog(dialog);
    }

    private void openInputDialog(InputDialog dialog) {
        if (inputDialog != null) inputDialog.dismiss();
        inputDialog = dialog;
        dialog.setOnDismissListener(d -> { if (inputDialog == dialog) inputDialog = null; });
        dialog.show();
    }

    private void city() {
        InputDialog dialog = new InputDialog(this, "搜索天气城市", "支持简体、繁体和英文城市名");
        InputDialog.Field search = dialog.field("城市名称", "例如：西雅图 / Seattle", "", InputType.TYPE_CLASS_TEXT);
        dialog.primary("搜索", android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH, () -> {
            if (!search.require("请输入城市名称")) return;
            dialog.dismiss();
            searchCity(search.value());
        });
        openInputDialog(dialog);
    }

    private void searchCity(String query) {
        if (searchDialog != null) searchDialog.dismiss();
        AlertDialog progress = AppDialogs.builder(this)
            .setMessage("正在搜索城市…")
            .setNegativeButton("取消", null)
            .create();
        searchDialog = progress;
        progress.show();
        DeskData.search(query, (cities, error) -> {
            if (!progress.isShowing()) return;
            progress.dismiss();
            if (searchDialog == progress) searchDialog = null;
            if (isFinishing() || isDestroyed()) return;
            if (cities == null || cities.length() == 0) {
                AppDialogs.builder(this)
                    .setMessage(error.isEmpty() ? "未找到城市，可换用其他城市名称、当前位置或经纬度。" : error)
                    .setNeutralButton("使用当前位置", (d, n) -> useCurrentLocation())
                    .setPositiveButton("经纬度设置", (d, n) -> coordinates())
                    .setNegativeButton("重新搜索", (d, n) -> city())
                    .show();
                return;
            }
            String[] names = new String[cities.length()];
            for (int i = 0; i < names.length; i++) {
                names[i] = WeatherCities.label(cities.optJSONObject(i));
            }
            InputDialog choices = new InputDialog(this, "选择城市", "点击对应地区，应用到天气组件");
            ListView list = choices.list(Arrays.asList(names));
            list.setOnItemClickListener((parent, view, n, id) -> {
                JSONObject c = cities.optJSONObject(n);
                setCity(WeatherCities.simplified(c.optString("name")), c.optString("latitude"), c.optString("longitude"));
                choices.dismiss();
            });
            openInputDialog(choices);
        });
    }

    private boolean weatherLocationNeedsSettings() {
        return prefs.getBoolean("weatherLocationAsked", false)
            && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void weatherLocationPermission() {
        if (WeatherLocation.allowed(this) || weatherLocationNeedsSettings()) {
            openAppPermissions();
        } else {
            requestPermissions(new String[] { android.Manifest.permission.ACCESS_COARSE_LOCATION },
                WEATHER_LOCATION_SETTINGS_PERMISSION);
        }
    }

    private void useCurrentLocation() {
        if (!WeatherLocation.allowed(this)) {
            if (weatherLocationNeedsSettings()) {
                AppDialogs.builder(this)
                    .setMessage("定位权限尚未开启，可在应用权限中允许位置信息，或继续搜索城市。")
                    .setPositiveButton("打开应用权限", (d, n) -> retryWeatherOnReturn =
                            openSystemSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())), "此设备未提供应用权限设置"))
                    .setNeutralButton("搜索城市", (d, n) -> city())
                    .setNegativeButton("取消", null)
                    .show();
                return;
            }
            requestPermissions(new String[] { android.Manifest.permission.ACCESS_COARSE_LOCATION }, WEATHER_LOCATION_PERMISSION);
            return;
        }
        prefs.edit().remove("weatherLocationAsked").apply();
        cancelWeatherLocation();
        AlertDialog progress = AppDialogs.builder(this)
            .setMessage("正在获取当前位置…")
            .setNegativeButton("取消", null)
            .create();
        locationDialog = progress;
        progress.setOnDismissListener(d -> {
            if (locationDialog == progress) cancelWeatherLocation();
        });
        progress.show();
        locationRequest = new WeatherLocation.Request(this, (loc, name, error) -> {
            cancelWeatherLocation();
            if (isFinishing() || isDestroyed()) return;
            if (loc != null) {
                setCity(name, String.format(Locale.ROOT, "%.4f", loc.getLatitude()), String.format(Locale.ROOT, "%.4f", loc.getLongitude()));
                Toast.makeText(this, "已定位到 " + name, Toast.LENGTH_SHORT).show();
            } else {
                AppDialogs.builder(this)
                    .setMessage(error)
                    .setPositiveButton(error.contains("系统定位未开启") ? "定位设置" : "重试", (d, n) -> {
                        if (error.contains("系统定位未开启")) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        else useCurrentLocation();
                    })
                    .setNeutralButton("搜索城市", (d, n) -> city())
                    .setNegativeButton("关闭", null)
                    .show();
            }
        });
        locationRequest.start();
    }

    private void cancelWeatherLocation() {
        if (locationRequest != null) locationRequest.cancel();
        locationRequest = null;
        AlertDialog progress = locationDialog;
        locationDialog = null;
        if (progress != null) progress.dismiss();
    }

    private void setCity(String name, String lat, String lon) {
        prefs.edit()
            .putString("city", WeatherCities.simplified(name))
            .putString("lat", lat)
            .putString("lon", lon)
            .remove("weatherCache")
            .remove("weatherAt")
            .apply();
        DeskData.weather = null;
        DeskData.weatherAt = 0;
        DeskData.weatherError = "";
        DeskData.weatherFailures = 0;
        DeskData.attempted = 0;
        if (!testMode) DeskData.refresh(this, true);
        show();
    }

    private void coordinates() {
        InputDialog dialog = new InputDialog(this, "按经纬度设置城市", "为天气地点指定名称与坐标");
        InputDialog.Field name = dialog.field("城市名称", "例如：上海", WeatherCities.simplified(prefs.getString("city", "")), InputType.TYPE_CLASS_TEXT);
        int number = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        InputDialog.Field lat = dialog.field("纬度 · −90～90", "例如：31.23", prefs.getString("lat", ""), number);
        InputDialog.Field lon = dialog.field("经度 · −180～180", "例如：121.47", prefs.getString("lon", ""), number);
        dialog.primary("应用", android.view.inputmethod.EditorInfo.IME_ACTION_DONE, () -> {
            if (!name.require("请输入城市名称") || !validCoordinate(lat, 90, "纬度") || !validCoordinate(lon, 180, "经度")) return;
            setCity(name.value(), lat.value(), lon.value());
            dialog.dismiss();
        });
        openInputDialog(dialog);
    }

    private boolean validCoordinate(InputDialog.Field field, int limit, String label) {
        try {
            double value = Double.parseDouble(field.value());
            if (Double.isFinite(value) && Math.abs(value) <= limit) return true;
        } catch (NumberFormatException ignored) {}
        field.error("请输入 −" + limit + "～" + limit + " 之间的有效" + label);
        return false;
    }

    void onDialogShown(AlertDialog dialog){studioDialog=dialog;fitDialog(dialog);}
    private void fitDialog(Dialog dialog){
        if(dialog.getWindow()==null)return;
        Rect area=dialogSafeBounds();WindowMetrics metrics=getWindowManager().getCurrentWindowMetrics();Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        int width=Math.min(dp(460),Math.max(1,area.width()-dp(20))),height=Math.max(1,area.height()-dp(20));
        View decor=dialog.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST));
        WindowManager.LayoutParams lp=dialog.getWindow().getAttributes();lp.width=width;lp.height=Math.min(height,decor.getMeasuredHeight());lp.gravity=Gravity.TOP|Gravity.LEFT;
        lp.x=area.left-bars.left+(area.width()-width)/2;lp.y=Math.max(0,area.top-bars.top+(area.height()-lp.height)/2);dialog.getWindow().setAttributes(lp);
    }
    void deletedForEditor(String label){showNotice(label);}
    AlertDialog.Builder alertForEditor(){return AppDialogs.builder(this);}

    private void message(String value) {
        AppDialogs.builder(this).setMessage(value).setPositiveButton("好", null).show();
    }

    private void withSavedScene(Runnable action) {
        persistDraft();
        action.run();
    }

    public void onBackPressed() {
        if (navExpanded) {
            navExpanded = false;
            show();
            return;
        }
        if (previewPicker != null) {
            closePreviewPicker();
            return;
        }
        if (tab == 3) {
            backFromSettings();
            return;
        }
        withSavedScene(this::finish);
    }

    protected void onSaveInstanceState(Bundle out) {
        out.putString("pendingStandbyPermission", pendingStandbyPermission);
        out.putString("pendingLyricsKey", pendingLyricsKey);
        out.putString("pendingLyricsTitle", pendingLyricsTitle);
        out.putBoolean("returningFromComponentSettings", returningFromComponentSettings);
        out.putBoolean("retryWeatherOnReturn", retryWeatherOnReturn);
        rememberScroll();
        history.finish();
        out.putInt("studioPanel",studioPanel);
        out.putStringArrayList("undoStates",history.undoStates());out.putStringArrayList("redoStates",history.redoStates());
        out.putBoolean("pageOverview", pageOverview);
        out.putBoolean("navExpanded", navExpanded);
        if (previewPicker != null) out.putString("previewPicker", previewPicker.name());
        out.putString("libraryType", libraryType);
        out.putString("draft", draft.json());
        out.putString("baseline", baseline);
        out.putString("page", pageId);
        out.putInt("tab", tab);
        out.putInt("slot", slot);
        out.putInt("category", category);
        out.putBoolean("settingsDetail", settingsDetail);
        out.putInt("inspectorGroup", inspectorGroup);
        out.putInt("paletteScope", paletteScope);
        out.putInt("colorMode", colorState.mode);
        out.putInt("colorRole", colorState.role);
        out.putString("colorHex", colorState.hex);
        out.putString("colorContext", colorContext);
        out.putBoolean("previewNight", previewNight);
        JSONObject j = new JSONObject();
        for (Map.Entry<String, Integer> e : scrolls.entrySet()) Scene.put(j, e.getKey(), e.getValue());
        out.putString("scrolls", j.toString());
        super.onSaveInstanceState(out);
    }

    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        show();
        if (inputDialog != null) inputDialog.resize();
        if(studioDialog!=null&&studioDialog.isShowing())fitDialog(studioDialog);
    }

    protected void onResume() {
        super.onResume();
        boolean returningFromTodo = todoManagerOpened;
        todoManagerOpened = false;
        if (todoStore != null) {
            TodoReminders.schedule(this);
            for (ClockFace preview : previews) preview.invalidate();
        }
        if (pendingStandbyPermission != null) {
            if (Settings.canDrawOverlays(this)) prefs.edit().putBoolean(pendingStandbyPermission, true).apply();
            else standbyError = "悬浮窗未授权，可手动开始待机";
            pendingStandbyPermission = null;
        }
        if (standbyEnabled() && !StandbyService.running && Settings.canDrawOverlays(this)) syncStandbyService();
        String saved=prefs.getString(Scene.KEY,baseline);Scene restored=Scene.load(prefs);String normalized=restored.json();
        if(todoStore!=null){todoStore.bind(restored);normalized=restored.json();}
        if(!normalized.equals(baseline)){
            String previous=historyState;draft=restored;pageId=page().id;slot=Math.min(slot,page().count()-1);baseline=normalized;
            historyState=captureEditorState();history.record(previous,historyState);show();
        }else if(returningFromTodo)show();
        if(!saved.equals(normalized))prefs.edit().putString(Scene.KEY,normalized).apply();
        if (!testMode) {
            ClockActivity.applyExtraDim(this, prefs, false, 0f);
            AlarmData.observe(this, alarmChanged);
            int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
            if (prefs.getLong("timerBoot", -1) != boot) {
                DeskData.resetFocus(prefs);
                prefs.edit().putLong("timerBoot", boot).apply();
            }
            DeskData.updateSystemState(this, true);
            DeskData.refresh(this, false);
        }
        if ((tab == 3 || returningFromComponentSettings) && inputDialog == null
                && (studioDialog == null || !studioDialog.isShowing())) show();
        returningFromComponentSettings = false;
        if (retryWeatherOnReturn) {
            retryWeatherOnReturn = false;
            if (WeatherLocation.allowed(this)) useCurrentLocation();
        }
        pulseRunning = true;
        handler.removeCallbacks(clockPulse);
        handler.post(clockPulse);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (readStandbyEntry()) show();
    }

    private boolean readStandbyEntry() {
        if (!getIntent().getBooleanExtra("openStandbySettings", false)) return false;
        getIntent().removeExtra("openStandbySettings");
        tab = 3;
        category = STANDBY_SETTINGS;
        settingsDetail = true;
        navExpanded = false;
        return true;
    }

    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == SPECTRUM_PERMISSION) {
            prefs.edit().putBoolean(MusicSpectrum.ENABLED, MusicSpectrum.allowed(this)).apply();
            show();
            return;
        }
        if (request == WEATHER_LOCATION_PERMISSION || request == WEATHER_LOCATION_SETTINGS_PERMISSION) {
            if (results.length == 0) return;
            if (WeatherLocation.allowed(this)) {
                prefs.edit().remove("weatherLocationAsked").apply();
                if (request == WEATHER_LOCATION_PERMISSION) useCurrentLocation();
                else show();
            } else {
                prefs.edit().putBoolean("weatherLocationAsked", true).apply();
                message("未获得定位权限，仍可搜索城市或使用经纬度设置天气地点。");
            }
            return;
        }
        if (!testMode) {
            DeskData.updateSystemState(this, true);
            DeskData.refresh(this, false);
        }
        show();
    }

    protected void onPause() {
        history.finish();
        persistDraft();
        AlarmData.forget(this, alarmChanged);
        cancelWeatherLocation();
        if (searchDialog != null) searchDialog.dismiss();
        searchDialog = null;
        pulseRunning = false;
        handler.removeCallbacks(clockPulse);
        super.onPause();
    }

    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        cancelWeatherLocation();
        if (inputDialog != null) inputDialog.dismiss();
        if (folds != null) folds.stop();
        super.onDestroy();
    }

    void selectForCheck(int tabIndex, int settingsGroup) {
        tab = tabIndex;
        category = settingsGroup;
        settingsDetail = tabIndex == 3;
        studioPanel=tabIndex==0?1:0;
        previewPicker = tabIndex == 0 ? PreviewPicker.STYLE : null;
        navExpanded = false;
    }
    void inspectorForCheck(int group) { inspectorGroup=group;studioPanel=component().type.equals("clock")?(group==2?2:group==3?0:1):(group==1?2:0); }
    void paletteModeForCheck(int mode) { colorState.mode = mode; colorState.hex = null; }
    void libraryForCheck(String type) {
        libraryType = type;
        tab = 0;
        previewPicker = PreviewPicker.STYLE;
    }
    boolean overviewForCheck() { return pageOverview; }
    PaletteEditor colorEditorForCheck() { return colorEditor; }
    WatchCarouselScrollView carouselForCheck() { return carouselScroll; }
    void foldForCheck(Rect bounds) { foldBounds = bounds; }
    void navigationForCheck(boolean expanded) { navExpanded = expanded; }
    void prepareForCheck(Context context) {
        attachBaseContext(context);
        testMode = true;
        prefs = context.getSharedPreferences("ui_checks", 0);
        draft = Scene.load(prefs);
        baseline = draft.json();
        pageId = draft.home;
        historyState = captureEditorState();
    }

    /** Caps the top Portrait Watch Carousel stage height so lower controls and keyboard input stay visible. */
    final class WatchStageContainer extends LinearLayout {
        private final int requestedHeight;
        WatchStageContainer(Context c, int requestedHeightDp) {
            super(c);
            setOrientation(VERTICAL);
            requestedHeight = UiKit.dp(c, requestedHeightDp);
        }
        protected void onMeasure(int w, int h) {
            int maxAvail=MeasureSpec.getSize(h);float density=getResources().getDisplayMetrics().density;
            WindowInsets insets=getRootWindowInsets();boolean keyboard=insets!=null?insets.isVisible(WindowInsets.Type.ime()):imeVisible;
            View tools=findViewWithTag("preview:tools");
            int cap=UiKit.dp(getContext(),AdaptiveLayout.previewHeight(widthDp,Math.round(maxAvail/density),keyboard,getResources().getConfiguration().fontScale*UiKit.customFontScale,previewAspect));
            View header=getChildAt(0);header.measure(w,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            int headerH=header.getMeasuredHeight();
            if(tools!=null){tools.setVisibility(View.VISIBLE);tools.measure(w,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));tools.setVisibility(keyboard||cap<headerH+tools.getMeasuredHeight()+UiKit.dp(getContext(),80)?View.GONE:View.VISIBLE);}
            cap=Math.min(Math.max(cap,headerH+UiKit.dp(getContext(),maxAvail<UiKit.dp(getContext(),300)?24:52)),Math.max(0,maxAvail));
            boolean labels=cap-headerH-(tools!=null&&tools.getVisibility()==View.VISIBLE?tools.getMeasuredHeight():0)>UiKit.dp(getContext(),100);
            if(carouselScroll!=null&&carouselScroll.getChildCount()>0){ViewGroup track=(ViewGroup)carouselScroll.getChildAt(0);for(int i=0;i<track.getChildCount();i++){View child=track.getChildAt(i);if(child instanceof ViewGroup)((ViewGroup)child).getChildAt(0).setVisibility(labels?View.VISIBLE:View.GONE);}}
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY));
        }
    }

    /** Middle-only scrollable smartwatch carousel applying 3D perspective zoom, dim, and snap selection. */
    static final class WatchCarouselScrollView extends ScrollView {
        private final float previewAspect;
        private List<LinearLayout> items = Collections.emptyList();
        private int selectedIndex;
        private java.util.function.IntConsumer onSelectIndex;
        private boolean userDragged, touching, settling, initialCentered, flinging;
        private ValueAnimator snapAnimator;
        private final Runnable settleCheck = () -> snapClosest();
        private java.util.function.BiConsumer<String, Integer> onMove;
        private boolean reordering;
        private float dragY;
        private int dropIndex = -1, dragIndex = -1;
        private float[] reorderOffsets = new float[0];
        private ValueAnimator reorderAnimator;
        private long edgeFrameTime;
        private TextView dragLabel;
        private CharSequence originalDragLabel;
        private final Runnable edgeScroll = new Runnable() {
            public void run() {
                if (!reordering) return;
                int edge = Math.min(UiKit.dp(getContext(), 72), getHeight() / 3);
                float direction = dragY < edge ? -(edge - dragY) / Math.max(1, edge)
                    : dragY > getHeight() - edge ? (dragY - getHeight() + edge) / Math.max(1, edge) : 0;
                long now = SystemClock.uptimeMillis();
                float elapsed = edgeFrameTime == 0 ? 16f : Math.min(32f, now - edgeFrameTime);
                edgeFrameTime = now;
                int step = Math.round(UiKit.dp(getContext(), 760) * elapsed / 1000f * Math.max(-1f, Math.min(1f, direction)));
                if (step != 0) scrollBy(0, step);
                previewDrop(closestIndexForScroll(getScrollY() + dragY - getHeight() / 2f));
                postOnAnimation(this);
            }
        };

        WatchCarouselScrollView(Context c, float previewAspect) {
            super(c);
            this.previewAspect = previewAspect;
        }

        int dropIndexForCheck() { return dropIndex; }
        float reorderOffsetForCheck(int index) { return reorderOffsets[index]; }

        @Override public boolean onDragEvent(DragEvent event) {
            if (!(event.getLocalState() instanceof String)) return false;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    dragIndex = -1;
                    for (int i = 0; i < items.size(); i++) if (("page:" + event.getLocalState()).equals(items.get(i).getTag())) dragIndex = i;
                    if (dragIndex < 0) return false;
                    dragLabel = (TextView) ((ViewGroup) items.get(dragIndex).getChildAt(0)).getChildAt(0);
                    originalDragLabel = dragLabel.getText();
                    reordering = true;
                    reorderOffsets = new float[items.size()];
                    edgeFrameTime = 0;
                    touching = settling = flinging = false;
                    cancelSnap();
                    super.fling(0);
                    removeCallbacks(settleCheck);
                    previewDrop(dragIndex);
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    dragY = event.getY();
                    removeCallbacks(edgeScroll);
                    edgeScroll.run();
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    removeCallbacks(edgeScroll);
                    edgeFrameTime = 0;
                    previewDrop(-1);
                    return true;
                case DragEvent.ACTION_DROP:
                    int target = closestIndexForScroll(getScrollY() + event.getY() - getHeight() / 2f);
                    removeCallbacks(edgeScroll);
                    if (onMove != null) onMove.accept((String) event.getLocalState(), target);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    reordering = false;
                    dropIndex = -1;
                    dragIndex = -1;
                    if (dragLabel != null) dragLabel.setText(originalDragLabel);
                    dragLabel = null;
                    removeCallbacks(edgeScroll);
                    if (reorderAnimator != null) { reorderAnimator.cancel(); reorderAnimator = null; }
                    Arrays.fill(reorderOffsets, 0);
                    refreshPerspective();
                    centerIndex(selectedIndex, true);
                    return true;
                default: return true;
            }
        }

        /** Preview the final order without changing the scene until a successful drop. */
        private void previewDrop(int target) {
            if (dropIndex == target) return;
            dropIndex = target;
            if (dragLabel != null) dragLabel.setText(target < 0 ? originalDragLabel : (target + 1) + "  " + originalDragLabel.toString().replaceFirst("^\\d+\\s+", ""));
            if (reorderAnimator != null) reorderAnimator.cancel();
            float[] start = reorderOffsets.clone(), end = new float[items.size()];
            if (target >= 0 && dragIndex >= 0) {
                for (int i = 0; i < items.size(); i++) {
                    int destination = i;
                    if (i == dragIndex) destination = target;
                    else if (dragIndex < target && i > dragIndex && i <= target) destination = i - 1;
                    else if (dragIndex > target && i >= target && i < dragIndex) destination = i + 1;
                    end[i] = items.get(destination).getTop() - items.get(i).getTop();
                }
            }
            if (!ValueAnimator.areAnimatorsEnabled()) {
                reorderOffsets = end;
                refreshPerspective();
                return;
            }
            reorderAnimator = ValueAnimator.ofFloat(0, 1);
            reorderAnimator.setDuration(180);
            reorderAnimator.setInterpolator(new PathInterpolator(.2f, .7f, .3f, 1f));
            reorderAnimator.addUpdateListener(a -> {
                float progress = (Float) a.getAnimatedValue();
                for (int i = 0; i < end.length; i++) reorderOffsets[i] = start[i] + (end[i] - start[i]) * progress;
                refreshPerspective();
            });
            reorderAnimator.start();
        }

        void bind(List<LinearLayout> itemViews, int selected, java.util.function.IntConsumer selectCallback) {
            cancelSnap();
            items = itemViews;
            reorderOffsets = new float[items.size()];
            selectedIndex = selected;
            onSelectIndex = selectCallback;
            initialCentered = false;
        }

        void setSelectedIndex(int idx, boolean smooth) {
            if (selectedIndex == idx && !smooth) return;
            selectedIndex = idx;
            centerIndex(idx, smooth);
        }

        void refreshPerspective() {
            updatePerspective(getScrollY());
        }

        private void cancelSnap() {
            if (snapAnimator != null) {
                ValueAnimator a = snapAnimator;
                snapAnimator = null;
                a.cancel();
            }
        }

        private static int blendColor(int from, int to, float ratio) {
            float t = Math.max(0f, Math.min(1f, ratio));
            int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
            int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
            int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
            int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
            return Color.argb(a, r, g, b);
        }

        protected void onDetachedFromWindow() {
            reordering = false;
            removeCallbacks(edgeScroll);
            if (reorderAnimator != null) { reorderAnimator.cancel(); reorderAnimator = null; }
            cancelSnap();
            removeCallbacks(settleCheck);
            super.onDetachedFromWindow();
        }

        @Override
        public void computeScroll() {
            if (reordering) return;
            if (snapAnimator != null && snapAnimator.isRunning()) return;
            super.computeScroll();
        }

        @Override
        public void fling(int velocityY) {
            if (items.isEmpty() || getHeight() <= 0) {
                super.fling(velocityY);
                return;
            }
            flinging = true;
            settling = false;
            removeCallbacks(settleCheck);
            int cur = closestIndex();
            float projectedY = getScrollY() + velocityY * 0.18f;
            int targetIdx = closestIndexForScroll(projectedY);
            if (Math.abs(velocityY) > UiKit.dp(getContext(), 220) && targetIdx == cur) {
                targetIdx = Math.max(0, Math.min(items.size() - 1, cur + (velocityY > 0 ? 1 : -1)));
            }
            snapToIndex(targetIdx);
        }

        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            int vh = getMeasuredHeight();
            if (vh > 0 && !items.isEmpty() && getChildCount() > 0) {
                View track = getChildAt(0);
                // Fit the landscape preview to the space between neighboring pages.
                int maxW = Math.max(1, getMeasuredWidth() - track.getPaddingLeft() - track.getPaddingRight() - UiKit.dp(getContext(), 8));
                int labelH = items.get(0).getChildAt(0).getVisibility()==View.GONE?0:items.get(0).getChildAt(0).getMeasuredHeight();
                int cue=items.size()>1?Math.min(UiKit.dp(getContext(),42),Math.max(0,(vh-labelH-UiKit.dp(getContext(),64))/3)):0;
                int previewH = Math.max(1, Math.min(Math.round(maxW / previewAspect), vh - labelH - cue*2 - UiKit.dp(getContext(), 8)));
                int previewW = Math.min(maxW, Math.round(previewH * previewAspect));
                boolean resized = false;
                for (LinearLayout item : items) {
                    View frame = item.getChildAt(1);
                    ViewGroup.LayoutParams lp = frame.getLayoutParams();
                    if (lp.width != previewW || lp.height != previewH) {
                        lp.width = previewW;
                        lp.height = previewH;
                        frame.setLayoutParams(lp);
                        resized = true;
                    }
                }
                if (resized) super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                int firstH = items.get(0).getMeasuredHeight();
                int lastH = items.get(items.size() - 1).getMeasuredHeight();
                int padTop = Math.max(UiKit.dp(getContext(), 4), (vh - firstH) / 2);
                int padBottom = Math.max(UiKit.dp(getContext(), 4), (vh - lastH) / 2);
                if (track.getPaddingTop() != padTop || track.getPaddingBottom() != padBottom) {
                    track.setPadding(track.getPaddingLeft(), padTop, track.getPaddingRight(), padBottom);
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            }
        }

        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if ((!initialCentered || changed && !touching && !reordering && snapAnimator == null) && getHeight() > 0 && !items.isEmpty()) {
                initialCentered = true;
                centerIndex(selectedIndex, false);
            }
            updatePerspective(getScrollY());
        }

        int maxScrollForCheck() {
            return getChildCount() > 0 ? Math.max(0, getChildAt(0).getHeight() - getHeight()) : 0;
        }

        void centerIndex(int idx, boolean smooth) {
            if (idx < 0 || idx >= items.size() || getHeight() <= 0) return;
            View target = items.get(idx);
            int desiredTop = target.getTop() - Math.max(0, (getHeight() - target.getHeight()) / 2);
            int maxScroll = maxScrollForCheck();
            int clamped = Math.max(0, Math.min(maxScroll, desiredTop));
            cancelSnap();
            if (!smooth || Math.abs(clamped - getScrollY()) <= 1 || !ValueAnimator.areAnimatorsEnabled()) {
                scrollTo(0, clamped);
                updatePerspective(clamped);
                return;
            }
            snapAnimator = ValueAnimator.ofInt(getScrollY(), clamped);
            long duration = Math.min(360L, Math.max(240L, Math.round(Math.abs(clamped - getScrollY()) * 1.2f)));
            snapAnimator.setDuration(duration);
            snapAnimator.setInterpolator(new PathInterpolator(0.22f, 0.61f, 0.36f, 1.0f));
            snapAnimator.addUpdateListener(a -> {
                int y = (Integer) a.getAnimatedValue();
                scrollTo(0, y);
                updatePerspective(y);
            });
            snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (snapAnimator == animation) snapAnimator = null;
                }
                public void onAnimationCancel(android.animation.Animator animation) {
                    if (snapAnimator == animation) snapAnimator = null;
                }
            });
            snapAnimator.start();
        }

        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            updatePerspective(t);
            if (!reordering && !touching && settling && (snapAnimator == null || !snapAnimator.isRunning())) {
                removeCallbacks(settleCheck);
                postDelayed(settleCheck, Math.abs(t - oldt) > 4 ? 95 : 55);
            }
        }

        private void updatePerspective(int scrollY) {
            int vh = getHeight();
            if (vh <= 0 || items.isEmpty()) return;
            float vCenter = scrollY + vh * 0.5f;
            for (int index = 0; index < items.size(); index++) {
                LinearLayout child = items.get(index);
                float reorderOffset = index < reorderOffsets.length ? reorderOffsets[index] : 0;
                boolean placeholder = reordering && index == dragIndex && dropIndex >= 0;
                float normRange = Math.max(1f, child.getHeight());
                float cCenter = child.getTop() + reorderOffset + child.getHeight() * 0.5f;
                float dist=(cCenter-vCenter)/normRange;
                if(Math.abs(dist)<.015f)dist=0;
                float focus=CarouselDepth.focus(dist),highlight=focus*focus,scale=CarouselDepth.scale(dist);
                if(child.getWidth()>0)child.setPivotX(child.getWidth()*.5f);
                if(child.getHeight()>0)child.setPivotY(child.getHeight()*.5f);
                child.setScaleX(scale);child.setScaleY(scale);child.setRotationX(CarouselDepth.rotation(dist));
                child.setTranslationY(reorderOffset+CarouselDepth.shift(dist)*child.getHeight());
                child.setTranslationZ(placeholder?UiKit.dp(getContext(),10):focus*UiKit.dp(getContext(),6));
                child.setAlpha(CarouselDepth.alpha(dist)*(reordering&&index==dragIndex?.55f:1f));

                if (child.getChildCount() >= 2) {
                    View row = child.getChildAt(0);
                    if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
                        View labelView = ((ViewGroup) row).getChildAt(0);
                        if (labelView instanceof TextView) {
                            TextView tv = (TextView) labelView;
                            tv.setTextColor(blendColor(UiKit.MUTED, UiKit.ACCENT, highlight));
                            int wantStyle = focus > 0.7f ? Typeface.BOLD : Typeface.NORMAL;
                            if (tv.getTypeface() == null || tv.getTypeface().getStyle() != wantStyle) {
                                tv.setTypeface(null, wantStyle);
                            }
                        }
                    }
                    View frame = child.getChildAt(1);
                    if (frame.getBackground() instanceof GradientDrawable) {
                        GradientDrawable bg = (GradientDrawable) frame.getBackground();
                        bg.setStroke(
                            Math.max(1, UiKit.dp(getContext(), placeholder ? 3f : 1.0f + 1.2f * highlight)),
                            placeholder ? UiKit.ACCENT : blendColor(0xff222d3d, UiKit.ACCENT, highlight)
                        );
                    }
                }
            }
        }

        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touching = true;
                settling = false;
                flinging = false;
                cancelSnap();
                removeCallbacks(settleCheck);
            }
            boolean intercepted = super.onInterceptTouchEvent(ev);
            if (intercepted) {
                touching = true;
                userDragged = true;
                settling = false;
                flinging = false;
                cancelSnap();
                removeCallbacks(settleCheck);
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                touching = false;
            }
            return intercepted;
        }

        public boolean onTouchEvent(MotionEvent ev) {
            if (reordering) return true;
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touching = true;
                settling = false;
                flinging = false;
                cancelSnap();
                removeCallbacks(settleCheck);
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                touching = true;
                userDragged = true;
                settling = false;
                flinging = false;
                cancelSnap();
                removeCallbacks(settleCheck);
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                flinging = false;
            }
            boolean handled = super.onTouchEvent(ev);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                touching = false;
                boolean wasDragged = userDragged;
                userDragged = false;
                if ((wasDragged || ev.getActionMasked() == MotionEvent.ACTION_UP) && !flinging) {
                    settling = false;
                    removeCallbacks(settleCheck);
                    snapClosest();
                }
            }
            return handled;
        }

        int closestIndex() {
            return closestIndexForScroll(getScrollY());
        }

        private int closestIndexForScroll(float scrollY) {
            int vh = getHeight();
            if (vh <= 0 || items.isEmpty()) return selectedIndex;
            int maxScroll = maxScrollForCheck();
            if (scrollY <= 2) return 0;
            if (maxScroll > 0 && scrollY >= maxScroll - 2) return items.size() - 1;
            float vCenter = scrollY + vh * 0.5f;
            int best = selectedIndex;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < items.size(); i++) {
                View child = items.get(i);
                float dist = Math.abs((child.getTop() + child.getHeight() * 0.5f) - vCenter);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
            return best;
        }

        private void snapClosest() {
            if (reordering) return;
            settling = false;
            if (getHeight() <= 0 || items.isEmpty()) return;
            snapToIndex(closestIndex());
        }

        private void snapToIndex(int best) {
            if (best < 0 || best >= items.size() || getHeight() <= 0) return;
            int prev = selectedIndex;
            selectedIndex = best;
            if (best != prev && onSelectIndex != null) {
                onSelectIndex.accept(best);
            }
            centerIndex(best, true);
        }
    }

    final class AspectPreview extends FrameLayout {
        AspectPreview(Context c) { super(c); }
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
            int innerW = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int innerH = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            int cw = Math.min(innerW, Math.round(innerH * previewAspect));
            int ch = Math.min(innerH, Math.round(cw / previewAspect));
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof ClockFace) {
                    ((ClockFace) child).setLayoutTarget(previewWidthDp, previewHeightDp, previewFold);
                }
                child.measure(
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
                );
            }
            setMeasuredDimension(width, height);
        }
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int padL = getPaddingLeft(), padT = getPaddingTop();
            int innerW = Math.max(0, getWidth() - padL - getPaddingRight());
            int innerH = Math.max(0, getHeight() - padT - getPaddingBottom());
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int x = padL + (innerW - child.getMeasuredWidth()) / 2;
                int y = padT + (innerH - child.getMeasuredHeight()) / 2;
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            }
        }
    }

    static final class HsvPicker extends View {
        private final Paint p = new Paint(3);
        private final float[] hsv = new float[3];
        private final java.util.function.IntConsumer callback;
        HsvPicker(Context c, int color, java.util.function.IntConsumer onColor) {
            super(c);
            Color.colorToHSV(color, hsv);
            callback = onColor;
            setContentDescription("调色盘，横向饱和度，纵向明度，底部色相");
        }
        void setColor(int color) { Color.colorToHSV(color, hsv); invalidate(); }
        protected void onDraw(Canvas c) {
            float h = getHeight() - dpLocal(22);
            int hue = Color.HSVToColor(new float[] { hsv[0], 1, 1 });
            p.setShader(new LinearGradient(0, 0, getWidth(), 0, Color.WHITE, hue, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), h, p);
            p.setShader(new LinearGradient(0, 0, 0, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), h, p);
            p.setShader(new LinearGradient(0, 0, getWidth(), 0, new int[] { 0xffff0000, 0xffffff00, 0xff00ff00, 0xff00ffff, 0xff0000ff, 0xffff00ff, 0xffff0000 }, null, Shader.TileMode.CLAMP));
            c.drawRect(0, h + dpLocal(6), getWidth(), getHeight(), p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dpLocal(2));
            p.setColor(Color.WHITE);
            c.drawCircle(hsv[1] * getWidth(), (1 - hsv[2]) * h, dpLocal(5), p);
            c.drawCircle(hsv[0] / 360 * getWidth(), h + dpLocal(14), dpLocal(5), p);
            p.setStyle(Paint.Style.FILL);
        }
        private float dpLocal(int n) { return n * getResources().getDisplayMetrics().density; }
        public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_DOWN){if(getContext() instanceof Activity){View focused=((Activity)getContext()).getCurrentFocus();if(focused instanceof EditText)focused.clearFocus();}UiKit.beginEdit(getContext());}
            if(e.getAction()==MotionEvent.ACTION_CANCEL){UiKit.endEdit(getContext());if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;}
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                float h = getHeight() - dpLocal(22);
                if (e.getY() > h) hsv[0] = Math.max(0, Math.min(359, e.getX() / getWidth() * 360));
                else {
                    hsv[1] = Math.max(0, Math.min(1, e.getX() / getWidth()));
                    hsv[2] = Math.max(0, Math.min(1, 1 - e.getY() / h));
                }
                callback.accept(Color.HSVToColor(hsv));
                invalidate();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                UiKit.endEdit(getContext());if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            }
            return true;
        }
        public boolean performClick() { super.performClick(); return true; }
    }
}
