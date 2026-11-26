package com.nas.dashboard.network;

public final class HostPolicy {
    private HostPolicy() { }

    /** Probe in priority order; the public endpoint is only selected after all locals fail. */
    public static String selectOrdered(HostConfig config, java.util.function.Predicate<String> available,
                                       java.util.function.BooleanSupplier cancelled) {
        for (String address : config.localBaseUrls) {
            if (cancelled.getAsBoolean()) return null;
            boolean reachable = available.test(address);
            if (cancelled.getAsBoolean()) return null;
            if (reachable) return address;
        }
        return cancelled.getAsBoolean() ? null : config.publicBaseUrl;
    }

}
