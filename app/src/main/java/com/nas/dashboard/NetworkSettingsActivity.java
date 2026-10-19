package com.nas.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.ConnectionMonitor;
import android.graphics.Color;
import com.nas.dashboard.network.HostSettings;
import com.nas.dashboard.network.CurrentWifi;

public class NetworkSettingsActivity extends Activity {
    private EditText ssid;
    private EditText local;
    private EditText remote;
    private EditText localPort;
    private EditText publicPort;
    private HostSettings settings;
    private ConnectionMonitor monitor;
    private final ConnectionMonitor.Listener listener = this::showState;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_network_settings);
        ssid = findViewById(R.id.ssid);
        local = findViewById(R.id.local_host);
        remote = findViewById(R.id.public_host);
        localPort = findViewById(R.id.local_port);
        publicPort = findViewById(R.id.public_port);
        settings = new HostSettings(this);
        HostConfig config = settings.load();
        if (config != null) {
            ssid.setText(config.ssid);
            showAddresses(config);
        } else {
            String current = CurrentWifi.read(this);
            if (!current.isEmpty()) ssid.setText(current);
        }
        monitor = ((DashboardApplication) getApplication()).monitor();
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.read_wifi_button).setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                readWifi();
                monitor.refreshRouting();
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 10);
            }
        });
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        findViewById(R.id.refresh_button).setOnClickListener(v -> {
            monitor.check();
            v.post(() -> ((android.widget.ScrollView) findViewById(R.id.network_scroll))
                .smoothScrollTo(0, findViewById(R.id.refresh_button).getBottom()));
        });
    }

    private void save() {
        try {
            HostConfig config = new HostConfig(ssid.getText().toString(),
                local.getText().toString(), localPort.getText().toString(),
                remote.getText().toString(), publicPort.getText().toString());
            HostConfig previous = settings.load();
            settings.save(config);
            if (previous == null || !previous.ssid.equals(config.ssid)
                    || !previous.localBaseUrl.equals(config.localBaseUrl)
                    || !previous.publicBaseUrl.equals(config.publicBaseUrl)) monitor.configurationChanged();
            showAddresses(config);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, invalid.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showAddresses(HostConfig config) {
        local.setText(HostConfig.hostWithoutPort(config.localBaseUrl));
        localPort.setText(HostConfig.portOf(config.localBaseUrl));
        remote.setText(HostConfig.hostWithoutPort(config.publicBaseUrl));
        publicPort.setText(HostConfig.portOf(config.publicBaseUrl));
    }

    private void readWifi() {
        String name = CurrentWifi.read(this);
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.wifi_unavailable, Toast.LENGTH_LONG).show();
        } else {
            ssid.setText(name);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                readWifi();
                monitor.refreshRouting();
            } else {
                Toast.makeText(this, R.string.wifi_unavailable, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showState(ConnectionMonitor.State state) {
        ((TextView) findViewById(R.id.active_host)).setText(state.baseUrl.isEmpty()
            ? getString(R.string.not_tested) : state.baseUrl);
        ((TextView) findViewById(R.id.route_reason)).setText(state.reason);
        TextView status = findViewById(R.id.connection_status);
        status.setText(state.title);
        status.setTextColor(Color.parseColor(state.baseUrl.isEmpty() || "地址已选择".equals(state.title) ? "#706B75" : state.checking ? "#52745F" : state.success ? "#52745F"
            : state.reachable ? "#916524" : "#A34D46"));
        ((TextView) findViewById(R.id.connection_detail)).setText(state.detail);
        findViewById(R.id.refresh_button).setEnabled(!state.checking);
    }

    @Override protected void onStart() { super.onStart(); monitor.addListener(listener); }
    @Override protected void onStop() { monitor.removeListener(listener); super.onStop(); }
}
