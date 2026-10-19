package com.nas.dashboard.presence;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class HomeAssistantClient {
    private HttpURLConnection open(String base, String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json");
        return c;
    }
    private void requireSuccess(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("HA HTTP " + code);
    }
    public String state(String base, String entity, String token) throws Exception {
        HttpURLConnection c = open(base, "api/states/" + entity, token);
        try {
            requireSuccess(c);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 65536) throw new IOException("HA 响应过大");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString("UTF-8")).getString("state");
            }
        } finally { c.disconnect(); }
    }
    public void set(String base, String entity, String token, boolean home) throws Exception {
        HttpURLConnection c = open(base, "api/services/input_boolean/" + (home ? "turn_on" : "turn_off"), token);
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true);
            byte[] body = new JSONObject().put("entity_id", entity).toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(body); }
            requireSuccess(c);
        } finally { c.disconnect(); }
    }
}
