package com.nas.dashboard.network;

public final class HostPolicy {
    private HostPolicy() { }

    public static String select(HostConfig config, boolean wifiConnected,
                                String connectedSsid, boolean pingSucceeded) {
        return wifiConnected && config.matches(connectedSsid) && pingSucceeded
            ? config.localBaseUrl : config.publicBaseUrl;
    }
}
