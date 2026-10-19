package com.nas.dashboard;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.JSONObject;
/** Device-local receipts; no message bodies are persisted. */
final class SmsReadStore {
    private final SharedPreferences prefs;
    SmsReadStore(Context context){prefs=context.getSharedPreferences("sms_read_state",Context.MODE_PRIVATE);}
    static String key(JSONObject row){return new org.json.JSONArray().put(row.optString("device_id")).put(row.optString("message_id")).toString();}
    boolean isRead(JSONObject row){return prefs.getStringSet("read",Collections.emptySet()).contains(key(row));}
    boolean hasUnread(){return !prefs.getStringSet("unread",Collections.emptySet()).isEmpty();}
    void observe(List<JSONObject> rows){
        Set<String> read=prefs.getStringSet("read",Collections.emptySet());
        Set<String> unread=new HashSet<>(prefs.getStringSet("unread",Collections.emptySet()));
        for(JSONObject row:rows)if(!read.contains(key(row)))unread.add(key(row));
        prefs.edit().putStringSet("unread",unread).apply();
    }
    void markRead(JSONObject row){
        Set<String> read=new HashSet<>(prefs.getStringSet("read",Collections.emptySet()));
        Set<String> unread=new HashSet<>(prefs.getStringSet("unread",Collections.emptySet()));
        read.add(key(row));unread.remove(key(row));prefs.edit().putStringSet("read",read).putStringSet("unread",unread).apply();
    }
}
