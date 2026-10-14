package com.nas.dashboard.network;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Foreground network routing. All state changes run on main; ping runs on a worker. */
public final class HostRouter {
    public interface Listener { void onSelection(Selection selection, boolean diagnostic); }

    public static final class Selection {
        public final String baseUrl;
        public final String reason;
        public final boolean local;
        public final boolean checking;
        private final Network network;

        private Selection(String baseUrl, String reason, boolean local, boolean checking, Network network) {
            this.baseUrl = baseUrl;
            this.reason = reason;
            this.local = local;
            this.checking = checking;
            this.network = network;
        }

        /** Call off the main thread. Use the same selection for URL and network. */
        public HttpURLConnection openConnection(String relativePath) throws IOException {
            if (baseUrl.isEmpty()) throw new IOException("请先配置接口地址");
            URI path = URI.create(relativePath);
            if (path.isAbsolute() || relativePath.startsWith("/") || path.getRawAuthority() != null
                    || path.getRawFragment() != null || path.normalize().getPath().startsWith("..")) {
                throw new IllegalArgumentException("接口路径必须是 base URL 下的相对路径");
            }
            URL url = URI.create(baseUrl).resolve(path).toURL();
            HttpURLConnection connection = (HttpURLConnection) (network == null
                ? url.openConnection() : network.openConnection(url));
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            return connection;
        }
    }

