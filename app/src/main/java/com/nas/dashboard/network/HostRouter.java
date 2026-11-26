package com.nas.dashboard.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground network routing. All state changes run on main; ordered HTTP probes run on a worker. */
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


    private final ConnectivityManager connectivity;
    private final HostSettings settings;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean started;
    private volatile int generation;

    private volatile EndpointProbe activeRouteProbe;
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

        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.settings = new HostSettings(context);
        this.listener = listener;
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
        cancelRouteProbe();

    }

    public void close() {
        stop();
        worker.shutdownNow();

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
        cancelRouteProbe();
    }


    public void refresh() { refresh(true); }

    /** Initial/configuration routing is automatic, without an HTTP diagnostic. */
    public void initializeRoute() { refresh(false); }

    private void refresh(boolean diagnostic) {
        this.diagnostic = diagnostic;
        if (!started) return;
        int request = ++generation;
        cancelRouteProbe();
        HostConfig config = settings.load();
        if (config == null) {
            publish(new Selection("", "请先设置内网地址和公网地址", false, false, null));
            return;
        }
        publish(new Selection(config.localBaseUrl, "正在按优先级检测内网地址…", false, true, null));
        worker.execute(() -> {
            if (!started || request != generation) return;
            String url = HostPolicy.selectOrdered(config, address -> {
                main.post(() -> {
                    if (started && request == generation)
                        publish(new Selection(address, "正在检测内网地址 "
                            + (config.localBaseUrls.indexOf(address) + 1) + "/" + config.localBaseUrls.size(),
                            false, true, null));
                });
                if (!started || request != generation) return false;
                EndpointProbe probe = new EndpointProbe();
                activeRouteProbe = probe;
                try {
                    if (!started || request != generation) return false;
                    return probe.check(() -> new Selection(address, "", true, false, null)
                        .openConnection("")).reachable;
                } finally {
                    if (activeRouteProbe == probe) activeRouteProbe = null;
                }
            }, () -> !started || request != generation);
            main.post(() -> {
                if (!started || request != generation || url == null) return;
                boolean reachable = config.localBaseUrls.contains(url);
                publish(new Selection(url, reachable
                    ? "内网地址 " + (config.localBaseUrls.indexOf(url) + 1) + " 可达，使用该地址"
                    : "所有内网地址均不可达，使用公网 Host2",
                    reachable, false, null));
            });
        });
    }

    private void publish(Selection value) {
        selection = value;
        listener.onSelection(value, diagnostic);
    }

    private void cancelRouteProbe() {
        EndpointProbe probe = activeRouteProbe;
        if (probe != null) probe.cancel();
    }
}
