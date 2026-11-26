package com.nas.dashboard.setup;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppSetupConfigTest {
    private JSONObject fixture() throws Exception {
        return new JSONObject("{\"type\":\"nasbridge.android.config\",\"version\":1,\"network\":{\"ssid\":\"Home Wi-Fi\",\"local_url\":\"http://192.168.1.10:18080\",\"public_url\":\"https://nas.example.test:18099\"},\"presence\":{\"url\":\"https://ha.example.test:8123\",\"entity\":\"input_boolean.home\",\"token\":\"fixture-secret\",\"test_mode\":false}}");
    }
    private void reject(String value) throws Exception {
        try { AppSetupConfig.parse(value); fail("Invalid config accepted"); } catch (IllegalArgumentException | org.json.JSONException expected) { }
    }
    @Test public void previewNormalizesAndHidesToken() throws Exception {
        AppSetupConfig c = AppSetupConfig.parse(fixture().toString());
        assertEquals("http://192.168.1.10:18080/", c.network.localBaseUrl);
        assertEquals("https://ha.example.test:8123/", c.haUrl);
        assertTrue(c.hasPresence()); assertFalse(c.testMode);
        assertTrue(c.preview().contains("30 分钟"));
        assertFalse(c.preview().contains("fixture-secret"));
    }
    @Test public void acceptsNetworkOnlyAndTestMode() throws Exception {
        JSONObject data = fixture(); data.getJSONObject("presence").put("test_mode", true);
        assertTrue(AppSetupConfig.parse(data.toString()).testMode);
        data.remove("presence");
        assertFalse(AppSetupConfig.parse(data.toString()).hasPresence());
    }
    @Test public void importsOrderedLocalAddressesAndPreservesThemWhenResolvingWifi() throws Exception {
        JSONObject data = fixture();
        data.getJSONObject("network").put("ssid", "").remove("local_url");
        data.getJSONObject("network").put("local_urls", new org.json.JSONArray()
            .put("http://10.10.0.66:18080").put("http://192.168.1.10:8080"));
        AppSetupConfig pending = AppSetupConfig.parse(data.toString());
        AppSetupConfig resolved = pending.withWifi("Home");
        assertEquals(java.util.Arrays.asList("http://10.10.0.66:18080/", "http://192.168.1.10:8080/"),
            resolved.network.localBaseUrls);
        assertTrue(resolved.preview().contains("http://192.168.1.10:8080/"));
        data.getJSONObject("network").put("local_urls", new org.json.JSONArray()); reject(data.toString());
        data.getJSONObject("network").put("local_urls", new org.json.JSONArray().put("file:///bad")); reject(data.toString());
        data.getJSONObject("network").put("local_urls", new org.json.JSONArray().put(123)); reject(data.toString());
    }
    @Test public void emptyWifiRequiresPhoneResolutionAndConfirmation() throws Exception {
        JSONObject data = fixture(); data.getJSONObject("network").put("ssid", "");
        AppSetupConfig pending = AppSetupConfig.parse(data.toString());
        assertTrue(pending.needsWifi()); assertNull(pending.network);
        assertTrue(pending.preview().contains("待从手机读取"));
        AppSetupConfig resolved = pending.withWifi("\"Home-Phone\"");
        assertFalse(resolved.needsWifi()); assertEquals("Home-Phone", resolved.network.ssid);
        assertTrue(resolved.preview().contains("手机读取，请确认是家里的"));
        assertEquals(pending.haUrl, resolved.haUrl); assertEquals(pending.entity, resolved.entity);
        assertFalse(pending.withManualWifi("Home-Manual").preview().contains("手机读取"));
        try {pending.apply(null);fail("Unresolved Wi-Fi was applied");} catch (IllegalArgumentException expected) { }
        for (String name : new String[]{"", "<unknown ssid>", "x".repeat(33), "bad\nname"}) {
            try {pending.withWifi(name);fail("Invalid current Wi-Fi accepted");} catch (IllegalArgumentException expected) { }
        }
        assertEquals("Home Wi-Fi", AppSetupConfig.parse(fixture().toString()).network.ssid);
    }
    @Test public void compactQrRoundTripAndInflationLimit() throws Exception {
        String raw = fixture().toString();
        assertEquals("Home Wi-Fi", AppSetupConfig.parse(pack(raw)).network.ssid);
        reject(pack("x".repeat(8193))); reject("NASBRIDGE1:invalid!"); reject("NASBRIDGE1:A");
    }
    private String pack(String raw) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(out)) { z.write(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        return "NASBRIDGE1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }
    @Test public void validatesEntireConfigurationBeforeAnyApply() throws Exception {
        JSONObject data = fixture(); data.getJSONObject("presence").put("token", "bad\nsecret"); reject(data.toString());
        data = fixture(); data.getJSONObject("presence").put("url", "http://ha.test"); reject(data.toString());
        data = fixture(); data.getJSONObject("presence").put("entity", "switch.home"); reject(data.toString());
        data = fixture(); data.getJSONObject("presence").put("test_mode", "true"); reject(data.toString());
        data = fixture(); data.getJSONObject("network").put("ssid", "x".repeat(33)); reject(data.toString());
        for (String address : new String[]{"file:///etc/passwd", "https://user:secret@nas.test", "https://nas.test/?token=x", "https://nas.test:70000/"}) {
            data = fixture(); data.getJSONObject("network").put("local_url", address); reject(data.toString());
        }
        data = fixture(); data.put("version", 2); reject(data.toString());
        data = fixture(); data.put("version", "1"); reject(data.toString());
        reject("[]"); reject("x".repeat(8193)); reject(fixture() + " trailing");
        data = fixture(); data.put("geofence", new JSONObject()); reject(data.toString());
    }
    @Test public void importsFenceSnapshotWithoutLegacyScheduleFields() throws Exception {
        JSONObject data = fixture();
        data.put("geofence", new JSONObject("{\"schema_version\":2,\"revision\":3,\"settings\":{\"enabled\":true,\"name\":\"Home\",\"fence_type\":\"circle\",\"center_latitude\":31.6,\"center_longitude\":104.4,\"radius_meters\":2000,\"max_location_age_seconds\":900,\"max_location_accuracy_meters\":3000}}"));
        AppSetupConfig config = AppSetupConfig.parse(data.toString());
        assertEquals(3, config.geofence.revision); assertEquals(2000, config.geofence.radius, 0);
        assertTrue(config.preview().contains("Home"));
    }
}
