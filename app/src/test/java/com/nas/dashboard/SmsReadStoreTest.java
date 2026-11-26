package com.nas.dashboard;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class SmsReadStoreTest {
    @Test public void identitySeparatesDevicesAndDelimiterCharacters() throws Exception {
        JSONObject a=new JSONObject().put("device_id","a:b").put("message_id","c");
        JSONObject b=new JSONObject().put("device_id","a").put("message_id","b:c");
        assertNotEquals(SmsReadStore.key(a),SmsReadStore.key(b));
        assertEquals(SmsReadStore.key(a),SmsReadStore.key(new JSONObject(a.toString()).put("body","changed")));
    }
}
