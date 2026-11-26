package com.nas.dashboard.presence;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.net.URI;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class PresenceSettings {
    public final SharedPreferences prefs;
    private static final String KEY = "nasbridge_ha_token";
    public PresenceSettings(Context c) { prefs = c.getSharedPreferences("ha_presence", Context.MODE_PRIVATE); }
    public boolean testMode() { return prefs.getBoolean("test_mode", false); }
    public long checkIntervalMillis() { return GeofencePolicy.interval(testMode()); }
    public boolean enabled() { return prefs.getBoolean("enabled", false); }
    public String url() { return prefs.getString("url", "https://azk.nagaiuta.life:18123/"); }
    public String entity() { return prefs.getString("entity", "input_boolean.bowen_shi_fou_zai_jia"); }
    public void status(String s) { prefs.edit().putString("status", s).remove("countdown_deadline").apply(); }
    public String currentStatus() { return prefs.getString("status", "尚未启用"); }
    public static String validateUrl(String text) {
        try {
            URI u = new URI(text.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                || u.getQuery() != null || u.getFragment() != null || u.getPort() > 65535 || u.getPort() == 0) throw new Exception();
            return u.toString().replaceAll("/+$", "") + "/";
        } catch (Exception e) { throw new IllegalArgumentException("请填写有效的 HTTPS HA 地址"); }
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public String token() throws Exception {
        String data = prefs.getString("token", "");
        if (data.isEmpty()) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    public void save(String url, String entity, String token) throws Exception {
        credentials(url, entity, token).apply();
    }
    public void importConfiguration(String url, String entity, String token, boolean testMode) throws Exception {
        if (!credentials(url, entity, token).putBoolean("test_mode", testMode)
            .putBoolean("enabled", false).putBoolean("away_confirmed", false).commit()) throw new java.io.IOException();
    }
    private SharedPreferences.Editor credentials(String url, String entity, String token) throws Exception {
        String address = validateUrl(url);
        if (!entity.matches("input_boolean\\.[a-z0-9_]+")) throw new IllegalArgumentException("实体必须是 input_boolean.xxx");
        if (token.isEmpty()) token = token();
        if (token.isEmpty() || token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("请填写有效的 HA 长期访问令牌");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(c.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return prefs.edit().putString("url", address).putString("entity", entity)
            .putString("token", encrypted).putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP));
    }
}
