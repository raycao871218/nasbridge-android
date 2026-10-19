package com.nas.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import com.nas.dashboard.presence.*;

public final class PresenceSettingsActivity extends Activity {
    private PresenceSettings settings;
    private EditText url, entity, token;
    private Switch testMode;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean enabled = settings.enabled(), running = PresenceService.isRunning();
            GeofenceConfig config = GeofenceConfig.cached(PresenceSettingsActivity.this);
            android.content.SharedPreferences geo = GeofenceConfig.prefs(PresenceSettingsActivity.this);
            long synced = geo.getLong("synced_at", 0);
            ((TextView) findViewById(R.id.geofence_status)).setText((config == null ? "尚未取得 NAS 围栏，启用同步后自动读取" : config.description())
                + "\n" + geo.getString("sync_status", "等待同步")
                + (synced == 0 ? "" : " · " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(synced)))
                + "\n" + geo.getString("last_check", "尚无定位记录"));
            testMode.setEnabled(config == null || !config.enabled);
            ((TextView) findViewById(R.id.presence_status)).setText(enabled && !running
                ? "后台未运行，暂时无法自动更新 HA。请检查后台权限后重新启用。\n上次记录：" + settings.currentStatus()
                : settings.currentStatus());
            ((TextView) findViewById(R.id.presence_enabled)).setText(!enabled ? "自动同步：已停止"
                : running ? "自动同步：后台运行中" : "自动同步：已开启，但后台未运行");
            android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
            boolean unlimited = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
            boolean background = Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
            ((TextView) findViewById(R.id.presence_background_status)).setText("后台定位：" + (background ? "始终允许" : "仅使用期间")
                + " · 电池优化：" + (unlimited ? "不限制" : "受系统优化")
                + "\n如后台被清理，请在系统中允许自启动、后台活动，并将电池用量设为不限制。强行停止 App 后需重新打开才能恢复。");
            main.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_presence_settings);
        settings = new PresenceSettings(this);
        url = findViewById(R.id.ha_url);
        entity = findViewById(R.id.ha_entity); token = findViewById(R.id.ha_token);
        testMode = findViewById(R.id.presence_test_mode);
        testMode.setChecked(settings.testMode());
        url.setText(settings.url()); entity.setText(settings.entity());
        if (settings.prefs.contains("token")) token.setHint("已保存令牌，留空保持原值");
        findViewById(R.id.presence_back).setOnClickListener(v -> finish());
        findViewById(R.id.presence_network_settings).setOnClickListener(v ->
            startActivity(new Intent(this, NetworkSettingsActivity.class)));
        findViewById(R.id.presence_start).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.geofence_refresh).setOnClickListener(v -> {
            if (!PresenceService.isRunning()) {
                Toast.makeText(this, "请先启用同步，再读取 NAS 配置", Toast.LENGTH_SHORT).show(); return;
            }
            sendBroadcast(new Intent(this, PresenceReceiver.class).setAction(PresenceService.REFRESH_CONFIG));
            Toast.makeText(this, "正在读取 NAS 围栏配置", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.presence_stop).setOnClickListener(v -> {
            settings.prefs.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, PresenceService.class));
            settings.status("同步已停止，HA 保留最后状态");
        });
        findViewById(R.id.presence_battery_settings).setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
        findViewById(R.id.presence_permissions).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT == 29 && PresenceService.permitted(this)) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 22);
            } else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        });
    }
    private void saveAndStart() {
        try {
            settings.save(url.getText().toString(), entity.getText().toString().trim(), token.getText().toString().trim());
            settings.prefs.edit().putBoolean("test_mode", testMode.isChecked()).apply();
            token.setText(""); token.setHint("已保存令牌，留空保持原值");
        } catch (IllegalArgumentException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        catch (Exception e) { Toast.makeText(this, "令牌加密保存失败，请重新填写令牌", Toast.LENGTH_LONG).show(); return; }
        if (!PresenceService.permitted(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 20);
            return;
        }
        askNotificationAndStart();
    }
    private void askNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
        } else start();
    }
    private void start() {
        try {
            settings.prefs.edit().putBoolean("enabled", true).apply();
            PresenceService.start(this);
        } catch (RuntimeException e) {
            settings.prefs.edit().putBoolean("enabled", false).apply();
            settings.status("启动失败，请检查定位权限后重试");
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 20) {
            if (PresenceService.permitted(this)) askNotificationAndStart();
            else settings.status("需要精确定位权限才能识别家庭 Wi-Fi，请授权后重新启用");
        } else if (code == 21) start();
    }
    @Override public void onResume() {
        super.onResume();
        PresenceService.resumeIfEnabled(this);
        main.post(refresh);
    }
    @Override public void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
