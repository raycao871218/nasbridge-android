package com.nas.dashboard.network;

import android.content.Context;
import android.content.SharedPreferences;

public final class HostSettings {
    private final SharedPreferences preferences;

    public HostSettings(Context context) {
        preferences = context.getSharedPreferences("network_hosts", Context.MODE_PRIVATE);
    }

    public HostConfig load() {
        if (!preferences.contains("ssid")) return null;
        try {
            return new HostConfig(preferences.getString("ssid", ""),
                preferences.getString("local", ""), preferences.getString("public", ""));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public void save(HostConfig config) {
        preferences.edit().putString("ssid", config.ssid)
            .putString("local", config.localBaseUrl)
            .putString("public", config.publicBaseUrl).apply();
    }
}
