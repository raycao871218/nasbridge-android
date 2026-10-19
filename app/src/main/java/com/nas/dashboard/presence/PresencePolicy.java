package com.nas.dashboard.presence;

/** Presence follows the shared NAS route; disconnected/unconfigured is unknown. */
public final class PresencePolicy {
    public static final long TEST_AWAY_DELAY_MS = 60_000L;
    public static final long NORMAL_AWAY_DELAY_MS = 4 * 60 * 60_000L;

    /** Monotonic time, so wall-clock changes cannot shorten the grace period. */
    public static final class AwayTimer {
        private long since = -1;
        public long remaining(State state, long now, long delay) {
            return remaining(state, false, now, delay);
        }
        public long remaining(State state, boolean selecting, long now, long delay) {
            // A short route probe must not restart a long, continuous public-route interval.
            if (selecting) return since < 0 ? delay : Math.max(0, delay - Math.max(0, now - since));
            if (state != State.AWAY) { since = -1; return delay; }
            if (since < 0) since = now;
            return Math.max(0, delay - Math.max(0, now - since));
        }
    }
    public static String formatRemaining(long millis) {
        long seconds = Math.max(0, millis) / 1000 + (millis > 0 && millis % 1000 != 0 ? 1 : 0);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
    public enum State { HOME, AWAY, UNKNOWN }
    public static State detectRoute(boolean configured, boolean selecting, boolean local, boolean online) {
        if (!configured || selecting || !online) return State.UNKNOWN;
        return local ? State.HOME : State.AWAY;
    }
}
