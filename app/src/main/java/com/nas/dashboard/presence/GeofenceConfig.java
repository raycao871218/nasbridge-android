package com.nas.dashboard.presence;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Only validated NAS schema 2 configurations enter the durable cache. */
public final class GeofenceConfig {
    public final boolean enabled;
    public final String name, source;
    public final double latitude, longitude, radius, maxAccuracy;
    public final long maxAge, revision;
    private GeofenceConfig(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw), s = root.getJSONObject("settings");
        if (number(root, "schema_version", 2, 2) != 2 || root.optBoolean("migration_required", false))
            throw new JSONException("Unsupported geofence");
        revision = (long) number(root, "revision", 0, Long.MAX_VALUE);
        enabled = bool(s, "enabled");
        name = s.getString("name");
        if (name.isEmpty() || name.length() > 100 || !"circle".equals(s.getString("fence_type"))) throw new JSONException("Invalid circle");
        latitude = enabled || !s.isNull("center_latitude") ? number(s, "center_latitude", -85, 85) : 0;
        longitude = enabled || !s.isNull("center_longitude") ? number(s, "center_longitude", -180, 180) : 0;
        radius = number(s, "radius_meters", 100, 500000);
        maxAccuracy = number(s, "max_location_accuracy_meters", 100, 10000);
        maxAge = (long) number(s, "max_location_age_seconds", 60, 3600) * 1000;
        // Legacy NAS timing fields do not control the phone's check interval or cause departure.
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
    public String description() {
        return !enabled ? "NAS 围栏已关闭；断开家庭 Wi-Fi 后保留状态" : name + " · 半径 " + (int)radius + " 米";
    }
}
