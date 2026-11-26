package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Real editable field for the same accessibility fill path used in other apps. */
public final class SmsFillTestActivity extends Activity {
    private TextView result;
    private EditText input;
    private final Handler main = new Handler();
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundResource(R.drawable.background);
        int pad = Math.round(22 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        setContentView(body);
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(v -> finish());
        body.addView(back);
        TextView title = new TextView(this);
        title.setText("测试验证码直接填入");
        title.setTextColor(0xff3b3a49);
        title.setTextSize(23);
        body.addView(title);
        TextView help = new TextView(this);
        help.setText("点击下方输入框，再发送测试验证码。在弹出的卡片上点击「填入」，这里应出现 123456。");
        help.setTextColor(0xff514d58);
        body.addView(help);
        input = new EditText(this);
        input.setId(R.id.sms_test_otp_input);
        input.setHint("验证码");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(0xff3b3a49);
        body.addView(input);
        result = new TextView(this);
        result.setTextColor(0xff52745f);
        body.addView(result);
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                result.setText("123456".contentEquals(value) ? "测试成功：验证码已实际填入输入框" : "");
            }
            public void afterTextChanged(android.text.Editable value) { }
        });
        Button send = new Button(this);
        send.setText("发送测试验证码");
        body.addView(send);
        send.setOnClickListener(v -> {
            input.setText("");
            input.requestFocus();
            if (!SmsQuickFillService.connected()) {
                result.setText("请先在系统辅助功能中开启「麻雀会验证码快捷填入」，再返回测试。");
                return;
            }
            main.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                try {
                    JSONObject row = new JSONObject().put("device_id", "notification-test").put("message_id", "fill")
                        .put("test", true).put("sender", "麻雀会填入测试").put("body", "测试验证码 123456")
                        .put("verification_code", "123456").put("sent_at", java.time.Instant.now().toString());
                    SmsQuickFillService.offer(row);
                } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }, 400);
        });
        Button settings = new Button(this);
        settings.setText("打开辅助功能设置");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(settings);
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
