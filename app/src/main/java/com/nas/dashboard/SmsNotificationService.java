package com.nas.dashboard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.NasBridgeClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground remote messaging keeps archive notifications available outside the UI. */
public final class SmsNotificationService extends Service {
    static final String CHANNEL = "sms_messages_v1";
    private static final String SYNC_CHANNEL = "sms_background_v1";
    private static final int SYNC_ID = 120;
    private static boolean running;
    private static long requestedAt;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::check;
    private ConnectionMonitor monitor;
    private SmsNotificationState state;
    private NasBridgeClient client;
    private boolean destroyed, busy;
    private final ConnectionMonitor.Listener listener = changed -> {
        if (!busy) { main.removeCallbacks(poll); main.post(poll); }
    };

    static void channels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel messages = new NotificationChannel(CHANNEL, "新短信提醒", NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("新短信声音、振动和悬浮通知");
        messages.enableVibration(true);
        messages.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(messages);
        NotificationChannel background = new NotificationChannel(SYNC_CHANNEL, "短信后台连接", NotificationManager.IMPORTANCE_LOW);
        background.setShowBadge(false);
        manager.createNotificationChannel(background);
    }
    static boolean permitted(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    static void resumeIfEnabled(Context context) {
        if (!new SmsNotificationState(context).enabled() || running || !permitted(context)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (requestedAt > 0 && now - requestedAt < 10000) return;
        requestedAt = now;
        try {
            Intent intent = new Intent(context, SmsNotificationService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
        } catch (RuntimeException blocked) {
            requestedAt = 0;
            new SmsNotificationState(context).status("系统限制后台启动，请打开短信提醒设置重新开启");
        }
    }
    static PendingIntent open(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, SmsActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }
    @Override public void onCreate() {
        super.onCreate();
        state = new SmsNotificationState(this);
        channels(this);
        Notification keepAlive = builder(SYNC_CHANNEL).setSmallIcon(R.drawable.ic_tab_notifications)
            .setContentTitle("麻雀会 · 短信提醒已开启").setContentText("正在接收 NAS 转存的新短信")
            .setOngoing(true).setContentIntent(open(this)).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(SYNC_ID, keepAlive, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(SYNC_ID, keepAlive);
        running = true;
        monitor = ((DashboardApplication) getApplication()).monitor();
        monitor.addListener(listener);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (!state.enabled() || !permitted(this)) { stopSelf(); return START_NOT_STICKY; }
        main.removeCallbacks(poll);
        main.post(poll);
        return START_STICKY;
    }
    private void check() {
        if (destroyed || busy || !state.enabled()) return;
        HostRouter.Selection route = monitor.routeForRequests();
        if (route == null) {
            state.status("等待 NAS 连接，恢复后自动接收短信");
            main.postDelayed(poll, 15000);
            return;
        }
        busy = true;
        NasBridgeClient next = new NasBridgeClient();
        client = next;
        worker.execute(() -> {
            try {
                JSONObject result = next.getJson(route::openConnection, "api/sms?limit=100");
                JSONArray items = result.getJSONArray("items");
                main.post(() -> {
                    if (destroyed || route != monitor.routeForRequests() || !state.enabled()) return;
                    JSONArray alerts = state.observe(items, System.currentTimeMillis());
                    List<JSONObject> rows = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject row = items.optJSONObject(i);
                        if (row != null) rows.add(row);
                    }
                    new SmsReadStore(this).observe(rows);
                    for (int i = alerts.length() - 1; i >= 0; i--) postMessage(this, alerts.optJSONObject(i));
                    state.status("后台提醒运行中 · 最近连接 " + java.text.DateFormat.getTimeInstance().format(new java.util.Date()));
                });
            } catch (Exception unavailable) {
                main.post(() -> { if (!destroyed) state.status("NAS 暂不可达，恢复后自动重试"); });
            } finally {
                main.post(() -> {
                    busy = false;
                    if (!destroyed) {
                        main.removeCallbacks(poll);
                        main.postDelayed(poll, 15000);
                    }
                });
            }
        });
    }
    static void postMessage(Context context, JSONObject row) {
        if (row == null || !permitted(context)) return;
        String sender = row.optString("sender", "短信");
        String code = row.optString("verification_code");
        String body = row.optString("body");
        String key = SmsReadStore.key(row);
        boolean otp = SmsNotificationState.validCode(code) && SmsNotificationState.recent(row, System.currentTimeMillis(), 180000);
        if (otp) SmsQuickFillService.offer(row);
        Intent copy = new Intent(context, SmsCopyActivity.class).setData(android.net.Uri.parse("nasbridge://sms/" + android.net.Uri.encode(key)))
            .putExtra("code", code).putExtra("sent_at", row.optString("sent_at")).putExtra("key", key).putExtra("test", row.optBoolean("test"));
        Notification.Builder notification = (Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_tab_notifications)
            .setContentTitle(otp ? (row.optBoolean("test") ? "测试验证码 " : "验证码 ") + code : sender.isEmpty() ? "新短信" : sender)
            .setContentText(otp ? sender + " · " + body : body)
            .setStyle(new Notification.BigTextStyle().bigText(otp ? sender + "\n" + body : body))
            .setCategory(Notification.CATEGORY_MESSAGE).setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(!row.optBoolean("test")).setAutoCancel(true).setContentIntent(open(context))
            .setWhen(SmsNotificationState.sentAt(row)).setShowWhen(true);
        if (otp) {
            notification.addAction(new Notification.Action.Builder(null, "复制验证码",
                PendingIntent.getActivity(context, 0, copy, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        context.getSystemService(NotificationManager.class).notify(key, 121, notification.build());
    }
    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        requestedAt = 0;
        main.removeCallbacksAndMessages(null);
        if (monitor != null) monitor.removeListener(listener);
        if (client != null) client.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