    private final Context context;
    private final ConnectivityManager connectivity;
    private final HostSettings settings;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor dnsWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1));
    private volatile Future<InetAddress[]> activeDns;
    private volatile boolean started;
    private volatile int generation;
    private volatile Process activePing;
    private volatile Selection selection = new Selection("", "尚未配置接口地址", false, false, null);
    private String lastEnvironment;
    private boolean diagnostic = true;
    private final Runnable networkRefresh = this::refreshIfEnvironmentChanged;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { networkChanged(); }
        @Override public void onLost(Network network) { networkChanged(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { networkChanged(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) { networkChanged(); }
    };

    public HostRouter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.settings = new HostSettings(context);
        this.listener = listener;
    }

    public static Selection publicRoute(HostConfig config) {
        return config == null ? null : new Selection(config.publicBaseUrl,
            "尚未检测 Host，数据请求暂用公网 Host2", false, false, null);
    }

    public Selection current() { return selection; }

    public void start() {
        if (started) return;
        started = true;
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
        // Registration reports existing networks too. Establish a baseline, without probing.
        String environment = environmentFingerprint();
        boolean changed = lastEnvironment != null && !lastEnvironment.equals(environment);
        lastEnvironment = environment;
        if (changed) refresh();
    }

    public void stop() {
        if (!started) return;
        started = false;
        generation++;
        connectivity.unregisterNetworkCallback(callback);
        main.removeCallbacks(networkRefresh);
        cancelPing();

    }

    public void close() {
        stop();
        worker.shutdownNow();
        dnsWorker.shutdownNow();
    }

    private void networkChanged() {
        main.post(() -> {
            if (!started) return;
            main.removeCallbacks(networkRefresh);
            main.postDelayed(networkRefresh, 350);
        });
    }

    private void refreshIfEnvironmentChanged() {
        if (!started) return;
        String environment = environmentFingerprint();
        if (environment.equals(lastEnvironment)) return;
        lastEnvironment = environment;
        refresh();
    }

    @SuppressWarnings("deprecation")
    private String environmentFingerprint() {
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            LinkProperties links = connectivity.getLinkProperties(network);
            if (caps == null) continue;
            // Ignore fluctuating signal strength/bandwidth and duplicate callbacks.
            entries.add(network.toString() + ("wifi=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                + ",cell=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                + ",vpn=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                + ",eth=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                + (links == null ? "" : links.getInterfaceName() + links.getLinkAddresses().toString()
                    + links.getDnsServers().toString() + links.getRoutes().toString()));
        }
        java.util.Collections.sort(entries);
        return entries.toString() + "|default=" + connectivity.getActiveNetwork();
    }

    public void invalidate() {
        generation++;
        cancelPing();
    }

    @SuppressWarnings("deprecation") // WifiManager supports SSID reads consistently on API 23+.
    public void refresh() { refresh(true); }

    /** Initial/configuration routing is automatic, without an HTTP diagnostic. */
    public void initializeRoute() { refresh(false); }

    private void refresh(boolean diagnostic) {
        this.diagnostic = diagnostic;
        if (!started) return;
        int request = ++generation;
        cancelPing();
        HostConfig config = settings.load();
        if (config == null) {
            publish(new Selection("", "请先设置家庭 Wi-Fi、Host 和 Host2", false, false, null));
            return;
        }
        Network wifiNetwork = null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                wifiNetwork = network;
                break;
            }
        }
        if (wifiNetwork == null) {
            fallback(config, "未连接 Wi-Fi，使用公网 Host2");
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fallback(config, "无法读取 Wi-Fi 名称：请授权精确定位；当前使用 Host2");
            return;
        }
        LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        boolean locationEnabled = Build.VERSION.SDK_INT >= 28 ? location.isLocationEnabled()
            : location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!locationEnabled) {
            fallback(config, "系统定位已关闭，无法识别 Wi-Fi；当前使用 Host2");
            return;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi.getConnectionInfo();
        String ssid = info == null ? "" : HostConfig.normalizeSsid(info.getSSID());
        if (ssid.isEmpty() || WifiManager.UNKNOWN_SSID.equals(ssid)) {
            fallback(config, "Wi-Fi 名称不可用，使用公网 Host2");
            return;
        }
        if (!config.matches(ssid)) {
            fallback(config, "当前 Wi-Fi：" + ssid + "，非家庭网络，使用 Host2");
            return;
        }
        LinkProperties links = connectivity.getLinkProperties(wifiNetwork);
        String iface = links == null ? null : links.getInterfaceName();
        if (iface == null) {
            fallback(config, "Wi-Fi 接口尚未就绪，使用 Host2");
            return;
        }
        Network probeNetwork = wifiNetwork;
        publish(new Selection(config.publicBaseUrl, "已连接 " + ssid + "，正在 ping 局域网 Host…", false, true, null));
        worker.execute(() -> {
            if (!started || request != generation) return;
            boolean reachable = ping(config.pingHost(), iface, probeNetwork);
            main.post(() -> {
                if (!started || request != generation) return;
                String url = HostPolicy.select(config, true, ssid, reachable);
                publish(new Selection(url, reachable
                    ? "家庭 Wi-Fi：" + ssid + " · ping 成功，使用局域网 Host"
                    : "家庭 Wi-Fi：" + ssid + " · ping 失败或超时，使用 Host2",
                    reachable, false, reachable ? probeNetwork : null));
            });
        });
    }

    private void fallback(HostConfig config, String reason) {
        publish(new Selection(config.publicBaseUrl, reason, false, false, null));
    }

    private void publish(Selection value) {
        selection = value;
        listener.onSelection(value, diagnostic);
    }

    private void cancelPing() {
        Future<InetAddress[]> dns = activeDns;
        if (dns != null) {
            dns.cancel(true);
            dnsWorker.remove((Runnable) dns);
        }
        Process process = activePing;
        if (process != null) process.destroy();
    }

    private boolean ping(String host, String iface, Network network) {
        Process process = null;
        Future<InetAddress[]> dns = null;
        long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
        try {
            // Resolve names on the chosen Wi-Fi, even when cellular/VPN is the default network.
            dns = dnsWorker.submit(() -> network.getAllByName(host));
            activeDns = dns;
            InetAddress[] addresses = dns.get(1500, TimeUnit.MILLISECONDS);
            if (addresses.length == 0) return false;
            String address = addresses[0].getHostAddress();
            String binary = address.contains(":") ? "/system/bin/ping6" : "/system/bin/ping";
            process = new ProcessBuilder(binary, "-I", iface, "-c", "1", "-W", "2", address)
                .redirectErrorStream(true).start();
            activePing = process;
            if (Build.VERSION.SDK_INT >= 26) {
                return process.waitFor(Math.max(1, deadline - android.os.SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) && process.exitValue() == 0;
            }
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { return process.exitValue() == 0; }
                catch (IllegalThreadStateException running) { Thread.sleep(100); }
            }
            return false;
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException
                 | RejectedExecutionException | CancellationException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (dns != null) {
                dns.cancel(true);
                dnsWorker.remove((Runnable) dns);
            }
            if (activeDns == dns) activeDns = null;
            if (process != null) process.destroy();
            if (activePing == process) activePing = null;
        }
    }
}
