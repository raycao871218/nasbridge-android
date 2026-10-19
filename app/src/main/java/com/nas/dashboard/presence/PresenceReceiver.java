package com.nas.dashboard.presence;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

public final class PresenceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("com.nas.dashboard.PRESENCE_TICK".equals(intent.getAction())) { PresenceService.wake(); return; }
        if (PresenceService.REFRESH_CONFIG.equals(intent.getAction())) { PresenceService.refreshConfiguration(); return; }
        PresenceSettings settings = new PresenceSettings(context);
        if ("com.nas.dashboard.STOP_PRESENCE".equals(intent.getAction())) {
            settings.prefs.edit().putBoolean("enabled", false).apply();
            settings.status("同步已停止，HA 保留最后状态");
            context.stopService(new Intent(context, PresenceService.class));
        } else if ((Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) && settings.enabled()) {
            if (!PresenceService.permitted(context) || (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
                settings.status("手机重启或 App 更新后后台尚未恢复，请打开 App；后台自动恢复需要始终允许定位");
                return;
            }
            try { PresenceService.start(context); }
            catch (RuntimeException e) { settings.status("系统限制后台启动，请打开在家状态同步页重新启动"); }
        }
    }
}
