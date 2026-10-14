package com.nas.dashboard.network;

import java.net.URI;
import java.net.URISyntaxException;

/** Immutable, validated settings. Kept free of Android dependencies for policy tests. */
public final class HostConfig {
    public final String ssid;
    public final String localBaseUrl;
    public final String publicBaseUrl;

    public HostConfig(String ssid, String localBaseUrl, String publicBaseUrl) {
        this.ssid = normalizeSsid(ssid);
        if (this.ssid.isEmpty() || this.ssid.equals("<unknown ssid>")) {
            throw new IllegalArgumentException("请填写家庭 Wi-Fi 名称");
        }
        this.localBaseUrl = normalizeUrl(localBaseUrl, "局域网 Host");
        this.publicBaseUrl = normalizeUrl(publicBaseUrl, "公网 Host2");
    }

    public HostConfig(String ssid, String localHost, String localPort,
                      String publicHost, String publicPort) {
        this(ssid, withPort(localHost, localPort, "局域网"),
            withPort(publicHost, publicPort, "公网"));
    }

    private static String withPort(String host, String portText, String label) {
        String normalized = normalizeUrl(host, label + " Host");
        String text = portText == null ? "" : portText.trim();
        if (text.isEmpty()) return normalized;
        if (!text.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException(label + "端口须为 1–65535 的整数");
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(label + "端口须为 1–65535 的整数");
        }
        URI uri = URI.create(normalized);
        return uri.getScheme() + "://" + uri.getHost() + ":" + port + uri.getRawPath();
    }

    public static String hostWithoutPort(String baseUrl) {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getHost() + uri.getRawPath();
    }

    public static String portOf(String baseUrl) {
        int port = URI.create(baseUrl).getPort();
        return port == -1 ? "" : Integer.toString(port);
    }

    public static String normalizeSsid(String ssid) {
        String value = ssid == null ? "" : ssid;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String normalizeUrl(String input, String label) {
        try {
            URI uri = new URI(input.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0
                    || uri.getPort() > 65535 || uri.getPort() < -1) {
                throw new URISyntaxException(input, "Invalid base URL");
            }
            String result = uri.toASCIIString();
            return result.endsWith("/") ? result : result + "/";
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException(label + " 需为完整 http:// 或 https:// 地址，可带端口和路径，不含账号、查询参数或 #");
        }
    }

    public String pingHost() {
        String host = URI.create(localBaseUrl).getHost();
        return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    }

    public boolean matches(String connectedSsid) {
        return ssid.equals(normalizeSsid(connectedSsid));
    }
}
