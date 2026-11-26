package com.nas.dashboard;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.nas.dashboard.network.*;
import com.nas.dashboard.presence.*;
import com.nas.dashboard.setup.AppSetupConfig;
import java.util.*;

/** Isolated preview app on a dedicated emulator; uses only fictitious QR credentials. */
public final class SetupInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); int outcome = Activity.RESULT_OK;
        Context c = getTargetContext(); SetupScannerActivity screen = null;
        try {
            if (!c.getPackageName().endsWith(".preview")) throw new AssertionError("Use -PuiPreview for setup tests");
            PresenceSettings settings = new PresenceSettings(c);
            settings.prefs.edit().clear().commit();
            c.getSharedPreferences("network_hosts", Context.MODE_PRIVATE).edit().clear().commit();
            GeofenceConfig.prefs(c).edit().clear().commit();
            Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open("setup-fixture.png"));
            check(bitmap != null, "Backend QR screenshot available");
            int[] pixels = new int[bitmap.getWidth()*bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, true); hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            String raw = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels))), hints).getText();
            bitmap.recycle();
            AppSetupConfig config = AppSetupConfig.parse(raw);
            check("Home Wi-Fi".equals(config.network.ssid), "Backend QR decoded with Android ZXing");
            check(config.hasPresence() && config.geofence != null, "All config groups decoded");
            check(!config.preview().contains("fixture-secret"), "Preview hides token");
            screen = (SetupScannerActivity)startActivitySync(new Intent(c, SetupScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SetupScannerActivity scanner = screen;
            runOnMainSync(() -> scanner.showConfiguration(raw));
            check(scanner.confirmation != null && scanner.confirmation.isShowing(), "Confirmation displayed before changes");
            check(new HostSettings(c).load() == null && !settings.prefs.contains("token"), "Preview does not change config");
            runOnMainSync(() -> scanner.confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            check(new HostSettings(c).load() == null && !settings.prefs.contains("token"), "Cancel preserves config");
            runOnMainSync(() -> {
                scanner.showConfiguration(raw);
                scanner.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (new HostSettings(c).load() == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            check(new HostSettings(c).load() != null, "Confirmation completed configuration import");
            check("Home Wi-Fi".equals(new HostSettings(c).load().ssid), "Network saved after confirmation");
            check("fixture-secret".equals(settings.token()), "HA token decrypts after import");
            check(!settings.prefs.getString("token", "").contains("fixture-secret"), "HA token saved encrypted");
            check(settings.url().equals("https://ha.example.test:8123/"), "HA URL and port imported");
            check(settings.entity().equals("input_boolean.home"), "HA entity imported");
            check(!settings.testMode() && !settings.enabled() && !PresenceService.isRunning(), "Import does not start automatic HA writes");
            check(GeofenceConfig.cached(c) != null, "NAS fence cached for first launch");
            Map<String, ?> before = settings.prefs.getAll();
            try { AppSetupConfig.parse("{\"type\":\"unrelated\"}"); throw new AssertionError("Invalid QR accepted"); }
            catch (IllegalArgumentException | org.json.JSONException expected) { }
            check(before.equals(settings.prefs.getAll()), "Invalid QR leaves saved config unchanged");
            org.json.JSONObject networkOnly = new org.json.JSONObject().put("type", "nasbridge.android.config").put("version", 1)
                .put("network", new org.json.JSONObject().put("ssid", config.network.ssid)
                    .put("local_url", config.network.localBaseUrl).put("public_url", config.network.publicBaseUrl));
            AppSetupConfig minimal = AppSetupConfig.parse(networkOnly.toString());
            runOnMainSync(() -> {try {minimal.apply(c);} catch (Exception e) {throw new RuntimeException(e);} });
            check("fixture-secret".equals(settings.token()), "Network-only import retains HA credentials");
            check(GeofenceConfig.cached(c) == null && !settings.enabled(), "Network-only import clears stale fence and stops sync");
            // An empty SSID is resolved by the phone, then explicitly confirmed like any other import.
            String currentWifi = CurrentWifi.read(c);
            deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (currentWifi.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(200); currentWifi = CurrentWifi.read(c);
            }
            check(!currentWifi.isEmpty(), "Test emulator connected to Wi-Fi with location permission");
            networkOnly.getJSONObject("network").put("ssid", "");
            String autoWifiRaw = networkOnly.toString();
            Map<String, ?> oldHosts = c.getSharedPreferences("network_hosts", Context.MODE_PRIVATE).getAll();
            screen = (SetupScannerActivity)startActivitySync(new Intent(c, SetupScannerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SetupScannerActivity wifiScreen = screen;
            runOnMainSync(() -> wifiScreen.showConfiguration(autoWifiRaw));
            check(wifiScreen.confirmation != null && wifiScreen.confirmation.isShowing(), "Detected Wi-Fi still requires confirmation");
            check(oldHosts.equals(c.getSharedPreferences("network_hosts", Context.MODE_PRIVATE).getAll()), "Automatic read does not save before confirmation");
            runOnMainSync(() -> wifiScreen.confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            check(oldHosts.equals(c.getSharedPreferences("network_hosts", Context.MODE_PRIVATE).getAll()), "Cancel auto Wi-Fi preserves saved config");
            runOnMainSync(() -> {wifiScreen.showConfiguration(autoWifiRaw);wifiScreen.confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (!currentWifi.equals(new HostSettings(c).load().ssid) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            check(currentWifi.equals(new HostSettings(c).load().ssid), "Confirmed import saves the phone's actual connected SSID");
            output.putString("stream", "Setup: " + checks + " checks passed (real backend QR, preview/cancel, import and encryption)\n");
        } catch (Throwable error) {
            outcome = Activity.RESULT_CANCELED; output.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (screen != null) { SetupScannerActivity done=screen; runOnMainSync(done::finish); }
        }
        finish(outcome, output);
    }
}
