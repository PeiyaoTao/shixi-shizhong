package com.foldstandby.clock;

import android.app.*;
import android.content.*;
import android.hardware.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.view.Display;

/** Coordinates charging and folded-posture standby without keeping idle devices awake. */
public class StandbyService extends Service implements SensorEventListener {
    static final String CHARGING_ENABLED = "chargingStandbyEnabled";
    static final String FOLD_ENABLED = "foldStandbyEnabled";
    static final String MODE_EXTRA = "standbyMode", GENERATION_EXTRA = "standbyGeneration";
    static final int MANUAL = 0, CHARGING = 1, FOLD = 2;
    static boolean running, active, resting;
    static float angle = Float.NaN, x, y, z;
    static long suppressedUntil;
    static String status = "未启动";
    private static StandbyService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PostureGate posture = new PostureGate();
    private final PostureGate chargeMotion = new PostureGate();
    private final ChargingStandbyGate charge = new ChargingStandbyGate();
    private SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor hinge, motion;
    private PowerManager power;
    private KeyguardManager keyguard;
    private android.media.AudioManager audio;
    private DisplayManager displays;
    private AppOpsManager appOps;
    private PowerManager.WakeLock settlingWake, tentWake;
    private boolean chargeEnabled, foldEnabled, hingeRegistered, motionRegistered;
    private boolean plugged, foldDismissed, receiverRegistered, chargingStill;
    private long settlingUntil, sampleTime, chargeSampleAt, pendingTentWakeUntil, generation;
    private float restAngle;
    private int requestedMode;
    private boolean launchRejected;

