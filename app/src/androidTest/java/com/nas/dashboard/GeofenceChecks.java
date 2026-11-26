package com.nas.dashboard;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import com.nas.dashboard.presence.*;
import com.nas.dashboard.network.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Called only by the dedicated-emulator presence runner. */
final class GeofenceChecks {
    private static int checks;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    interface Condition { boolean ready() throws Exception; }
    private static void waitFor(Condition condition, long timeout, String message) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) { if (condition.ready()) { checks++; return; } Thread.sleep(100); }
        throw new AssertionError(message);
    }
    static int run(Instrumentation test, Context c) throws Exception {
        SharedPreferences cache = GeofenceConfig.prefs(c);
        Map<String, ?> backup = new HashMap<>(cache.getAll());
        PresenceSettings settings = new PresenceSettings(c);
        LocationManager lm = c.getSystemService(LocationManager.class);
        Activity activity = null;
        try (Fixture fixture = new Fixture()) {
            cache.edit().clear().commit();
            GeofenceConfig normal = GeofenceConfig.parse(config(1, false));
            check(normal.enabled, "Normal NAS geometry loads");
            GeofenceConfig config = GeofenceConfig.parse(config(1, true));
            check(config.enabled, "Legacy NAS test flag still loads geometry");
            for (String bad : new String[]{config.source.replace("\"schema_version\":2", "\"schema_version\":1"),
                config.source.replace("\"radius_meters\":2000", "\"radius_meters\":0"),
                config.source.replace("\"enabled\":true", "\"enabled\":\"true\""),
                config.source.replace("\"center_latitude\":31.6", "\"center_latitude\":91"),
                config.source.replace("\"max_location_accuracy_meters\":3000", "\"max_location_accuracy_meters\":0")}) {
                try { GeofenceConfig.parse(bad); throw new AssertionError("Invalid configuration accepted"); }
                catch (JSONException expected) { checks++; }
            }
            config.save(c);
            check(GeofenceConfig.cached(c).revision == 1, "Validated cache round trip");
            Location location = fix(31.6, 104.4);
            check(GeofenceLocator.valid(location, config), "Fresh native location");
            location.setAccuracy(4000); check(!GeofenceLocator.valid(location, config), "Reject imprecise fix");
            location = fix(31.6, 104.4); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - (config.maxAge + 1000) * 1000000);
            check(!GeofenceLocator.valid(location, config), "Reject stale fix");
            location = fix(31.6, 104.4); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() + 1000000000);
            check(!GeofenceLocator.valid(location, config), "Reject future fix");
            int locationMode = android.provider.Settings.Secure.getInt(c.getContentResolver(), "location_mode", 3);
            try {
                shell(test, "cmd location set-location-enabled false");
                waitFor(() -> !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER), 3000, "Location providers did not disable");
                java.util.concurrent.atomic.AtomicBoolean unknown = new java.util.concurrent.atomic.AtomicBoolean();
                test.runOnMainSync(() -> new GeofenceLocator(c).start(config, l -> unknown.set(l == null)));
                check(unknown.get(), "Providers disabled returns unknown without HA write");
            } finally { shell(test, "cmd location set-location-enabled " + (locationMode != 0)); }
            shell(test, "appops set com.nas.dashboard android:mock_location allow");
            test.runOnMainSync(() -> {
                lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
                lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix(32.6, 104.4));
            });
            cache.edit().clear().commit();
            settings.prefs.edit().putBoolean("enabled", false).commit();
            new HostSettings(c).save(new HostConfig("fixture-never-matches", "http://127.0.0.1:1/", "http://127.0.0.1:" + fixture.server.getLocalPort() + "/"));
            test.runOnMainSync(() -> ((DashboardApplication)c.getApplicationContext()).monitor().configurationChanged());
            activity = test.startActivitySync(new Intent(c, PresenceSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            settings.prefs.edit().putBoolean("enabled", true).putBoolean("test_mode", true).putBoolean("away_confirmed", false).commit();
            test.runOnMainSync(() -> PresenceService.start(c));
            waitFor(() -> GeofenceConfig.cached(c) != null, 12000, "NAS config never synced");
            check(GeofenceConfig.cached(c).enabled, "Live fixture config enabled");
            waitFor(() -> settings.currentStatus().contains("下次检测"), 12000, "No geofence countdown");
            final Activity screen = activity;
            test.runOnMainSync(() -> screen.findViewById(R.id.presence_test_now).performClick());
            waitFor(() -> cache.getString("last_check", "").contains("围栏外"), 35000, "Immediate test did not apply native outside fix");
            check(settings.prefs.getBoolean("away_confirmed", false), "Departure persisted");
            long outsideChecks = cache.getLong("last_check_number", 0);
            // A config refresh must not restart automatic positioning once away is confirmed.
            fixture.body = config(2, true);
            c.sendBroadcast(new Intent(c, PresenceReceiver.class).setAction(PresenceService.REFRESH_CONFIG));
            waitFor(() -> GeofenceConfig.cached(c).revision == 2, 12000, "Updated config not applied");
            Thread.sleep(65000);
            check(cache.getLong("last_check_number", 0) == outsideChecks || !cache.contains("last_check_number"), "Confirmed away stops automatic checks");
            // Manual testing still works after departure; an inside fix does not invent a home arrival.
            test.runOnMainSync(() -> lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix(31.6, 104.4)));
            test.runOnMainSync(() -> screen.findViewById(R.id.presence_test_now).performClick());
            waitFor(() -> cache.getString("last_check", "").contains("围栏内"), 35000, "Manual inside fix not applied");
            check(settings.prefs.getBoolean("away_confirmed", false), "Only home Wi-Fi resets confirmed departure");
            // Simulate a fresh monitoring session; inside fixes must never time out to away.
            test.runOnMainSync(() -> c.stopService(new Intent(c, PresenceService.class)));
            waitFor(() -> !PresenceService.isRunning(), 5000, "Service stop before inside session");
            settings.prefs.edit().putBoolean("away_confirmed", false).commit();
            test.runOnMainSync(() -> PresenceService.start(c));
            long before = cache.getLong("last_check_at", 0);
            waitFor(() -> {
                test.runOnMainSync(() -> lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix(31.6, 104.4)));
                return cache.getLong("last_check_at", 0) > before;
            }, 100000, "One-minute periodic check missing");
            check(!settings.prefs.getBoolean("away_confirmed", false), "Inside fix preserves state beyond old test timeout");
            fixture.body = "{}";
            c.sendBroadcast(new Intent(c, PresenceReceiver.class).setAction(PresenceService.REFRESH_CONFIG));
            waitFor(() -> cache.getString("sync_status", "").contains("失败"), 12000, "Invalid config response not detected");
            check(GeofenceConfig.cached(c).revision == 2, "Failed refresh preserves last valid cache");
            test.runOnMainSync(() -> {
                settings.prefs.edit().putBoolean("enabled", false).commit();
                c.stopService(new Intent(c, PresenceService.class));
            });
            waitFor(() -> !PresenceService.isRunning(), 5000, "Geofence service failed to stop");
        } finally {
            settings.prefs.edit().putBoolean("enabled", false).commit();
            test.runOnMainSync(() -> c.stopService(new Intent(c, PresenceService.class)));
            if (activity != null) { Activity a = activity; test.runOnMainSync(a::finish); }
            test.runOnMainSync(() -> { try { lm.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (RuntimeException ignored) { } });
            SharedPreferences.Editor e = cache.edit().clear();
            for (Map.Entry<String, ?> entry : backup.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) e.putString(entry.getKey(), (String)value);
                if (value instanceof Long) e.putLong(entry.getKey(), (Long)value);
            }
            e.commit();
        }
        return checks;
    }
    private static Location fix(double latitude, double longitude) {
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(latitude); l.setLongitude(longitude); l.setAccuracy(30);
        l.setTime(System.currentTimeMillis()); l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()); return l;
    }
    private static String config(int revision, boolean test) {
        return "{\"schema_version\":2,\"revision\":" + revision + ",\"settings\":{\"enabled\":true,\"test_mode\":" + test
            + ",\"name\":\"测试围栏\",\"fence_type\":\"circle\",\"center_latitude\":31.6,\"center_longitude\":104.4,\"radius_meters\":2000,\"boundary_buffer_meters\":1000,\"max_location_accuracy_meters\":3000,\"max_location_age_seconds\":900,\"check_interval_seconds\":3600,\"max_detection_seconds\":14400}}";
    }
    private static void shell(Instrumentation t, String cmd) throws Exception {
        try (ParcelFileDescriptor fd = t.getUiAutomation().executeShellCommand(cmd); InputStream in = new FileInputStream(fd.getFileDescriptor())) { while (in.read() != -1) { } }
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        volatile String body = config(1, true);
        Fixture() throws IOException {
            new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String request = r.readLine(), line;
                    while ((line = r.readLine()) != null && !line.isEmpty()) { }
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(b); out.flush();
                } catch (IOException ignored) { }
            }, "geo-fixture").start();
        }
        public void close() throws IOException { server.close(); }
    }
}
