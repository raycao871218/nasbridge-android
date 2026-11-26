package com.nas.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.view.View;
import java.util.ArrayList;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.ConnectionMonitor;
import android.graphics.Color;
import com.nas.dashboard.network.HostSettings;
import com.nas.dashboard.network.CurrentWifi;

public class NetworkSettingsActivity extends Activity {
    private EditText ssid;
    private LinearLayout localHosts;
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
        localHosts = findViewById(R.id.local_hosts);
        remote = findViewById(R.id.public_host);
        localPort = findViewById(R.id.local_port);
        publicPort = findViewById(R.id.public_port);
        settings = new HostSettings(this);
        HostConfig config = settings.load();
        if (config != null) {
            ssid.setText(config.ssid);
            showAddresses(config);
        } else {
            addLocalAddress("");
            String current = CurrentWifi.read(this);
            if (!current.isEmpty()) ssid.setText(current);
        }
        if (state != null && state.containsKey("local_addresses")) {
            localHosts.removeAllViews();
            for (String address : state.getStringArrayList("local_addresses")) addLocalAddress(address);
        }
        findViewById(R.id.add_local_host).setOnClickListener(v -> {
            if (localHosts.getChildCount() >= 8) {
                Toast.makeText(this, "最多添加 8 个内网地址", Toast.LENGTH_SHORT).show();
                return;
            }
            addLocalAddress("");
            localHosts.getChildAt(localHosts.getChildCount() - 1).findViewById(R.id.local_host).requestFocus();
        });
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
                String.join("\n", localAddressValues(true)), localPort.getText().toString(),
                remote.getText().toString(), publicPort.getText().toString());
            HostConfig previous = settings.load();
            settings.save(config);
            if (previous == null || !previous.ssid.equals(config.ssid)
                    || !previous.localBaseUrls.equals(config.localBaseUrls)
                    || !previous.publicBaseUrl.equals(config.publicBaseUrl)) monitor.configurationChanged();
            showAddresses(config);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, invalid.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showAddresses(HostConfig config) {
        localHosts.removeAllViews();
        for (String address : config.localBaseUrls) addLocalAddress(withoutDefaultScheme(address, "http://"));
        localPort.setText("");
        remote.setText(withoutDefaultScheme(HostConfig.hostWithoutPort(config.publicBaseUrl), "https://"));
        publicPort.setText(HostConfig.portOf(config.publicBaseUrl));
    }

    private static String withoutDefaultScheme(String address, String scheme) {
        return address.startsWith(scheme) ? address.substring(scheme.length()) : address;
    }

    private void addLocalAddress(String address) {
        View row = getLayoutInflater().inflate(R.layout.item_local_host, localHosts, false);
        EditText input = row.findViewById(R.id.local_host);
        input.setText(address);
        localHosts.addView(row);
        row.findViewById(R.id.remove_local_host).setOnClickListener(v -> {
            localHosts.removeView(row);
            if (localHosts.getChildCount() == 0) addLocalAddress("");
            updateAddressLabels();
        });
        updateAddressLabels();
    }

    private void updateAddressLabels() {
        for (int i = 0; i < localHosts.getChildCount(); i++) {
            View row = localHosts.getChildAt(i);
            ((TextView) row.findViewById(R.id.local_host_label)).setText("内网地址 " + (i + 1));
            row.findViewById(R.id.remove_local_host).setContentDescription("删除内网地址 " + (i + 1));
        }
        findViewById(R.id.add_local_host).setEnabled(localHosts.getChildCount() < 8);
    }

    private ArrayList<String> localAddressValues(boolean validate) {
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < localHosts.getChildCount(); i++) {
            EditText input = localHosts.getChildAt(i).findViewById(R.id.local_host);
            String value = input.getText().toString().trim();
            if (validate && (value.isEmpty() || value.matches("(?s).*[\\r\\n,，;；].*"))) {
                input.setError("请填写一个地址，或删除此项");
                input.requestFocus();
                throw new IllegalArgumentException("请检查内网地址 " + (i + 1));
            }
            values.add(value);
        }
        return values;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putStringArrayList("local_addresses", localAddressValues(false));
        super.onSaveInstanceState(outState);
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
