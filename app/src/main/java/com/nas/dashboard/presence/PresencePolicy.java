package com.nas.dashboard.presence;

/** Presence follows the configured home Wi-Fi, independently of NAS reachability. */
public final class PresencePolicy {
    public enum State { HOME, AWAY, UNKNOWN }
    public static State detectWifi(boolean configured, boolean connected, boolean readable, boolean matchesHome) {
        if (!configured) return State.UNKNOWN;
        if (!connected) return State.AWAY;
        if (!readable) return State.UNKNOWN;
        return matchesHome ? State.HOME : State.AWAY;
    }
}
