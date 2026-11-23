package com.foldstandby.clock;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;

public class ClockActivity extends Activity {
    static ClockActivity currentInstance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ClockFace face;
    private ClockStage stage;
    private TextView previewExit;
    private FoldLayoutObserver folds;
    private SharedPreferences prefs;
    private TodoStore todoStore;
    private boolean todoOpened;
    private View todoNotice;
    private final Runnable hideTodoNotice = () -> {
        if (todoNotice != null && stage != null) stage.content.removeView(todoNotice);
        todoNotice = null;
    };
    private Scene scene;
    private int page, automationMode;
    private long automationGeneration;
    private boolean previewMode, sleeping, returnHomeOnWake, receiverRegistered, wasNight, checkMode, resumed;
    private long activeSince, lastInteraction, pageSince;
    private float lastBrightness = -1f;
    private final Runnable clearTurnScreenOn = () -> setTurnScreenOn(false);
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (isAutomatic() && !sleeping) {
                    // Starting the locked activity can briefly change display state.
                    if (automationMode != StandbyService.FOLD || SystemClock.elapsedRealtime() - activeSince >= 1500L) {
                        StandbyService.dismissSession();
                        finish();
                    }
                } else {
                    rest(false);
                }
            } else if (!isFinishing()) {
                if (checkAutomation()) {
                    wake(returnHomeOnWake);
                    returnHomeOnWake = false;
                }
            }
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!checkAutomation() || sleeping || !resumed) return;
            DeskData.refresh(ClockActivity.this, false);
            long now = SystemClock.elapsedRealtime();
            if (DeskData.batteryTempTenths >= 435) {
                StandbyService.suppressedUntil = now + 300000;
                if (isAutomatic()) StandbyService.dismissSession();
                finish();
                return;
            }
            boolean night = face.night();
            if (night != wasNight) {
                wasNight = night;
                lastInteraction = now;
            }
            long viewTime = prefs.getInt("nightViewSeconds", 20) * 1000L;
            boolean nightSleep = !previewMode && night && prefs.getBoolean("nightSleep", true);
            if (nightSleep && now - lastInteraction >= viewTime) {
                rest(true);
                return;
            }
            applyBrightness(night, now);
            boolean autoplay = !previewMode && !night && prefs.getBoolean("autoplay", false);
            long interval = prefs.getInt("pageInterval", 20) * 1000L;
            long autoIdle = Math.max(30000L, interval * 2);
            if (autoplay && now - pageSince >= interval && now - lastInteraction >= autoIdle) {
                if (face.moveSubpage(1)) pageSince = now;
                else if (scene.pages.size() > 1) changePage((page + 1) % scene.pages.size(), 1);
                else if (face.currentSubpage() > 0) {
                    face.restoreSubpage(0);
                    pageSince = now;
                }
            }
            int homeAfter = previewMode ? 0 : prefs.getInt("homeAfter", 60);
            boolean returnHome = !autoplay && homeAfter > 0
                    && (page != scene.homeIndex() || face.currentSubpage() > 0);
            if (returnHome && now - lastInteraction >= homeAfter * 1000L) {
                if (page == scene.homeIndex()) face.restoreSubpage(0);
                else changePage(scene.homeIndex(), 0);
                returnHome = false;
            }
            face.invalidate();
            long delay = face.nextDelayMs();
            if (nightSleep) delay = Math.min(delay, Math.max(100L, viewTime - (now - lastInteraction)));
            if (autoplay && (scene.pages.size() > 1 || face.hasSubpages())) {
                long nextAuto = Math.max(pageSince + interval, lastInteraction + autoIdle) - now;
                delay = Math.min(delay, Math.max(100L, nextAuto));
            }
            if (returnHome) delay = Math.min(delay, Math.max(100L, lastInteraction + homeAfter * 1000L - now));
            handler.removeCallbacks(this);
            handler.postDelayed(this, delay);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged = (p, key) -> refresh();

    static boolean userSessionOpen() {
        ClockActivity activity = currentInstance;
        return activity != null && !activity.checkMode && !activity.isAutomatic() && !activity.isFinishing()
                && activity.resumed;
    }

    static boolean hasStandbySession(int mode, long generation) {
        ClockActivity activity = currentInstance;
        return activity != null && !activity.isFinishing() && activity.automationMode == mode
                && activity.automationGeneration == generation;
    }

    static boolean adoptStandbyMode(int mode, long generation) {
        ClockActivity activity = currentInstance;
        if (activity == null || !activity.isAutomatic() || activity.isFinishing() || activity.sleeping
                || !activity.resumed) return false;
        activity.automationMode = mode;
        activity.automationGeneration = generation;
        activity.getIntent().putExtra(StandbyService.MODE_EXTRA, mode)
                .putExtra(StandbyService.GENERATION_EXTRA, generation);
        activity.refresh();
        return true;
    }

    static void onPostureOrDisplayChanged() {
        ClockActivity activity = currentInstance;
        if (activity != null) activity.handler.post(() -> {
            if (!activity.isFinishing() && activity.checkAutomation()) activity.refresh();
        });
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        checkMode = getIntent().getBooleanExtra("uiCheck", false);
        prefs = getSharedPreferences(checkMode ? "ui_checks" : "clock", MODE_PRIVATE);
        if (!checkMode) todoStore = TodoStore.open(this);
        readLaunch();
        if (!checkAutomation()) return;
        if (saved != null) page = scene.index(saved.getString("page", scene.pages.get(page).id));

        currentInstance = this;
        prefs.registerOnSharedPreferenceChangeListener(preferencesChanged);
        setShowWhenLocked(!previewMode);
        setTurnScreenOn(isAutomatic());
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            insets.hide(WindowInsets.Type.systemBars());
        }

        face = new ClockFace(this, prefs);
        face.externalTick = true;
        face.flipFeedbackEnabled = true;
        face.pageSwipe = direction -> {
            int next = page + direction;
            if (next >= 0 && next < scene.pages.size()) {
                changePage(next, direction);
                if (direction < 0) face.selectLastSubpage();
            }
        };
        face.interaction = () -> {
            if (sleeping) wake(false);
            else {
                lastInteraction = SystemClock.elapsedRealtime();
                refresh();
            }
        };
        bindFace();
        if (saved != null) face.restoreSubpage(saved.getInt("subpage"));
        stage = new ClockStage(this, face);
        folds = new FoldLayoutObserver(this, stage::setFold);
        setContentView(stage);
        updatePreviewExit();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, filter);
        receiverRegistered = true;
    }

    private void readLaunch() {
        automationMode = getIntent().getIntExtra(StandbyService.MODE_EXTRA, StandbyService.MANUAL);
        automationGeneration = getIntent().getLongExtra(StandbyService.GENERATION_EXTRA, 0);
        scene = Scene.parse(getIntent().getStringExtra("scenePreview"));
        previewMode = scene != null;
        if (!previewMode) scene = Scene.load(prefs);
        page = previewMode ? scene.index(getIntent().getStringExtra("previewPage")) : scene.homeIndex();
    }

    private void bindFace() {
        face.bind(scene, page);
        face.todoStore = todoStore;
        face.openTodo = previewMode || todoStore == null ? null : this::openTodos;
        face.toggleTodo = previewMode || todoStore == null ? null : this::toggleTodo;
        face.nightPreview = previewMode && getIntent().hasExtra("previewNight")
                ? getIntent().getBooleanExtra("previewNight", false) : null;
        face.sceneChanged = () -> scene.save(prefs);
        updatePageDescription();
    }

    private void openTodos(Scene.Component component, TodoStore.View view, String task, boolean add) {
        todoOpened = true;
        startActivity(TodoActivity.intent(this, todoStore.listFor(component), view, task, add, true));
    }

    private void toggleTodo(TodoTask task) {
        java.util.List<String> ids = java.util.Collections.singletonList(task.id);
        TodoStore.Action action = task.completed == 0 ? TodoStore.Action.COMPLETE : TodoStore.Action.REOPEN;
        TodoStore.Undo change = todoStore.change(ids, action);
        if (change.count() == 0) return;
        TodoReminders.cancel(this, ids);
        TodoReminders.schedule(this);
        face.invalidate();
        handler.removeCallbacks(hideTodoNotice);
        hideTodoNotice.run();

        LinearLayout notice = UiKit.row(this);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(dp(12), 0, dp(4), 0);
        notice.setBackground(UiKit.surface(this, UiKit.ACTIVE_BG, 12, UiKit.ACTIVE_BORDER, 1));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView message = UiKit.text(this, action.message, 14, UiKit.TEXT);
        message.setPadding(0, 0, dp(12), 0);
        notice.addView(message);
        TextView undo = UiKit.button(this, "撤销", () -> {
            todoStore.undo(change);
            TodoReminders.schedule(this);
            hideTodoNotice.run();
            face.invalidate();
        });
        undo.setMinHeight(dp(48));
        notice.addView(undo);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        position.bottomMargin = dp(8);
        stage.content.addView(notice, position);
        todoNotice = notice;
        handler.postDelayed(hideTodoNotice, 8_000);
    }

    private void updatePreviewExit() {
        if (previewExit != null) {
            stage.content.removeView(previewExit);
            previewExit = null;
        }
        if (!previewMode) return;
        previewExit = UiKit.compactButton(this, "‹ 返回编辑", UiKit.PANEL, UiKit.SELECTOR_BORDER, UiKit.ACCENT, this::finish);
        previewExit.setTag("preview:return");
        previewExit.setContentDescription("退出全屏预览，返回页面编辑");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(14), dp(12), dp(14), 0);
        stage.content.addView(previewExit, lp);
    }

    private void updatePageDescription() {
        face.setContentDescription((page + 1) + "/" + scene.pages.size() + " · " + scene.pages.get(page).name);
    }

    private void changePage(int next, int direction) {
        if (next == page) return;
        page = next;
        pageSince = SystemClock.elapsedRealtime();
        updatePageDescription();
        int style = prefs.getInt("pageMotion", 0);
        face.animatePage(page, direction, direction == 0 && style == 0 ? 1 : style,
                prefs.getInt("pageMotionDuration", 360));
    }

    private boolean isAutomatic() {
        return automationMode != StandbyService.MANUAL;
    }

    private boolean checkAutomation() {
        if (!isAutomatic() || StandbyService.sessionAllowed(automationMode, automationGeneration)) return true;
        finish();
        return false;
    }

    private void wake(boolean resetPage) {
        if (face == null || isFinishing()) return;
        if (!checkMode && !previewMode) StandbyService.leaveRest(this);
        sleeping = false;
        face.setMotionActive(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        long now = SystemClock.elapsedRealtime();
        lastInteraction = now;
        if (activeSince == 0 || resetPage) {
            activeSince = pageSince = now;
        }
        if (resetPage) {
            DeskData.keyguardAt = 0;
            page = previewMode ? scene.index(getIntent().getStringExtra("previewPage")) : scene.homeIndex();
            face.selectPage(page);
            updatePageDescription();
        }
        lastBrightness = -1f;
        refresh();
    }

    private void refresh() {
        handler.removeCallbacks(tick);
        if (resumed && !sleeping && !isFinishing()) handler.post(tick);
    }

    private void rest(boolean returnHome) {
        if (face == null || sleeping) return;
        returnHomeOnWake = returnHome;
        sleeping = true;
        face.setMotionActive(false);
        handler.removeCallbacks(clearTurnScreenOn);
        setTurnScreenOn(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!checkMode && !previewMode) {
            applyExtraDim(this, prefs, false, 0f);
            StandbyService.enterRest(this);
        }
        handler.removeCallbacks(tick);
    }

    private void applyBrightness(boolean night, long now) {
        float rawNight = prefs.getFloat("nightBrightness", .06f);
        float target = night ? Math.max(.0001f, rawNight) : prefs.getFloat("brightness", .25f);
        long elapsed = now - activeSince;
        if (elapsed > 14400000) target = Math.min(target, .05f);
        else if (elapsed > 7200000) target = Math.min(target, .08f);
        if (DeskData.batteryTempTenths >= 400) target = Math.min(target, .05f);
        if (Math.abs(target - lastBrightness) > .0002f) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.screenBrightness = target;
            getWindow().setAttributes(attributes);
            lastBrightness = target;
        }
        float alpha = night && rawNight < .01f ? Math.max(.22f, Math.min(.95f, .15f + rawNight * 85f)) : 1f;
        if (Math.abs(face.getAlpha() - alpha) > .01f) face.setAlpha(alpha);
        if (!checkMode) applyExtraDim(this, prefs, night && prefs.getBoolean("nightExtraDim", true), rawNight);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunch();
        if (!checkAutomation() || face == null) return;
        bindFace();
        updatePreviewExit();
        setShowWhenLocked(!previewMode);
        setTurnScreenOn(isAutomatic());
        handler.removeCallbacks(clearTurnScreenOn);
        handler.postDelayed(clearTurnScreenOn, 1500L);
        wake(true);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onStart() {
        super.onStart();
        if (folds != null && !checkMode) folds.start();
    }

    @Override protected void onResume() {
        super.onResume();
        todoOpened = false;
        if (face == null || !checkAutomation()) return;
        currentInstance = this;
        resumed = true;
        handler.removeCallbacks(clearTurnScreenOn);
        handler.postDelayed(clearTurnScreenOn, 1500L);
        wake(false);
    }

    @Override protected void onPause() {
        resumed = false;
        if (face != null) face.setMotionActive(false);
        if (!checkMode) applyExtraDim(this, prefs, false, 0f);
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        if (folds != null && !checkMode) folds.stop();
        if (isFinishing() || isChangingConfigurations() || checkMode || todoOpened) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (isAutomatic()) {
            KeyguardManager lock = getSystemService(KeyguardManager.class);
            if (checkAutomation() && power != null && power.isInteractive()
                    && (lock == null || !lock.isKeyguardLocked())) {
                StandbyService.dismissSession();
                finish();
            }
        }
        else if (power != null && power.isInteractive()) finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (scene != null && face != null) {
            out.putString("page", scene.pages.get(page).id);
            out.putInt("subpage", face.currentSubpage());
        }
    }

    @Override protected void onDestroy() {
        if (currentInstance == this) {
            currentInstance = null;
            if (sleeping && !checkMode && !previewMode) StandbyService.leaveRest(this);
        }
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(preferencesChanged);
        if (!checkMode) applyExtraDim(this, prefs, false, 0f);
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(screen);
        if (Fold.requestedByApp && isFinishing()) {
            try { Fold.release(this); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!checkMode && isAutomatic()) StandbyService.dismissSession();
        super.onBackPressed();
    }

    boolean restingForCheck() { return sleeping; }
    String stateForCheck() {
        return "night=" + face.night() + ", preview=" + previewMode + ", resumed=" + resumed
                + ", idle=" + (SystemClock.elapsedRealtime() - lastInteraction)
                + ", interactive=" + getSystemService(PowerManager.class).isInteractive();
    }
    static void applyExtraDim(Context c,SharedPreferences prefs,boolean enable,float nightBrightness){
        if(c==null||prefs==null)return;
        if(c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        try{
            ContentResolver cr=c.getContentResolver();
            boolean overridden=prefs.getBoolean("extraDimOverridden",false);
            if(enable){
                if(!overridden){
                    int origAct=android.provider.Settings.Secure.getInt(cr,"reduce_bright_colors_activated",0);
                    int origLvl=android.provider.Settings.Secure.getInt(cr,"reduce_bright_colors_level",38);
                    prefs.edit().putInt("origExtraDimAct",origAct).putInt("origExtraDimLvl",origLvl).putBoolean("extraDimOverridden",true).apply();
                }
                int targetLevel;
                if(nightBrightness<.01f){
                    float t=Math.max(0f,Math.min(1f,(.009f-nightBrightness)/.008f));
                    targetLevel=Math.round(50+t*40);
                }else{
                    targetLevel=Math.max(38,prefs.getInt("origExtraDimLvl",38));
                }
                if(android.provider.Settings.Secure.getInt(cr,"reduce_bright_colors_activated",0)!=1){
                    android.provider.Settings.Secure.putInt(cr,"reduce_bright_colors_activated",1);
                }
                if(android.provider.Settings.Secure.getInt(cr,"reduce_bright_colors_level",-1)!=targetLevel){
                    android.provider.Settings.Secure.putInt(cr,"reduce_bright_colors_level",targetLevel);
                }
            }else if(overridden){
                int origAct=prefs.getInt("origExtraDimAct",0);
                int origLvl=prefs.getInt("origExtraDimLvl",38);
                android.provider.Settings.Secure.putInt(cr,"reduce_bright_colors_activated",origAct);
                android.provider.Settings.Secure.putInt(cr,"reduce_bright_colors_level",origLvl);
                prefs.edit().putBoolean("extraDimOverridden",false).apply();
            }
        }catch(Throwable ignored){}
    }
}
