package com.nas.dashboard;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.Locale;

/** A tap on the OTP overlay writes only to the current, recognized OTP field. */
public final class SmsQuickFillService extends AccessibilityService {
    private static SmsQuickFillService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject message;
    private LinearLayout card;
    private Button fill;
    private TextView target;
    private final Runnable expire = this::dismiss;
    static boolean connected() { return instance != null; }
    static void offer(JSONObject row) {
        SmsQuickFillService service = instance;
        if (service == null) return;
        try {
            JSONObject snapshot = new JSONObject(row.toString());
            service.main.post(() -> service.show(snapshot));
        } catch (org.json.JSONException ignored) { }
    }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (card == null) return;
        if (getSystemService(KeyguardManager.class).isKeyguardLocked() || !fresh()) dismiss();
        else updateTarget();
    }
    @Override public void onInterrupt() { dismiss(); }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        dismiss();
        super.onDestroy();
    }
    static boolean otpLabel(String value) {
        if (value == null) return false;
        String text = value.toLowerCase(Locale.ROOT).replaceAll("[_\\s-]", "");
        if (text.contains("card") || text.contains("cvv") || text.contains("password")) return false;
        return text.contains("验证码") || text.contains("校验码") || text.contains("verificationcode")
            || text.contains("smscode") || text.contains("smsotp") || text.contains("onetimecode")
            || text.matches(".*(?:[/.:]otp|^otp)(?:code)?$");
    }
    private AccessibilityNodeInfo field() {
        AccessibilityNodeInfo root = null;
        // The overlay can become the active touch window while the app keeps input focus.
        for (android.view.accessibility.AccessibilityWindowInfo window : getWindows()) {
            if (window.isFocused() && window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                root = window.getRoot();
                break;
            }
        }
        if (root == null) root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo node;
        try { node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
        finally { root.recycle(); }
        if (node == null) return null;
        boolean otp = node.isEditable() && node.isEnabled() && node.isVisibleToUser() && !node.isPassword()
            && (otpLabel(node.getViewIdResourceName()) || otpLabel(String.valueOf(node.getContentDescription()))
                || (Build.VERSION.SDK_INT >= 26 && otpLabel(String.valueOf(node.getHintText()))));
        if (message != null && node.getMaxTextLength() >= 0
                && node.getMaxTextLength() < message.optString("verification_code").length()) otp = false;
        if (!otp) { node.recycle(); return null; }
        return node;
    }
    private boolean fresh() {
        return message != null && SmsNotificationState.validCode(message.optString("verification_code"))
            && SmsNotificationState.recent(message, System.currentTimeMillis(), 180000);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void show(JSONObject row) {
        dismiss();
        message = row;
        if (!fresh() || getSystemService(KeyguardManager.class).isKeyguardLocked()) { message = null; return; }
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(10));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xfffff3da);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xffd59443);
        card.setBackground(background);
        card.setElevation(dp(10));
        TextView title = new TextView(this);
        title.setText((row.optBoolean("test") ? "测试验证码 " : "验证码 ") + row.optString("verification_code"));
        title.setTextSize(22);
        title.setTextColor(0xff3b3a49);
        card.addView(title);
        TextView sender = new TextView(this);
        sender.setText(row.optString("sender"));
        sender.setTextColor(0xff514d58);
        card.addView(sender);
        target = new TextView(this);
        target.setTextColor(0xff706b75);
        card.addView(target);
        LinearLayout actions = new LinearLayout(this);
        fill = new Button(this);
        fill.setText("填入");
        fill.setOnClickListener(v -> fill());
        actions.addView(fill, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> dismiss());
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(actions);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            Math.min(dp(350), getResources().getDisplayMetrics().widthPixels - dp(24)), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(70);
        try {
            getSystemService(WindowManager.class).addView(card, params);
            updateTarget();
            main.postDelayed(expire, 30000);
        } catch (RuntimeException unavailable) { dismiss(); }
    }
    private void updateTarget() {
        AccessibilityNodeInfo node = field();
        if (fill.isEnabled() != (node != null)) fill.setEnabled(node != null);
        String label = node == null ? "请先点击验证码输入框" : "点击「填入」写入当前验证码输入框";
        if (!label.contentEquals(target.getText())) target.setText(label);
        if (node != null) node.recycle();
    }
    private void fill() {
        if (!fresh() || getSystemService(KeyguardManager.class).isKeyguardLocked()) { dismiss(); return; }
        AccessibilityNodeInfo node = field();
        if (node == null) { updateTarget(); return; }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message.optString("verification_code"));
        boolean success;
        try { success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args); }
        finally { node.recycle(); }
        if (success) {
            Toast.makeText(this, "验证码已填入", Toast.LENGTH_SHORT).show();
            dismiss();
        } else target.setText("当前 App 不支持直接填入，请使用通知中的复制按钮");
    }
    private void dismiss() {
        main.removeCallbacks(expire);
        if (card != null) {
            try { getSystemService(WindowManager.class).removeView(card); } catch (RuntimeException ignored) { }
        }
        card = null;
        message = null;
    }
}
