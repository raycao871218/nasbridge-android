package com.nas.dashboard.presence;

/** WGS-84 great-circle checks and an elapsed-time window; independent of Android. */
public final class GeofencePolicy {
    public static double distance(double lat, double lon, double centerLat, double centerLon) {
        double p = Math.toRadians(lat), q = Math.toRadians(centerLat);
        double a = Math.pow(Math.sin((p-q)/2), 2) + Math.cos(p)*Math.cos(q)
            * Math.pow(Math.sin(Math.toRadians(lon-centerLon)/2), 2);
        return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }
    public static boolean outside(double distance, double radius, double buffer, double accuracy) {
        return Double.isFinite(distance) && Double.isFinite(accuracy) && accuracy >= 0
            && distance > radius + buffer + accuracy;
    }
    public static final class Window {
        private long since = -1;
        private int slot;
        private boolean outside;
        public void update(PresencePolicy.State state, boolean probing, long now) {
            if (probing) return;
            if (state != PresencePolicy.State.AWAY) { reset(); return; }
            if (since < 0) since = now;
        }
        public void reset() { since = -1; slot = 0; outside = false; }
        public boolean active() { return since >= 0; }
        public long elapsed(long now) { return since < 0 ? 0 : Math.max(0, now - since); }
        public long remaining(long now, long duration) { return Math.max(0, duration - elapsed(now)); }
        public boolean outside() { return outside; }
        public void markOutside() { outside = true; }
        public int checks() { return slot; }
        public long nextIn(long now, long interval) { return Math.max(0, (slot + 1L) * interval - elapsed(now)); }
        /** Skip missed slots after sleep rather than producing a burst of stale checks. */
        public boolean claim(long now, long interval, long duration) {
            long elapsed = elapsed(now);
            if (!active() || outside || elapsed > duration + Math.min(30000, interval / 2) || elapsed < (slot + 1L) * interval) return false;
            slot = (int) Math.min(duration / interval, elapsed / interval);
            return true;
        }
    }
}
