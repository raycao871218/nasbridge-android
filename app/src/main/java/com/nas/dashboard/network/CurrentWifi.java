package com.nas.dashboard.network;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.nio.charset.StandardCharsets;

/** Reads only the connected SSID, never Wi-Fi passwords or nearby network scans. */
public final class CurrentWifi {
    public static String normalize(String raw) {
        String ssid = HostConfig.normalizeSsid(raw);
        if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)
            || ssid.getBytes(StandardCharsets.UTF_8).length > 32 || ssid.matches("(?s).*[\\p{Cntrl}].*")) return "";
        return ssid;
    }
    @SuppressWarnings("deprecation")
    public static String read(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "";
        try {
            LocationManager location = context.getSystemService(LocationManager.class);
            boolean enabled = location != null && (Build.VERSION.SDK_INT >= 28 ? location.isLocationEnabled()
                : location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!enabled) return "";
            WifiManager wifi = (WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            return info == null || info.getNetworkId() < 0 ? "" : normalize(info.getSSID());
        } catch (SecurityException | IllegalArgumentException error) { return ""; }
    }
}
