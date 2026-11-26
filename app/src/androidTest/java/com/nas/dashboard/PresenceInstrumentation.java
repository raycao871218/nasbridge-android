package com.nas.dashboard;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import com.nas.dashboard.presence.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Dedicated emulator only: local HA fixture, no real HA access or writes. */
public final class PresenceInstrumentation extends Instrumentation {
    private int checks;
    private void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        PresenceSettings settings = new PresenceSettings(context);
        Map<String, ?> backup = new HashMap<>(settings.prefs.getAll());
        SharedPreferences hosts = context.getSharedPreferences("network_hosts", Context.MODE_PRIVATE);
        Map<String, ?> hostBackup = new HashMap<>(hosts.getAll());
        Activity activity = null;
        Activity homeActivity = null;
        int outcome = Activity.RESULT_OK;
        try (Fixture fixture = new Fixture()) {
            if (!android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.MODEL.contains("sdk"))
                throw new AssertionError("Run only on a dedicated emulator");
            settings.prefs.edit().clear().commit();
            settings.save("https://127.0.0.1:1/", "input_boolean.test_presence", "fixture-token");
            require("fixture-token".equals(settings.token()), "Encrypted token round trip");
            require(!settings.prefs.getString("token", "").contains("fixture-token"), "Token stored encrypted");
            settings.save(settings.url(), settings.entity(), "");
            require("fixture-token".equals(settings.token()), "Blank token preserves previous token");
            for (String url : new String[]{"http://ha.test", "https://user:password@ha.test", "https://ha.test/?token=x", "https://ha.test/#x", "https://ha.test:70000/"}) {
                try { PresenceSettings.validateUrl(url); throw new AssertionError("Accepted " + url); }
                catch (IllegalArgumentException expected) { checks++; }
            }
            require(!settings.testMode() && settings.checkIntervalMillis() == 1_800_000, "Normal mode defaults to thirty minutes");
            settings.prefs.edit().putBoolean("test_mode", false).commit();
            require(settings.checkIntervalMillis() == 1_800_000, "Normal interval is thirty minutes");
            settings.prefs.edit().putBoolean("test_mode", true).commit();
            HomeAssistantClient client = new HomeAssistantClient();
            String base = "http://127.0.0.1:" + fixture.server.getLocalPort() + "/";
            require("off".equals(client.state(base, "input_boolean.test_presence", "fixture-token")), "Read initial off");
            client.set(base, "input_boolean.test_presence", "fixture-token", true);
            require("on".equals(client.state(base, "input_boolean.test_presence", "fixture-token")), "turn_on");
            client.set(base, "input_boolean.test_presence", "fixture-token", false);
            require("off".equals(client.state(base, "input_boolean.test_presence", "fixture-token")), "turn_off");
            require(fixture.posts == 2 && fixture.validBody, "Exact service payload and methods");
            try { client.state(base, "input_boolean.test_presence", "invalid"); throw new AssertionError("Accepted invalid token"); }
            catch (IOException expected) { require("HA HTTP 401".equals(expected.getMessage()), "Auth failure reported safely"); }
            fixture.redirect = true;
            try { client.state(base, "input_boolean.test_presence", "fixture-token"); throw new AssertionError("Followed redirect"); }
            catch (IOException expected) { require("HA HTTP 302".equals(expected.getMessage()), "Redirect blocked"); }
            fixture.redirect = false;
            Intent launch = new Intent(context, PresenceSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            AtomicReference<String> text = new AtomicReference<>();
            Activity screen = activity;
            require(screen.findViewById(R.id.presence_network_settings) != null, "Uses shared network settings");
            java.util.concurrent.atomic.AtomicBoolean testMode = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> testMode.set(((android.widget.Switch)screen.findViewById(R.id.presence_test_mode)).isChecked()));
            require(testMode.get(), "Test mode switch reflects saved setting");
            runOnMainSync(() -> text.set(((android.widget.EditText)screen.findViewById(R.id.ha_token)).getText().toString()));
            require(text.get().isEmpty(), "Token never revealed on screen");
            shell("pm grant com.nas.dashboard android.permission.ACCESS_COARSE_LOCATION");
            shell("pm grant com.nas.dashboard android.permission.ACCESS_FINE_LOCATION");
            shell("pm grant com.nas.dashboard android.permission.POST_NOTIFICATIONS");
            new com.nas.dashboard.network.HostSettings(context).save(new com.nas.dashboard.network.HostConfig(
                "presence-fixture-never-matches", "https://127.0.0.1:1/", "https://127.0.0.1:2/"));
            runOnMainSync(() -> ((DashboardApplication) context.getApplicationContext()).monitor().configurationChanged());
            runOnMainSync(() -> screen.findViewById(R.id.presence_start).performClick());
            waitStatus(settings, "尚未启用 NAS 围栏");
            require(screen.findViewById(R.id.presence_test_now) != null, "Immediate test button available");
            require(settings.enabled(), "Foreground service enabled from settings");
            runOnMainSync(() -> screen.moveTaskToBack(true));
            require(notificationText(context).contains("保留原状态"), "Missing fence preserves state without departure timeout");
            runOnMainSync(() -> ((android.widget.Switch)screen.findViewById(R.id.presence_test_mode)).setChecked(false));
            require(settings.checkIntervalMillis() == 1_800_000, "Switch immediately restores normal interval");
            runOnMainSync(() -> ((android.widget.Switch)screen.findViewById(R.id.presence_test_mode)).setChecked(true));
            require(settings.checkIntervalMillis() == 60_000, "Switch immediately enables one-minute checks");
            boolean foreground = false;
            for (ActivityManager.RunningServiceInfo info : context.getSystemService(ActivityManager.class).getRunningServices(100)) {
                if (info.service.getClassName().equals(PresenceService.class.getName())) foreground = info.foreground;
            }
            require(foreground, "Location foreground service is running");
            AtomicReference<android.graphics.Bitmap> rendered = new AtomicReference<>();
            runOnMainSync(() -> {
                android.view.ViewGroup content = screen.findViewById(android.R.id.content);
                android.view.ViewGroup scroll = (android.view.ViewGroup) content.getChildAt(0);
                android.view.View form = scroll.getChildAt(0);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(form.getWidth(), form.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                canvas.drawColor(android.graphics.Color.rgb(8, 22, 36));
                form.draw(canvas);
                rendered.set(bitmap);
            });
            android.graphics.Bitmap screenshot = rendered.get();
            try (OutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "presence-settings.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            screenshot.recycle();
            runOnMainSync(() -> screen.findViewById(R.id.presence_stop).performClick());
            require(!settings.enabled(), "Stop disables future sync");
            require(settings.prefs.getString("status", "").contains("同步已停止"), "Stop retains HA state");
            waitService(false);
            homeActivity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            require(!PresenceService.isRunning(), "Opening home respects explicit stop");
            Activity home = homeActivity;
            runOnMainSync(() -> home.moveTaskToBack(true));
            // Reproduce a lost service with persisted opt-in. Re-enter through the launcher home only.
            settings.prefs.edit().putBoolean("enabled", true).commit();
            runOnMainSync(home::finish);
            homeActivity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitService(true);
            waitStatus(settings, "尚未启用 NAS 围栏");
            require(PresenceService.isRunning(), "MainActivity automatically restores an opted-in missing service");
            Activity restored = homeActivity;
            runOnMainSync(() -> restored.moveTaskToBack(true));
            runOnMainSync(() -> context.stopService(new Intent(context, PresenceService.class)));
            waitService(false);
            require(settings.enabled(), "Service loss preserves user opt-in");
            runOnMainSync(restored::finish);
            homeActivity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitService(true);
            require(PresenceService.isRunning(), "Launcher recovery works after actual service destruction");
            settings.prefs.edit().putBoolean("enabled", false).commit();
            runOnMainSync(() -> context.stopService(new Intent(context, PresenceService.class)));
            waitService(false);
            int geoChecks = GeofenceChecks.run(this, context);
            result.putString("stream", "Presence: " + checks + " checks passed; Geofence: " + geoChecks + " checks passed\n");

        } catch (Throwable t) {
            result.putString("stream", android.util.Log.getStackTraceString(t));
            outcome = Activity.RESULT_CANCELED;
        } finally {
            context.stopService(new Intent(context, PresenceService.class));
            if (homeActivity != null) { Activity a = homeActivity; runOnMainSync(a::finish); }
            if (activity != null) { Activity a = activity; runOnMainSync(a::finish); }
            SharedPreferences.Editor editor = settings.prefs.edit().clear();
            for (Map.Entry<String, ?> entry : backup.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof String) editor.putString(entry.getKey(), (String)v);
                else if (v instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean)v);
                else if (v instanceof Long) editor.putLong(entry.getKey(), (Long)v);
            }
            editor.commit();
            editor = hosts.edit().clear();
            for (Map.Entry<String, ?> entry : hostBackup.entrySet()) {
                if (entry.getValue() instanceof String) editor.putString(entry.getKey(), (String) entry.getValue());
            }
            editor.commit();
            runOnMainSync(() -> ((DashboardApplication) context.getApplicationContext()).monitor().configurationChanged());
        }
        finish(outcome, result);
    }
    private void waitService(boolean expected) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (PresenceService.isRunning() != expected && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        require(PresenceService.isRunning() == expected, "Runtime service state: " + expected);
    }
    private String notificationText(Context context) {
        for (android.service.notification.StatusBarNotification n : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (n.getId() == 104) return String.valueOf(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
        }
        return "";
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             InputStream in = new FileInputStream(fd.getFileDescriptor())) {
            byte[] b = new byte[1024]; while (in.read(b) != -1) { }
        }
    }
    private void waitStatus(PresenceSettings settings, String... expected) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 12000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            String status = settings.prefs.getString("status", "");
            for (String text : expected) if (status.contains(text)) { checks++; return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Unexpected service status: " + settings.prefs.getString("status", ""));
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        volatile String state = "off";
        volatile int posts;
        volatile boolean redirect, validBody = true;
        final Thread thread;
        Fixture() throws IOException {
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String line = reader.readLine();
                        if (line == null) continue;
                        String request = line;
                        int length = 0; boolean authorized = false;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                            if (line.equalsIgnoreCase("Authorization: Bearer fixture-token")) authorized = true;
                        }
                        char[] body = new char[length]; int read = 0;
                        while (read < length) { int n = reader.read(body, read, length-read); if (n < 0) break; read += n; }
                        int status = !authorized ? 401 : redirect ? 302 : 200;
                        if (status == 200 && request.startsWith("POST ")) {
                            boolean on = request.equals("POST /api/services/input_boolean/turn_on HTTP/1.1");
                            boolean off = request.equals("POST /api/services/input_boolean/turn_off HTTP/1.1");
                            validBody &= (on || off) && new JSONObject(new String(body)).getString("entity_id").equals("input_boolean.test_presence");
                            state = on ? "on" : "off"; posts++;
                        }
                        String payload = request.startsWith("GET ") ? "{\"state\":\"" + state + "\"}" : "[]";
                        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\nLocation: http://127.0.0.1:1/stolen\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(bytes); out.flush();
                    } catch (Exception ignored) { }
                }
            });
            thread.start();
        }
        @Override public void close() throws Exception { server.close(); thread.join(2000); }
    }
}
