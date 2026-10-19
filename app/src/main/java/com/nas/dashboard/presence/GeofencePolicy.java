package com.nas.dashboard.presence;

/** WGS-84 distance and periodic checks until departure is confirmed. */
public final class GeofencePolicy {
    public static long interval(boolean testMode) { return testMode ? 60_000L : 30 * 60_000L; }
    public static double distance(double lat, double lon, double centerLat, double centerLon) {
        double p = Math.toRadians(lat), q = Math.toRadians(centerLat);
        double a = Math.pow(Math.sin((p-q)/2), 2) + Math.cos(p)*Math.cos(q)
            * Math.pow(Math.sin(Math.toRadians(lon-centerLon)/2), 2);
        return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }
    public static boolean outside(double distance, double radius, double accuracy) {
        return Double.isFinite(distance) && Double.isFinite(accuracy) && accuracy >= 0
            && distance > radius + accuracy;
    }
    public static final class Schedule {
        private long lastCheck = -1;
        private int checks;
        private boolean outside;
        public void update(PresencePolicy.State state, long now) {
            if (state == PresencePolicy.State.HOME) { reset(); return; }
            if (state == PresencePolicy.State.AWAY && lastCheck < 0) lastCheck = now;
        }
        public void reset() { lastCheck = -1; checks = 0; outside = false; }
        public void reschedule(long now) { if (active()) lastCheck = now; }
        public boolean active() { return lastCheck >= 0; }
        public boolean outside() { return outside; }
        public void markOutside() { outside = true; }
        public int checks() { return checks; }
        public long nextIn(long now, long interval) { return Math.max(0, interval - Math.max(0, now - lastCheck)); }
        public void checked(long now) { lastCheck = now; checks++; }
        /** Skip missed slots after sleep rather than producing a burst of stale checks. */
        public boolean claim(long now, long interval) {
            if (!active() || outside || nextIn(now, interval) > 0) return false;
            checked(now);
            return true;
        }
    }
}
