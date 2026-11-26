package com.nas.dashboard;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.HostSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;

/** Uses only a separate preview app and a loopback fixture server. */
public final class SmsNotificationInstrumentation extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle args) { super.onCreate(args); options = args; start(); }
    @Override public void onStart() {
        Context context = getTargetContext();
        Bundle result = new Bundle();
        Activity settings = null;
        try {
            if (!context.getPackageName().endsWith(".preview")) throw new AssertionError("Preview package required");
            context.getSharedPreferences("sms_notifications", 0).edit().clear().commit();
            SmsNotificationState state = new SmsNotificationState(context);
            long now = System.currentTimeMillis();
            JSONObject old = new JSONObject().put("device_id", "fixture").put("message_id", "old")
                .put("sender", "NASBridge 测试").put("sent_at", java.time.Instant.ofEpochMilli(now - 120000).toString())
                .put("verification_code", "001234");
            JSONArray baseline = new JSONArray().put(old);
            if (state.observe(baseline, now).length() != 0) throw new AssertionError("Historical messages alerted");
            JSONObject incoming = new JSONObject(old.toString()).put("message_id", "new");
            JSONArray next = new JSONArray().put(incoming).put(old);
            if (state.observe(next, now).length() != 1) throw new AssertionError("New SMS was not detected");
            if (state.observe(next, now).length() != 0) throw new AssertionError("Duplicate notification");
            state.enabled(true);
            if (!SmsNotificationState.recent(incoming, now, 180000)) throw new AssertionError("Fresh OTP rejected");
            if (SmsNotificationState.recent(incoming, now + 61000, 180000)) throw new AssertionError("OTP not expired");
            state.enabled(false);
            context.getSharedPreferences("sms_notifications", 0).edit().clear().commit();
            String base = options == null ? null : options.getString("base");
            if (base != null) {
                if (!base.startsWith("http://127.0.0.1:")) throw new AssertionError("Loopback fixture required");
                runOnMainSync(() -> {
                    new HostSettings(context).save(new HostConfig("sms-preview", base, base));
                    ((DashboardApplication) context.getApplicationContext()).monitor().configurationChanged();
                });
            }
            settings = startActivitySync(new Intent(context, SmsNotificationSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity visible = settings;
            runOnMainSync(() -> {
                if (base == null) clickTestPopup(visible.findViewById(android.R.id.content));
                else {
                    new SmsNotificationState(context).enabled(true);
                    SmsNotificationService.resumeIfEnabled(visible);
                }
            });
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            StatusBarNotification alert = null;
            long end = android.os.SystemClock.elapsedRealtime() + 45000;
            while (android.os.SystemClock.elapsedRealtime() < end && alert == null) {
                for (StatusBarNotification entry : manager.getActiveNotifications()) {
                    if (entry.getId() == 121) alert = entry;
                }
                Thread.sleep(200);
            }
            if (alert == null) throw new AssertionError("Background service did not notify: " + new SmsNotificationState(context).status());
            Notification notification = alert.getNotification();
            if (notification.actions == null || notification.actions.length != 1) throw new AssertionError("Copy action missing");
            if (notification.visibility != Notification.VISIBILITY_PRIVATE) throw new AssertionError("Lockscreen privacy lost");
            if (manager.getNotificationChannel(SmsNotificationService.CHANNEL).getImportance() < NotificationManager.IMPORTANCE_HIGH)
                throw new AssertionError("Message channel is not high importance");
            String expectedTitle = base == null ? "测试验证码 123456" : "验证码 001234";
            if (!expectedTitle.contentEquals(notification.extras.getCharSequence(Notification.EXTRA_TITLE)))
                throw new AssertionError("OTP is not prominent in the popup");
            result.putString("stream", "PASS: background OTP popup content, copy action, importance, duplicate suppression, leading zeros and expiry\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(1, result);
        } finally {
            runOnMainSync(() -> {
                new SmsNotificationState(context).enabled(false);
                context.stopService(new Intent(context, SmsNotificationService.class));
                context.getSystemService(NotificationManager.class).cancelAll();
                context.getSharedPreferences("sms_notifications", 0).edit().clear().commit();
            });
            if (settings != null) {
                Activity activity = settings;
                runOnMainSync(activity::finish);
            }
        }
    }
    private void clickTestPopup(android.view.View view) {
        if (view instanceof android.widget.Button && "测试验证码弹窗".contentEquals(((android.widget.Button) view).getText())) {
            view.performClick();
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clickTestPopup(group.getChildAt(i));
        }
    }
}