    private final Runnable tick = this::evaluate;
    private final Runnable foldChanged = () -> handler.post(this::evaluate);
    private final Runnable batteryChanged = () -> {
        boolean connected = DeskData.batteryPlugged;
        if (connected != plugged) {
            plugged = connected;
            charge.reset();
            chargeMotion.reset();
            chargingStill = false;
            if (plugged && chargeEnabled) beginSettling();
        }
        queueEvaluation();
    };
    private final AppOpsManager.OnOpChangedListener overlayChanged =
            (op, packageName) -> handler.post(this::evaluate);
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                charge.reset();
                foldDismissed = false;
                posture.reset();
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (!resting) beginSettling();
            }
            queueEvaluation();
        }
    };
    private final DisplayManager.DisplayListener display = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) {}
        public void onDisplayRemoved(int id) {}
        public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) queueEvaluation();
        }
    };

    static boolean hasRequiredSensors(Context context) {
        SensorManager manager = context.getSystemService(SensorManager.class);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
                && motionSensor(manager) != null;
    }

    private static Sensor motionSensor(SensorManager manager) {
        if (manager == null) return null;
        Sensor gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        return gravity != null ? gravity : manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    static boolean sessionAllowed(int mode, long generation) {
        StandbyService service = instance;
        return service != null && running && mode == service.requestedMode && generation == service.generation
                && Settings.canDrawOverlays(service) && service.isLocked()
                && (mode == CHARGING ? service.plugged && service.chargeEnabled
                : service.foldEnabled && ScreenIdentity.isPrimaryCover(service));
    }

    static void enterRest(Context context) {
        resting = true;
        if (instance != null) {
            instance.restAngle = angle;
            instance.queueEvaluation();
        }
    }

    static void leaveRest(Context context) {
        if (!resting) return;
        resting = false;
        if (instance != null) instance.queueEvaluation();
    }

    static void dismissSession() {
        StandbyService service = instance;
        if (service == null) return;
        service.charge.dismiss();
        service.foldDismissed = true;
        resting = false;
        service.requestMode(MANUAL);
        service.queueEvaluation();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("clock", 0);
        sensors = getSystemService(SensorManager.class);
        hinge = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        motion = motionSensor(sensors);
        power = getSystemService(PowerManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        audio = getSystemService(android.media.AudioManager.class);
        settlingWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldStandby:settle");
        settlingWake.setReferenceCounted(false);
        if (ScreenIdentity.isVerifiedFold8(Build.MODEL)) {
            tentWake = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "FoldStandby:tent");
            tentWake.setReferenceCounted(false);
        }
        startForeground(1, monitorNotification());
        IntentFilter events = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        events.addAction(Intent.ACTION_SCREEN_ON);
        events.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, events);
        receiverRegistered = true;
        displays = getSystemService(DisplayManager.class);
        if (displays != null) displays.registerDisplayListener(display, handler);
        appOps = getSystemService(AppOpsManager.class);
        if (appOps != null) appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                getPackageName(), overlayChanged);
        DeskData.observeBattery(this, batteryChanged);
        plugged = DeskData.batteryPlugged;
        running = true;
        active = resting = false;
        angle = Float.NaN;
    }

    private Notification monitorNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("monitor", "自动待机", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).putExtra("openStandbySettings", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, StandbyService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "monitor").setSmallIcon(R.drawable.ic_clock)
                .setContentTitle(getString(R.string.standby_notification_title)).setContentText("自动待机已开启 · 点击管理")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "关闭自动待机", stop).build())
                .setOngoing(true).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) {
            prefs.edit().putBoolean(CHARGING_ENABLED, false).putBoolean(FOLD_ENABLED, false).apply();
        }
        boolean nextCharge = prefs.getBoolean(CHARGING_ENABLED, false);
        boolean nextFold = prefs.getBoolean(FOLD_ENABLED, false) && hinge != null && motion != null;
        if (!nextCharge && !nextFold) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (nextCharge != chargeEnabled) {
            charge.reset();
            chargeMotion.reset();
            chargeSampleAt = 0;
            chargingStill = false;
        }
        if (nextFold != foldEnabled) {
            posture.reset();
            foldDismissed = false;
            angle = Float.NaN;
            if (nextFold) {
                hingeRegistered = sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_NORMAL);
                Fold.watch(this, foldChanged);
            } else {
                if (hingeRegistered) sensors.unregisterListener(this, hinge);
                hingeRegistered = false;
                Fold.forgetWatcher(foldChanged);
            }
        }
        chargeEnabled = nextCharge;
        foldEnabled = nextFold;
        beginSettling();
        queueEvaluation();
        return START_NOT_STICKY;
    }

    private void beginSettling() {
        if ((!chargeEnabled || !plugged) && (!foldEnabled || !PostureGate.isPartlyFolded(angle))) return;
        if (charge.dismissed() && (!foldEnabled || foldDismissed)) return;
        // Keep the CPU up briefly so a lock transition can be observed after screen-off.
        long now = SystemClock.elapsedRealtime();
        settlingUntil = now + 12000L;
        if (!settlingWake.isHeld()) settlingWake.acquire(12000L);
    }

    private boolean isLocked() {
        if (keyguard == null) return false;
        return keyguard.isKeyguardLocked() || (!keyguard.isKeyguardSecure()
                && (!power.isInteractive() || requestedMode != MANUAL));
    }

    private void queueEvaluation() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void setMotionListening(boolean needed) {
        if (motion == null || needed == motionRegistered) return;
        if (needed) {
            chargeSampleAt = 0L;
            chargeMotion.reset();
            motionRegistered = sensors.registerListener(this, motion, 200000);
        } else {
            sensors.unregisterListener(this, motion);
            motionRegistered = false;
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        long now = SystemClock.elapsedRealtime();
        if (event.sensor.getType() == Sensor.TYPE_HINGE_ANGLE) {
            float previous = angle;
            angle = event.values[0];
            if (!PostureGate.isPartlyFolded(angle)) {
                foldDismissed = false;
                if (Fold.requestedByApp) Fold.cancelRequest(this);
            }
            if (resting && (!PostureGate.isPartlyFolded(angle) || !Float.isFinite(restAngle)
                    || Math.abs(angle - restAngle) > 12f) && requestedMode == FOLD) {
                requestMode(MANUAL);
                leaveRest(this);
                posture.reset();
            }
            if (tentWake != null && isLocked()
                    && ((previous >= 8f && angle < 5f) || (previous < 5f && angle >= 5f && angle < 90f))) {
                if (now - sampleTime < 1500L) {
                    if (samsungTentOrientation()) tentWake.acquire(2500L);
                } else {
                    pendingTentWakeUntil = now + 900L;
                }
            }
            if (!resting && PostureGate.isPartlyFolded(angle) && isLocked()) beginSettling();
            queueEvaluation();
            return;
        }

        x = event.values[0];
        y = event.values[1];
        z = event.values[2];
        sampleTime = now;
        posture.observe(angle, x, y, z, now);
        chargeMotion.observe(0f, x, y, z, now);
        chargeSampleAt = now;
        if (tentWake != null && now < pendingTentWakeUntil) {
            pendingTentWakeUntil = 0L;
            if (isLocked() && samsungTentOrientation()) tentWake.acquire(2500L);
        }
    }

    private static boolean samsungTentOrientation() {
        return Math.abs(x) > 4f && Math.abs(y) < 3.5f && z < -.5f;
    }

    private void evaluate() {
        if (!running || instance != this) return;
        handler.removeCallbacks(tick);
        long now = SystemClock.elapsedRealtime();
        boolean locked = isLocked();
        boolean allowed = Settings.canDrawOverlays(this);
        if (!allowed) {
            status = "悬浮窗权限未授予或已撤销";
            requestMode(MANUAL);
            stopSelf();
            return;
        }

        boolean quiet = audio == null || audio.getMode() == android.media.AudioManager.MODE_NORMAL;
        boolean safe = quiet && now >= suppressedUntil && DeskData.batteryTempTenths < 435;
        boolean isFolded = PostureGate.isPartlyFolded(angle);
        boolean inUse = ClockActivity.userSessionOpen();
        boolean charging = chargeEnabled && plugged && locked && !charge.dismissed();
        boolean sensingCharge = charging && !chargingStill && !resting && !inUse;
        boolean sensingFold = foldEnabled && locked && !resting && !inUse
                && (isFolded || now < pendingTentWakeUntil);
        setMotionListening(sensingCharge || sensingFold);

        if (!locked) foldDismissed = false;
        boolean still = motion == null || !motionRegistered || chargeMotion.isStill(now);
        // Some devices advertise a motion sensor but provide no samples while the screen is off.
        if (!still && chargeSampleAt == 0 && now >= settlingUntil - 10000L) still = true;
        boolean chargeReady = charge.update(chargeEnabled && plugged, locked, still || chargingStill, now);
        chargingStill = charging && (chargingStill || chargeReady);

        int pose = PostureGate.classify(angle, x, y, z);
        boolean enabledPose = pose == PostureGate.TENT ? prefs.getBoolean("detectTent", true)
                : pose == PostureGate.STAND && prefs.getBoolean("detectStand", true);
        boolean foldReady = !foldDismissed && foldEnabled && locked && isFolded && enabledPose && safe;
        if (!resting || !foldReady) {
            active = posture.update(foldReady && posture.isStill(now), now);
        }
        boolean cover = foldReady && active && ScreenIdentity.isPrimaryCover(this);
        int desired = !safe || inUse ? MANUAL : cover ? FOLD : chargingStill ? CHARGING : MANUAL;
        if (resting) {
            desired = MANUAL;
            if (requestedMode != MANUAL) {
                if (sessionAllowed(requestedMode, generation) && safe) desired = requestedMode;
                else {
                    charge.dismiss();
                    foldDismissed = true;
                }
            }
        }
        requestMode(desired);

        if (launchRejected) status = "系统限制后台唤醒，可手动开始待机";
        else if (resting) status = "已休眠，等待下一次触发";
        else if (requestedMode == FOLD) status = "已在外屏启动待机";
        else if (requestedMode == CHARGING) status = "已在当前屏幕启动充电待机";
        else if (!safe) status = quiet ? "温度保护中，暂缓启动" : "通话期间暂停待机";
        else if (foldReady && active) status = "已识别支撑姿态，等待可用外屏";
        else if (charge.dismissed() && plugged) status = "本次待机已结束，等待重新插电或锁屏";
        else if (charging) status = "已锁屏，等待静置后进入待机";
        else if (chargeEnabled && !plugged && !foldEnabled) status = "等待充电";
        else if (!locked) status = "等待锁屏";
        else if (foldEnabled && !Float.isFinite(angle)) status = "等待铰链传感器数据";
        else if (foldEnabled) status = "等待稳定的折叠支撑姿态";
        else status = "等待触发条件";

        if (resting || desired != MANUAL || now >= settlingUntil) release(settlingWake);
        if (sensingFold || sensingCharge || (charging && !resting && !inUse)
                || (!resting && now < settlingUntil && !locked)
                || (requestedMode != MANUAL && !resting)) {
            handler.postDelayed(tick, desired == MANUAL ? 400L : 1400L);
        }
    }

    private void requestMode(int mode) {
        if (requestedMode == mode) return;
        requestedMode = mode;
        long id = SystemClock.elapsedRealtimeNanos();
        generation = id;
        launchRejected = false;
        if (mode == MANUAL) {
            ClockActivity.onPostureOrDisplayChanged();
            return;
        }
        if (ClockActivity.adoptStandbyMode(mode, id)) return;
        if (mode == FOLD && tentWake != null && !power.isInteractive()) tentWake.acquire(2500L);
        try {
            startActivity(new Intent(this, ClockActivity.class).putExtra(MODE_EXTRA, mode)
                    .putExtra(GENERATION_EXTRA, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            launchRejected = true;
        }
        handler.postDelayed(() -> {
            if (id != generation || ClockActivity.hasStandbySession(mode, id)) return;
            launchRejected = true;
            queueEvaluation();
        }, 4500L);
    }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        out.println("running=" + running + " active=" + active + " resting=" + resting
                + " charging=" + plugged + " requestedMode=" + requestedMode);
        out.println("hinge=" + angle + " gravity=" + x + "," + y + "," + z
                + " posture=" + PostureGate.classify(angle, x, y, z));
        out.println("cover=" + ScreenIdentity.isPrimaryCover(this) + " deviceState=" + Fold.currentState);
        out.println("status=" + status);
    }

    private static void release(PowerManager.WakeLock lock) {
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = active = resting = false;
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        DeskData.forgetBattery(this, batteryChanged);
        Fold.forgetWatcher(foldChanged);
        if (receiverRegistered) unregisterReceiver(screen);
        if (displays != null) displays.unregisterDisplayListener(display);
        if (appOps != null) appOps.stopWatchingMode(overlayChanged);
        if (sensors != null) sensors.unregisterListener(this);
        release(settlingWake);
        release(tentWake);
        ClockActivity.onPostureOrDisplayChanged();
        super.onDestroy();
    }
}
