package com.nas.dashboard.presence;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import com.nas.dashboard.PresenceSettingsActivity;
import com.nas.dashboard.R;
import com.nas.dashboard.DashboardApplication;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.NasBridgeClient;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Uses the process-wide NAS route, including its LAN ping result. */
public final class PresenceService extends Service {
    private static final int ID = 104;
    private static volatile boolean running;
    private static PresenceService instance;
    public static final String REFRESH_CONFIG = "com.nas.dashboard.REFRESH_GEOFENCE";
    public static void refreshConfiguration() {
        PresenceService service = instance;
        if (service != null) service.main.post(() -> {
            service.nextConfigAt = 0;
            service.monitor.refreshRouting(); service.later(0);
        });
    }
    public static void wake() {
        PresenceService service = instance;
        if (service == null || service.destroyed) return;
        service.alarmAt = 0;
        service.wakeLock.acquire(45000);
        service.monitor.refreshRouting();
        service.later(0);
    }
    private static long startRequestedAt;

    public static boolean isRunning() { return running; }

    /** Called only from an Activity that has resumed, where Android permits an FGS start. */
    public static void resumeIfEnabled(Context context) {
        PresenceSettings settings = new PresenceSettings(context);
        if (!settings.enabled() || running || !permitted(context)) return;
        long now = SystemClock.elapsedRealtime();
        if (startRequestedAt != 0 && now - startRequestedAt < 10000) return;
        startRequestedAt = now;
        settings.status("后台同步正在恢复，正在重新判断内外网…");
        try { start(context); }
        catch (RuntimeException e) {
            startRequestedAt = 0;
            settings.status("后台恢复失败，请检查定位权限和系统后台限制后重试");
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PresenceSettings settings;
    private ConnectivityManager connectivity;
    private ConnectionMonitor monitor;
    private boolean registered, receiving, busy;
    private final ConnectionMonitor.Listener routeListener = state -> changed();
    private final Runnable refreshRoute = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            monitor.refreshRouting();
            main.postDelayed(this, 60000);
        }
    };
    private volatile boolean destroyed;
    private volatile int generation;
    private PresencePolicy.State candidate = PresencePolicy.State.UNKNOWN;
    private long nextSyncAt;
    private final GeofencePolicy.Window window = new GeofencePolicy.Window();
    private GeofenceConfig geofence;
    private GeofenceLocator locator;
    private final ExecutorService configWorker = Executors.newSingleThreadExecutor();
    private boolean fetching;
    private long nextConfigAt, alarmAt;
    private String locationResult = "尚未检测";
    private PendingIntent tick;
    private PowerManager.WakeLock wakeLock;
    private void alarm(long delay) {
        long at = SystemClock.elapsedRealtime() + Math.max(1000, delay);
        if (alarmAt > 0 && alarmAt <= at) return;
        alarmAt = at;
        getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tick);
    }
    private void cancelAlarm() { getSystemService(AlarmManager.class).cancel(tick); alarmAt = 0; }
    private void refreshConfig() {
        HostRouter.Selection route = monitor.selectedRoute();
        if (fetching || route == null || route.checking || !online() || SystemClock.elapsedRealtime() < nextConfigAt) return;
        fetching = true;
        configWorker.execute(() -> {
            GeofenceConfig received = null;
            try { received = GeofenceConfig.parse(new NasBridgeClient().getJson(route::openConnection, "api/geofence/config").toString()); }
            catch (Exception ignored) { }
            GeofenceConfig result = received;
            main.post(() -> {
                fetching = false;
                if (destroyed) return;
                HostRouter.Selection current = monitor.selectedRoute();
                if (current == null || !current.baseUrl.equals(route.baseUrl)) { nextConfigAt = 0; later(0); return; }
                nextConfigAt = SystemClock.elapsedRealtime() + (result == null ? 60000 : 300000);
                GeofenceConfig.prefs(this).edit().putString("sync_status", result == null
                    ? "同步失败，保留上次有效配置" : "已从 NAS 同步").apply();
                if (result != null) {
                    result.save(this);
                    if (geofence == null || !result.source.equals(geofence.source)) {
                        generation++; locator.cancel(); window.reset(); nextSyncAt = 0;
                        locationResult = "配置已更新，等待检测";
                        GeofenceConfig.prefs(this).edit().remove("last_check").remove("last_check_at")
                            .remove("last_check_number").apply();
                    }
                    geofence = result;
                }
                later(0);
            });
        });
    }
    private final Runnable evaluate = this::evaluate;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            monitor.refreshRouting(); later(0);
        }
    };
    public static void start(Context c) {
        Intent i = new Intent(c, PresenceService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    public static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    @Override public void onCreate() {
        super.onCreate();
        settings = new PresenceSettings(this);
        instance = this;
        geofence = GeofenceConfig.cached(this);
        locator = new GeofenceLocator(this);
        tick = PendingIntent.getBroadcast(this, 1, new Intent(this, PresenceReceiver.class)
            .setAction("com.nas.dashboard.PRESENCE_TICK"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NASBridge:geofence");
        wakeLock.setReferenceCounted(false);
        connectivity = getSystemService(ConnectivityManager.class);
        monitor = ((DashboardApplication) getApplication()).monitor();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("presence", "在家状态同步", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!settings.enabled() || !permitted(this)) { stopSelf(); return START_NOT_STICKY; }
        generation++;
        nextSyncAt = 0;
        try {
            startForeground(ID, notification("正在判断内外网…"));
            running = true;
            startRequestedAt = 0;
            if (!registered) {
                monitor.addListener(routeListener);
                registered = true;
            }
            if (!receiving) {
                IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                filter.addAction(LocationManager.MODE_CHANGED_ACTION);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(receiver, filter);
                receiving = true;
            }
            main.removeCallbacks(refreshRoute);
            main.post(refreshRoute);
            return START_STICKY;
        } catch (RuntimeException e) {
            running = false;
            startRequestedAt = 0;
            settings.status("无法启动后台检测，请从设置页重新启用并检查定位权限");
            stopSelf(); return START_NOT_STICKY;
        }
    }
    private Notification notification(String text) { return notification(text, 0); }
    private Notification notification(String text, long remaining) {
        Intent open = new Intent(this, PresenceSettingsActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, PresenceReceiver.class).setAction("com.nas.dashboard.STOP_PRESENCE");
        PendingIntent action = PendingIntent.getBroadcast(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "presence") : new Notification.Builder(this);
        if (remaining > 0 && Build.VERSION.SDK_INT >= 24) {
            b.setUsesChronometer(true).setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + remaining).setShowWhen(true);
        }
        return b.setSmallIcon(R.drawable.ic_network).setContentTitle(remaining > 0 ? "NASBridge · 离家倒计时" : "NASBridge · 在家状态同步")
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentText(text).setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "停止同步", action).build()).build();
    }
    private void status(String text) {
        if (destroyed) return;
        settings.status(text);
        getSystemService(NotificationManager.class).notify(ID, notification(text));
    }
    private void changed() {
        if (destroyed) return;
        if (detect() != candidate) { generation++; locator.cancel(); }
        later(0);
    }
    private boolean online() { return connectivity.getActiveNetwork() != null; }
    private PresencePolicy.State detect() {
        HostRouter.Selection route = monitor.selectedRoute();
        return PresencePolicy.detectRoute(route != null && !route.baseUrl.isEmpty(),
            route != null && route.checking, route != null && route.local, online());
    }
    private void later(long delay) { main.removeCallbacks(evaluate); main.postDelayed(evaluate, delay); }
    private void evaluate() {
        if (destroyed) return;
        if (!settings.enabled()) { stopSelf(); return; }
        refreshConfig();
        PresencePolicy.State next;
        try { next = detect(); }
        catch (RuntimeException e) { next = PresencePolicy.State.UNKNOWN; }
        if (next != candidate) {
            candidate = next; generation++; locator.cancel();
            nextSyncAt = 0;
        }
        HostRouter.Selection route = monitor.selectedRoute();
        boolean selecting = route != null && route.checking && online();
        long now = SystemClock.elapsedRealtime();
        boolean geoEnabled = geofence != null && geofence.enabled;
        long duration = geoEnabled ? geofence.duration : settings.awayDelayMillis();
        window.update(next, selecting, now);
        long remaining = window.remaining(now, duration);
        if (next == PresencePolicy.State.UNKNOWN) {
            if (!selecting) { locator.cancel(); cancelAlarm(); }
            status(selecting ? "正在判断内外网，保留 HA 状态…"
                : "无法判断内外网：请检查网络连接及网络配置；保留 HA 状态");
            later(selecting ? 2000 : 60000); return;
        }
        if (next == PresencePolicy.State.HOME) { locator.cancel(); cancelAlarm(); }
        if (next == PresencePolicy.State.AWAY && geoEnabled && !window.outside()
            && !locator.active() && window.claim(now, geofence.interval, duration)) {
            int request = generation;
            GeofenceConfig config = geofence;
            locationResult = "正在获取位置（第 " + window.checks() + " 次）";
            locator.start(config, location -> {
                if (destroyed || request != generation || detect() != PresencePolicy.State.AWAY) return;
                if (location == null) locationResult = "定位不可用，保留 HA 状态";
                else {
                    double distance = GeofencePolicy.distance(location.getLatitude(), location.getLongitude(), config.latitude, config.longitude);
                    if (GeofencePolicy.outside(distance, config.radius, config.buffer, location.getAccuracy())) {
                        window.markOutside(); locationResult = "已确认在围栏外"; nextSyncAt = 0;
                    } else locationResult = "位于围栏内或边缘，保留 HA 状态";
                }
                GeofenceConfig.prefs(this).edit().putString("last_check", locationResult)
                    .putLong("last_check_at", System.currentTimeMillis()).putLong("last_check_number", window.checks()).apply();
                later(0);
            });
        }
        if (next == PresencePolicy.State.AWAY && !window.outside() && (remaining > 0 || locator.active())) {
            String text = "当前使用外网，持续满 " + (duration / 60000) + " 分钟后确认离家";
            if (geoEnabled) text += "\n" + locationResult + "\n" + (locator.active() ? "本次定位中…"
                : "下次检测：" + DateFormat.getTimeInstance().format(new Date(System.currentTimeMillis() + window.nextIn(now, geofence.interval))));
            settings.countdown(text, remaining);
            getSystemService(NotificationManager.class).notify(ID, notification(settings.currentStatus(), remaining));
            alarm(locator.active() ? 30000 : Math.min(remaining, geoEnabled ? window.nextIn(now, geofence.interval) : remaining));
            later(1000); return;
        }
        cancelAlarm();
        if (busy) { later(2000); return; }
        long untilSync = nextSyncAt - SystemClock.elapsedRealtime();
        if (untilSync > 0) { later(untilSync); return; }
        busy = true;
        int request = generation;
        boolean home = next == PresencePolicy.State.HOME;
        String url = settings.url(), entity = settings.entity();
        status((home ? "在家" : "离家") + " · 正在核对 HA 状态…");
        worker.execute(() -> {
            String result;
            try {
                String token = settings.token();
                HomeAssistantClient client = new HomeAssistantClient();
                String expected = home ? "on" : "off";
                String actual = client.state(url, entity, token);
                if (request != generation || destroyed || !settings.enabled()) returnResult(request, "", false);
                else {
                    if (!expected.equals(actual)) {
                        client.set(url, entity, token, home);
                        if (!expected.equals(client.state(url, entity, token))) throw new java.io.IOException();
                    }
                    result = (home ? "在家" : "离家") + " · HA：" + expected + " · "
                        + DateFormat.getTimeInstance().format(new Date());
                    returnResult(request, result, true);
                }
            } catch (Exception e) {
                // Never display tokens, URLs containing secrets, or HA response bodies.
                result = e instanceof java.io.IOException && e.getMessage() != null && e.getMessage().startsWith("HA HTTP ")
                    ? e.getMessage() : "连接或认证失败";
                returnResult(request, "同步失败（" + result + "），稍后重试", false);
            }
        });
    }
    private void returnResult(int request, String result, boolean success) {
        main.post(() -> {
            busy = false;
            if (destroyed) return;
            if (request != generation) { later(0); return; }
            status(result);
            nextSyncAt = SystemClock.elapsedRealtime() + (success ? 60000 : 15000);
            later(success ? 60000 : 15000);
        });
    }
    @Override public void onDestroy() {
        running = false;
        startRequestedAt = 0;
        destroyed = true; generation++;
        instance = null;
        locator.cancel(); cancelAlarm();
        if (wakeLock.isHeld()) wakeLock.release();
        configWorker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (registered) monitor.removeListener(routeListener);
        if (receiving) unregisterReceiver(receiver);
        worker.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
