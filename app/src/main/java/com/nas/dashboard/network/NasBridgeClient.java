package com.nas.dashboard.network;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Status APIs and explicit article read markers; no stored NAS credentials. */
public final class NasBridgeClient {
    public interface ConnectionFactory { HttpURLConnection open(String path) throws IOException; }
    public static final class Overview {
        public final String name;
        public final String mihomoStatus;
        public final int connections;
        public final int applications;
        public final int certificates;
        public final int proxiedConnections;
        public final int directConnections;
        public final long memoryBytes;
        public final long uploadBytes;
        public final long downloadBytes;
        public final String generatedAt;
        public final List<Application> applicationItems;
        public final List<Certificate> certificateItems;
        private Overview(JSONObject data) throws JSONException {
            JSONObject app = data.getJSONObject("app");
            name = app.getString("name");
            if (!"NASBridge".equalsIgnoreCase(name)) throw new JSONException("Unexpected service");
            JSONObject mihomo = data.getJSONObject("mihomo");
            mihomoStatus = mihomo.optString("status", "unknown");
            connections = mihomo.optInt("connection_count", 0);
            applications = data.getJSONArray("applications").length();
            certificates = data.getJSONArray("certificates").length();
            proxiedConnections = mihomo.optInt("proxied_count", 0);
            directConnections = mihomo.optInt("direct_count", 0);
            memoryBytes = mihomo.optLong("memory", 0);
            JSONObject proxied = mihomo.optJSONObject("proxied_bytes");
            JSONObject direct = mihomo.optJSONObject("direct_bytes");
            uploadBytes = count(proxied, "upload") + count(direct, "upload");
            downloadBytes = count(proxied, "download") + count(direct, "download");
            generatedAt = data.optString("generated_at", "");
            List<Application> apps = new ArrayList<>();
            JSONArray appArray = data.getJSONArray("applications");
            for (int i = 0; i < Math.min(appArray.length(), 100); i++) apps.add(new Application(appArray.getJSONObject(i)));
            applicationItems = Collections.unmodifiableList(apps);
            List<Certificate> certs = new ArrayList<>();
            JSONArray certArray = data.getJSONArray("certificates");
            for (int i = 0; i < Math.min(certArray.length(), 100); i++) certs.add(new Certificate(certArray.getJSONObject(i)));
            certificateItems = Collections.unmodifiableList(certs);
        }
    }

    private static long count(JSONObject data, String key) { return data == null ? 0 : data.optLong(key, 0); }

    public static final class Application {
        public final String name;
        public final String mode;
        public final int connections;
        public final long upload;
        public final long download;
        private Application(JSONObject data) {
            name = data.optString("name", "未命名应用");
            mode = data.optString("observed_mode", "unknown");
            connections = data.optInt("active_connections", 0);
            upload = data.optLong("upload", 0);
            download = data.optLong("download", 0);
        }
    }

    public static final class Certificate {
        public final String name;
        public final String domain;
        public final String state;
        public final String expiresAt;
        public final Double daysLeft;
        private Certificate(JSONObject data) {
            name = data.optString("name", "未命名证书");
            domain = data.optString("domain", "");
            state = data.optString("status", "unknown");
            expiresAt = data.optString("expires_at", "");
            daysLeft = data.isNull("days_left") ? null : data.optDouble("days_left", Double.NaN);
        }
    }

    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    public Overview load(ConnectionFactory factory) throws IOException {
        try {
            JSONObject health = get(factory, "api/health", 65536);
            if (!"ok".equals(health.optString("status"))) throw new IOException("NASBridge 健康检查未通过");
            return new Overview(get(factory, "api/overview", 2 * 1024 * 1024));
        } catch (JSONException invalid) {
            throw new IOException("该地址未返回有效的 NASBridge 数据，请检查 Host 和服务路径", invalid);
        }
    }

    public JSONObject getJson(ConnectionFactory factory, String path) throws IOException {
        try { return get(factory, path, 2 * 1024 * 1024); }
        catch (JSONException invalid) { throw new IOException("服务未返回有效的 JSON 数据", invalid); }
    }

    public JSONObject googleCheck(ConnectionFactory factory) throws IOException {
        try { return request(factory, "api/mihomo/google-check", 65536, "GET", null, 16000); }
        catch (JSONException invalid) { throw new IOException("Google 检测返回了无效的数据", invalid); }
    }

    public JSONObject article(ConnectionFactory factory, String id) throws IOException {
        return getJson(factory, "api/daily-tasks/article?id=" + java.net.URLEncoder.encode(id, "UTF-8"));
    }

    public void markArticleRead(ConnectionFactory factory, String id) throws IOException {
        try {
            JSONObject body = new JSONObject().put("action", "read_summary").put("id", id);
            JSONObject result = request(factory, "api/daily-tasks/state", 65536, "POST", body, 8000);
            if (!"ok".equals(result.optString("status"))) throw new IOException("服务未确认已读状态，请重试");
        } catch (JSONException invalid) { throw new IOException("已读状态保存失败，请重试", invalid); }
    }

    private JSONObject get(ConnectionFactory factory, String path, int limit) throws IOException, JSONException {
        return request(factory, path, limit, "GET", null, 8000);
    }

    private JSONObject request(ConnectionFactory factory, String path, int limit, String method, JSONObject body, int readTimeout) throws IOException, JSONException {
        if (cancelled) throw new IOException("已取消");
        HttpURLConnection connection = factory.open(path);
        active = connection;
        try {
            if (cancelled) throw new IOException("已取消");
            connection.setRequestMethod(method);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(readTimeout);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("NASBridge 需要登录或访问授权");
            if (code < 200 || code >= 300) throw new IOException("NASBridge 接口返回 HTTP " + code + "，请检查服务地址或登录状态");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled) throw new IOException("已取消");
                    if (bytes.size() + count > limit) throw new IOException("NASBridge 返回数据过大");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
            if (active == connection) active = null;
        }
    }
}
