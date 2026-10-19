package com.nas.dashboard.presence;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Only validated NAS schema 2 configurations enter the durable cache. */
public final class GeofenceConfig {
    public final boolean enabled, testMode;
    public final String name, source;
    public final double latitude, longitude, radius, buffer, maxAccuracy;
    public final long interval, duration, maxAge, revision;
    private GeofenceConfig(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw), s = root.getJSONObject("settings");
        if (number(root, "schema_version", 2, 2) != 2 || root.optBoolean("migration_required", false))
            throw new JSONException("Unsupported geofence");
        revision = (long) number(root, "revision", 0, Long.MAX_VALUE);
        enabled = bool(s, "enabled"); testMode = bool(s, "test_mode");
        name = s.getString("name");
        if (name.isEmpty() || name.length() > 100 || !"circle".equals(s.getString("fence_type"))) throw new JSONException("Invalid circle");
        latitude = enabled || !s.isNull("center_latitude") ? number(s, "center_latitude", -85, 85) : 0;
        longitude = enabled || !s.isNull("center_longitude") ? number(s, "center_longitude", -180, 180) : 0;
        radius = number(s, "radius_meters", 100, 500000);
        buffer = number(s, "boundary_buffer_meters", 0, 10000);
        maxAccuracy = number(s, "max_location_accuracy_meters", 100, 10000);
        maxAge = (long) number(s, "max_location_age_seconds", 60, 3600) * 1000;
        long normalInterval = (long) number(s, "check_interval_seconds", 60, 86400);
        long normalDuration = (long) number(s, "max_detection_seconds", 60, 604800);
        if (normalDuration % normalInterval != 0 || normalDuration / normalInterval > 24 || normalDuration < normalInterval)
            throw new JSONException("Invalid schedule");
        interval = (testMode ? 15 : normalInterval) * 1000;
        duration = (testMode ? 60 : normalDuration) * 1000;
        source = raw;
    }
    private static double number(JSONObject o, String key, double min, double max) throws JSONException {
        Object raw = o.get(key);
        if (!(raw instanceof Number)) throw new JSONException("Invalid number");
        double n = ((Number) raw).doubleValue();
        if (!Double.isFinite(n) || n < min || n > max || n != Math.rint(n) && !key.startsWith("center_"))
            throw new JSONException("Out of range");
        return n;
    }
    private static boolean bool(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof Boolean)) throw new JSONException("Invalid boolean");
        return (Boolean) value;
    }
    public static GeofenceConfig parse(String raw) throws JSONException { return new GeofenceConfig(raw); }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("geofence_config", Context.MODE_PRIVATE); }
    public static GeofenceConfig cached(Context c) {
        try { return parse(prefs(c).getString("config", "")); } catch (JSONException e) { return null; }
    }
    public void save(Context c) { prefs(c).edit().putString("config", source).putLong("synced_at", System.currentTimeMillis()).apply(); }
    private static String durationText(long millis) {
        if (millis % 3600000 == 0) return millis / 3600000 + " 小时";
        if (millis % 60000 == 0) return millis / 60000 + " 分钟";
        return millis / 1000 + " 秒";
    }
    public String description() {
        return !enabled ? "NAS 地理围栏已关闭；使用本机外网等待设置" : name + " · 半径 " + (int)radius + " 米\n"
            + "每 " + durationText(interval) + "检测，最长 " + durationText(duration) + (testMode ? "（NAS 测试模式）" : "")
            + "\n配置版本：" + revision + " · WGS-84";
    }
}
