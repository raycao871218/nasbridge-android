package com.nas.dashboard.setup;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.nas.dashboard.DashboardApplication;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.HostSettings;
import com.nas.dashboard.presence.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Map;
import org.json.*;

/** A versioned QR imports configuration only; it never starts HA synchronization. */
public final class AppSetupConfig {
    public final HostConfig network;
    public final GeofenceConfig geofence;
    public final String haUrl, entity;
    public final boolean testMode;
    private final String token;
    private final String localUrl, publicUrl;
    private final boolean wifiFromPhone;

    private AppSetupConfig(JSONObject root) throws Exception {
        if (!"nasbridge.android.config".equals(root.getString("type")) || integer(root, "version") != 1)
            throw new IllegalArgumentException();
        JSONObject n = root.getJSONObject("network");
        Object rawSsid = n.opt("ssid");
        if (!(rawSsid instanceof String)) throw new IllegalArgumentException();
        String ssid = (String)rawSsid;
        if (ssid.getBytes(StandardCharsets.UTF_8).length > 32 || ssid.matches("(?s).*[\\p{Cntrl}].*")) throw new IllegalArgumentException();
        localUrl = address(n, "local_url", false); publicUrl = address(n, "public_url", false);
        network = ssid.isEmpty() ? null : new HostConfig(ssid, localUrl, publicUrl);
        wifiFromPhone = false;
        if (root.has("presence")) {
            JSONObject p = root.getJSONObject("presence");
            haUrl = address(p, "url", true);
            entity = text(p, "entity", 160);
            if (!entity.matches("input_boolean\\.[a-z0-9_]+")) throw new IllegalArgumentException();
            token = text(p, "token", 1024);
            if (token.matches("(?s).*[\\s\\p{Cntrl}].*")) throw new IllegalArgumentException();
            Object mode = p.get("test_mode");
            if (!(mode instanceof Boolean)) throw new IllegalArgumentException();
            testMode = (Boolean) mode;
        } else { haUrl = null; entity = null; token = null; testMode = false; }
        geofence = root.has("geofence") ? GeofenceConfig.parse(root.getJSONObject("geofence").toString()) : null;
    }
    private AppSetupConfig(AppSetupConfig original, String ssid, boolean fromPhone) {
        String valid = com.nas.dashboard.network.CurrentWifi.normalize(ssid);
        if (valid.isEmpty()) throw new IllegalArgumentException();
        localUrl = original.localUrl; publicUrl = original.publicUrl;
        network = new HostConfig(valid, localUrl, publicUrl); wifiFromPhone = fromPhone;
        haUrl = original.haUrl; entity = original.entity; token = original.token;
        testMode = original.testMode; geofence = original.geofence;
    }
    public boolean needsWifi() { return network == null; }
    public AppSetupConfig withWifi(String ssid) { return new AppSetupConfig(this, ssid, true); }
    public AppSetupConfig withManualWifi(String ssid) { return new AppSetupConfig(this, ssid, false); }
    private static long integer(JSONObject o, String key) throws Exception {
        Object v = o.get(key);
        if (!(v instanceof Integer) && !(v instanceof Long)) throw new IllegalArgumentException();
        return ((Number)v).longValue();
    }
    private static String text(JSONObject o, String key, int max) throws Exception {
        Object value = o.get(key);
        if (!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length() > max) throw new IllegalArgumentException();
        return (String)value;
    }
    private static String address(JSONObject o, String key, boolean httpsOnly) throws Exception {
        String value = text(o, key, 512);
        URI uri = new URI(value);
        if (value.matches("(?s).*[\\s\\p{Cntrl}].*") || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
            || !("https".equals(uri.getScheme()) || !httpsOnly && "http".equals(uri.getScheme()))) throw new IllegalArgumentException();
        return value.replaceAll("/+$", "") + "/";
    }
    public static AppSetupConfig parse(String raw) throws Exception {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IllegalArgumentException();
        if (raw.startsWith("NASBRIDGE1:")) raw = unpack(raw.substring(11));
        JSONTokener reader = new JSONTokener(raw);
        Object value = reader.nextValue();
        if (!(value instanceof JSONObject) || reader.nextClean() != 0) throw new IllegalArgumentException();
        return new AppSetupConfig((JSONObject)value);
    }
    private static String unpack(String encoded) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        if (encoded.isEmpty() || encoded.length() % 4 == 1) throw new IllegalArgumentException();
        for (int i = 0; i < encoded.length(); i++) {
            int value = alphabet.indexOf(encoded.charAt(i));
            if (value < 0) throw new IllegalArgumentException();
            buffer = (buffer << 6) | value; bits += 6;
            if (bits >= 8) { bits -= 8; compressed.write((buffer >>> bits) & 255); }
        }
        if ((buffer & ((1 << bits) - 1)) != 0) throw new IllegalArgumentException();
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        try (java.util.zip.InflaterInputStream input = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(compressed.toByteArray()))) {
            byte[] chunk = new byte[1024]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (plain.size() + count > 8192) throw new IllegalArgumentException();
                plain.write(chunk, 0, count);
            }
        }
        return new String(plain.toByteArray(), StandardCharsets.UTF_8);
    }
    public boolean hasPresence() { return haUrl != null; }
    public String preview() {
        return "家庭 Wi-Fi：" + (network == null ? "待从手机读取" : network.ssid)
            + (wifiFromPhone ? "（手机读取，请确认是家里的 Wi-Fi）" : "")
            + "\n内网：" + localUrl + "\n外网：" + publicUrl
            + (hasPresence() ? "\n\nHA：" + haUrl + "\n实体：" + entity + "\n令牌：已包含（隐藏）\n定位间隔："
                + (testMode ? "测试 1 分钟" : "正常 30 分钟") : "\n\n未包含 HA 配置，将保留已保存的 HA 设置。")
            + "\n\n围栏：" + (geofence == null ? "启用后从 NAS 读取" : geofence.description())
            + "\n\n确认后覆盖上述配置，自动同步暂时停止。可在「在家状态同步」启用。";
    }
    public void apply(Context context) throws Exception {
        if (needsWifi()) throw new IllegalArgumentException("请先读取或填写家庭 Wi-Fi 名称");
        PresenceSettings presence = new PresenceSettings(context);
        SharedPreferences hosts = context.getSharedPreferences("network_hosts", Context.MODE_PRIVATE);
        SharedPreferences fence = GeofenceConfig.prefs(context);
        Map<String, ?> oldHa = presence.prefs.getAll(), oldHosts = hosts.getAll(), oldFence = fence.getAll();
        try {
            if (!presence.prefs.edit().putBoolean("enabled", false).putBoolean("away_confirmed", false).commit()) throw new java.io.IOException();
            context.stopService(new Intent(context, PresenceService.class));
            if (hasPresence()) presence.importConfiguration(haUrl, entity, token, testMode);
            new HostSettings(context).importConfiguration(network);
            SharedPreferences.Editor edit = fence.edit().clear();
            if (geofence != null) edit.putString("config", geofence.source).putString("sync_status", "从配置二维码导入");
            if (!edit.commit()) throw new java.io.IOException();
            presence.status("配置已导入，请启用在家状态同步");
        } catch (Exception error) {
            restore(hosts, oldHosts); restore(fence, oldFence); restore(presence.prefs, oldHa);
            PresenceService.resumeIfEnabled(context);
            throw new java.io.IOException("配置导入失败，请检查后重试");
        }
        ((DashboardApplication)context.getApplicationContext()).monitor().configurationChanged();
    }
    private static void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> item : values.entrySet()) {
            Object value = item.getValue();
            if (value instanceof String) edit.putString(item.getKey(), (String)value);
            else if (value instanceof Boolean) edit.putBoolean(item.getKey(), (Boolean)value);
            else if (value instanceof Long) edit.putLong(item.getKey(), (Long)value);
            else if (value instanceof Integer) edit.putInt(item.getKey(), (Integer)value);
        }
        edit.commit();
    }
}
