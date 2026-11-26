package com.nas.dashboard;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SmsNotificationSettingsActivity extends Activity {
    private SmsNotificationState state;
    private TextView status;
    private Button toggle;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = new SmsNotificationState(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.background);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        setContentView(scroll);
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(v -> finish());
        body.addView(back);
        TextView title = new TextView(this);
        title.setText("短信提醒与验证码弹窗");
        title.setTextSize(23);
        title.setTextColor(0xff3b3a49);
        body.addView(title);
        status = new TextView(this);
        status.setTextColor(0xff514d58);
        status.setPadding(0, pad, 0, pad);
        status.setTextSize(15);
        body.addView(status);
        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (state.enabled()) {
                state.enabled(false);
                state.clearCodes();
                stopService(new Intent(this, SmsNotificationService.class));
                render();
            } else if (!SmsNotificationService.permitted(this) && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 301);
            } else enable();
        });
        body.addView(toggle);
        TextView note = new TextView(this);
        note.setTextColor(0xff706b75);
        note.setText("开启后通过后台常驻通知接收 NAS 转存短信，每 15 秒检查一次。首次开启只建立记录，避免历史短信集中提醒。省电、无网络或系统后台限制可能使提醒延迟。");
        note.setPadding(0, pad / 2, 0, pad);
        body.addView(note);
        Button notifications = new Button(this);
        notifications.setText("声音、振动和横幅通知");
        notifications.setOnClickListener(v -> {
            SmsNotificationService.channels(this);
            if (Build.VERSION.SDK_INT >= 26) startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, SmsNotificationService.CHANNEL));
            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())));
        });
        body.addView(notifications);
        Button test = new Button(this);
        test.setText("测试验证码弹窗");
        test.setOnClickListener(v -> {
            if (!SmsNotificationService.permitted(this) && Build.VERSION.SDK_INT >= 33)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 302);
            else testPopup();
        });
        body.addView(test);
        Button quickFill = new Button(this);
        quickFill.setText("开启快捷填入（辅助功能）");
        quickFill.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this).setTitle("开启验证码快捷填入")
                .setMessage("麻雀会需要辅助功能读取当前验证码输入框，并在你点击悬浮卡片的「填入」后写入验证码。请在下一页选择「麻雀会验证码快捷填入」并开启。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置", (dialog, which) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
        });
        body.addView(quickFill);
        Button fillTest = new Button(this);
        fillTest.setText("测试验证码直接填入");
        fillTest.setOnClickListener(v -> startActivity(new Intent(this, SmsFillTestActivity.class)));
        body.addView(fillTest);
        TextView fillNote = new TextView(this);
        fillNote.setTextColor(0xff706b75);
        fillNote.setText("新验证码通过屏幕顶部的悬浮通知弹出，显示验证码、发送人和「复制验证码」按钮。若只有通知栏消息，请在上方系统设置中开启横幅／悬浮通知。免打扰和锁屏隐私设置会影响显示。");
        fillNote.setPadding(0, pad / 2, 0, pad);
        body.addView(fillNote);
        Button battery = new Button(this);
        battery.setText("设置后台电池限制");
        battery.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (android.content.ActivityNotFoundException missing) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())));
            }
        });
        body.addView(battery);
    }
    private void enable() {
        if (!SmsNotificationService.permitted(this)) return;
        state.enabled(true);
        state.status("后台提醒正在连接 NAS…");
        SmsNotificationService.resumeIfEnabled(this);
        render();
    }
    private void render() {
        boolean allowed = getSystemService(NotificationManager.class).areNotificationsEnabled() && SmsNotificationService.permitted(this);
        status.setText(state.enabled() ? (allowed ? state.status() : "通知权限已关闭，请在系统设置允许通知") : "短信提醒尚未开启");
        toggle.setText(state.enabled() ? "停止短信提醒" : "开启短信提醒");
    }
    private void testPopup() {
        SmsNotificationService.channels(this);
        try {
            org.json.JSONObject row = new org.json.JSONObject()
                .put("device_id", "notification-test").put("message_id", "preview")
                .put("sender", "麻雀会弹窗测试").put("body", "这是一条测试通知，验证码 123456。")
                .put("verification_code", "123456").put("test", true)
                .put("sent_at", java.time.Instant.now().toString());
            SmsNotificationService.postMessage(this, row);
        } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
    }
    @Override protected void onResume() { super.onResume(); render(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 301 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) enable();
        if (request == 302 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) testPopup();
    }
}
