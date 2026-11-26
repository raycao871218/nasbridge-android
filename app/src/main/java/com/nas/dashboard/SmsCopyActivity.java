package com.nas.dashboard;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.widget.Toast;
import org.json.JSONObject;

/** A visible notification action may write to the clipboard on modern Android. */
public final class SmsCopyActivity extends Activity {
    private boolean copied;
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !copied) {
            copied = true;
            copy();
        }
    }
    private void copy() {
        String code = getIntent().getStringExtra("code");
        JSONObject row = new JSONObject();
        try { row.put("sent_at", getIntent().getStringExtra("sent_at")); } catch (org.json.JSONException ignored) { }
        if (code == null || !SmsNotificationState.validCode(code) || !SmsNotificationState.recent(row, System.currentTimeMillis(), 180000)) {
            Toast.makeText(this, "验证码已超过 3 分钟，请打开短信查看", Toast.LENGTH_LONG).show();
        } else {
            ClipData data = ClipData.newPlainText("验证码", code);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            data.getDescription().setExtras(extras);
            getSystemService(ClipboardManager.class).setPrimaryClip(data);
            Toast.makeText(this, "验证码已复制", Toast.LENGTH_SHORT).show();
            String key = getIntent().getStringExtra("key");
            if (key != null) {
                if (!getIntent().getBooleanExtra("test", false))
                    new SmsReadStore(this).markAllRead(java.util.Collections.singleton(key));
                getSystemService(NotificationManager.class).cancel(key, 121);
            }
        }
        finish();
    }
}
