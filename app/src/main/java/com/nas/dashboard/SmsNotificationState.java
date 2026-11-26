package com.nas.dashboard;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/** Keep local notification receipts to avoid alerting for the same message twice. */
final class SmsNotificationState {
    private final SharedPreferences prefs;
    SmsNotificationState(Context context) {
        prefs = context.getSharedPreferences("sms_notifications", Context.MODE_PRIVATE);
        if (prefs.contains("codes")) prefs.edit().remove("codes").apply();
    }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    void enabled(boolean value) { prefs.edit().putBoolean("enabled", value).apply(); }
    void status(String value) { prefs.edit().putString("status", value).apply(); }
    String status() { return prefs.getString("status", "尚未开启"); }

    static long sentAt(JSONObject row) {
        try { return OffsetDateTime.parse(row.optString("sent_at")).toInstant().toEpochMilli(); }
        catch (Exception invalid) { return 0; }
    }
    static boolean recent(JSONObject row, long now, long maxAge) {
        long sent = sentAt(row);
        // NAS, sender and receiving phone clocks can differ by a few seconds.
        return sent > 0 && now - sent >= -30000 && now - sent < maxAge;
    }
    static boolean validCode(String code) { return code.matches("[0-9]{4,8}"); }

    synchronized JSONArray observe(JSONArray items, long now) {
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
        boolean initialized = prefs.getBoolean("initialized", false);
        JSONArray alerts = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row == null) continue;
            String key = SmsReadStore.key(row);
            if (initialized && !seen.contains(key) && recent(row, now, 600000)) alerts.put(row);
            seen.add(key);
        }
        if (seen.size() > 4000) {
            seen.clear();
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i);
                if (row != null) seen.add(SmsReadStore.key(row));
            }
        }
        prefs.edit().putStringSet("seen", seen).putBoolean("initialized", true).apply();
        return alerts;
    }

    void clearCodes() { prefs.edit().remove("codes").apply(); }
}
