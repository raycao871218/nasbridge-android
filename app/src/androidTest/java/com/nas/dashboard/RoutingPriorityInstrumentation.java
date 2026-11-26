package com.nas.dashboard;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.HostSettings;
import com.nas.dashboard.network.ConnectionMonitor;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only in the isolated preview app; never edits the personal app configuration. */
public final class RoutingPriorityInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void awaitRoute(ConnectionMonitor monitor, String expected) throws Exception {
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until) {
            if (monitor.routeForRequests() != null && expected.equals(monitor.routeForRequests().baseUrl)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected " + expected + ", got " + monitor.current().reason);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(getTargetContext().getPackageName().endsWith(".preview"), "Requires isolated preview app");
            ConnectionMonitor monitor = ((DashboardApplication)getTargetContext().getApplicationContext()).monitor();
            AtomicInteger localRequests = new AtomicInteger(), publicRequests = new AtomicInteger();
            try (ServerSocket server = new ServerSocket(0)) {
                Thread responder = new Thread(() -> {
                    while (!server.isClosed()) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(2000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                            String line = reader.readLine();
                            if (line != null && line.contains("/public")) publicRequests.incrementAndGet();
                            else localRequests.incrementAndGet();
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        } catch (Exception ignored) { }
                    }
                });
                responder.setDaemon(true);
                responder.start();
                String base = "http://127.0.0.1:" + server.getLocalPort() + "/";
                HostConfig config = new HostConfig("never-matches-current-wifi", "http://127.0.0.1:1/\n" + base, base + "public/");
                runOnMainSync(() -> {
                    new HostSettings(getTargetContext()).save(config);
                    monitor.configurationChanged();
                    check(monitor.routeForRequests() == null, "Requests must wait for local probes");
                });
                awaitRoute(monitor, base);
                check(monitor.routeForRequests().local, "Reachable local must win without matching Wi-Fi");
                check(localRequests.get() > 0, "Local HTTP service must actually be probed");
                check(publicRequests.get() == 0, "Public must not be contacted before local failure");
                HostConfig failed = new HostConfig("never-matches-current-wifi", "http://127.0.0.1:1/\nhttp://127.0.0.1:2/", base + "public/");
                runOnMainSync(() -> {
                    new HostSettings(getTargetContext()).save(failed);
                    monitor.configurationChanged();
                });
                awaitRoute(monitor, failed.publicBaseUrl);
                check(!monitor.routeForRequests().local, "All failed locals must fall back to public");
            }
            result.putString("stream", "PASS: actual HTTP priority routing without matching Wi-Fi; public untouched on local success; fallback after all failures");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
