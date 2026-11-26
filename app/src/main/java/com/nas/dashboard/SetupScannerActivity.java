package com.nas.dashboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.journeyapps.barcodescanner.*;
import com.nas.dashboard.setup.AppSetupConfig;
import com.nas.dashboard.network.CurrentWifi;
import java.util.Collections;

/** Camera frames and QR credentials remain on the phone. Nothing changes before confirmation. */
public final class SetupScannerActivity extends Activity {
    private DecoratedBarcodeView scanner;
    private TextView hint;
    private boolean preview;
    AlertDialog confirmation;
    private AppSetupConfig pendingWifi;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(20)); page.setBackgroundResource(R.drawable.background);
        TextView title = new TextView(this); title.setText("扫码导入配置"); title.setTextSize(24); title.setTextColor(0xffe9eff8); page.addView(title);
        hint = new TextView(this); hint.setText("对准 NAS 后台「App 扫码配置」的二维码。扫码后先预览，确认后才保存。");
        hint.setTextColor(0xff9bb4c7); hint.setTextSize(14); hint.setPadding(0, dp(14), 0, dp(18)); page.addView(hint);
        scanner = new DecoratedBarcodeView(this); scanner.setStatusText("");
        scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE),
            Collections.singletonMap(DecodeHintType.TRY_HARDER, true), "UTF-8", 0));
        page.addView(scanner, new LinearLayout.LayoutParams(-1, 0, 1));
        scanner.getBarcodeView().addStateListener(new CameraPreview.StateListener() {
            public void previewSized() { } public void previewStarted() { } public void previewStopped() { } public void cameraClosed() { }
            public void cameraError(Exception error) { hint.setText("相机无法使用，请检查相机权限后返回重试。"); }
        });
        Button cancel = new Button(this); cancel.setText("返回"); cancel.setTextColor(0xff75f0d2);
        cancel.setOnClickListener(view -> finish()); page.addView(cancel, new LinearLayout.LayoutParams(-1, dp(56)));
        setContentView(page); decode();
        if (!permitted()) requestPermissions(new String[]{Manifest.permission.CAMERA}, 42);
    }
    private boolean permitted() { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private void decode() {
        scanner.decodeSingle(result -> showConfiguration(result.getText()));
    }
    void showConfiguration(String raw) {
            if (preview || isFinishing()) return;
            scanner.pause(); preview = true;
            AppSetupConfig config;
            try { config = AppSetupConfig.parse(raw); }
            catch (Exception error) {
                confirmation = new AlertDialog.Builder(this).setTitle("无法导入")
                    .setMessage("二维码不是受支持的 鸟巢配置，或内容不完整。请在后台重新生成。")
                    .setPositiveButton("重新扫码", (dialog, which) -> retry()).setOnCancelListener(dialog -> retry()).show();
                return;
            }
            if (config.needsWifi()) { pendingWifi = config; resolveWifi(); return; }
            showPreview(config);
    }
    private void resolveWifi() {
        if (pendingWifi == null || isFinishing()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 43);
            return;
        }
        String ssid = CurrentWifi.read(this);
        if (ssid.isEmpty()) { manualWifi(); return; }
        AppSetupConfig resolved = pendingWifi.withWifi(ssid); pendingWifi = null;
        showPreview(resolved);
    }
    private void manualWifi() {
        if (pendingWifi == null || isFinishing()) return;
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("家庭 Wi-Fi 名称");
        input.setSaveEnabled(false);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), 0);
        TextView note = new TextView(this); note.setText("未能读取当前 Wi-Fi。请连接家里的 Wi-Fi、允许精确定位并开启系统定位后重试；也可手动填写名称。");
        form.addView(note); form.addView(input);
        confirmation = new AlertDialog.Builder(this).setTitle("识别家庭 Wi-Fi").setView(form)
            .setPositiveButton("使用填写名称", null).setNeutralButton("重新读取", (d, which) -> resolveWifi())
            .setNegativeButton("取消", (d, which) -> retry()).setOnCancelListener(d -> retry()).create();
        confirmation.show();
        AlertDialog dialog = confirmation;
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                AppSetupConfig resolved = pendingWifi.withManualWifi(input.getText().toString()); pendingWifi = null;
                dialog.dismiss(); showPreview(resolved);
            } catch (IllegalArgumentException error) { input.setError("请输入有效的家庭 Wi-Fi 名称"); }
        });
    }
    private void showPreview(AppSetupConfig config) {
            ScrollView scroll = new ScrollView(this);
            TextView details = new TextView(this); details.setText(config.preview()); details.setTextSize(15);
            details.setPadding(dp(24), dp(12), dp(24), dp(12)); scroll.addView(details);
            confirmation = new AlertDialog.Builder(this).setTitle("确认导入配置").setView(scroll)
                .setPositiveButton("确认导入", (d, which) -> {
                    try {
                        config.apply(this);
                        Toast.makeText(this, "配置已导入", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(this, config.hasPresence() ? PresenceSettingsActivity.class : NetworkSettingsActivity.class));
                        finish();
                    } catch (Exception error) {
                        hint.setText("配置保存失败，请重试。没有启用自动同步。"); retry();
                    }
                }).setNegativeButton("取消", (d, which) -> retry()).setOnCancelListener(d -> retry()).create();
            confirmation.show();
            if (confirmation.getWindow() != null) confirmation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void retry() { pendingWifi = null; preview = false; decode(); if (permitted()) scanner.resume(); }
    @Override protected void onResume() { super.onResume(); if (permitted() && !preview) scanner.resume(); }
    @Override protected void onPause() { scanner.pause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (confirmation != null && confirmation.isShowing()) confirmation.dismiss();
        super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 43) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) resolveWifi();
            else manualWifi();
            return;
        }
        if (code == 42 && permitted()) scanner.resume();
        else hint.setText("未允许相机权限。可返回手动配置，或在系统设置中允许使用相机后重试。");
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
