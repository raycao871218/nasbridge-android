package com.nas.dashboard.presence;

import android.content.Context;
import android.location.*;
import android.os.*;


/** Native providers, no Play services; every attempt is bounded and cancels on route changes. */
public final class GeofenceLocator implements LocationListener {
    public interface Callback { void accept(Location location); }
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Callback callback;
    private GeofenceConfig config;
    private final Runnable timeout = () -> finish(null);
    public GeofenceLocator(Context c) { manager = c.getSystemService(LocationManager.class); }
    public boolean active() { return callback != null; }
    public void start(GeofenceConfig config, Callback callback) {
        cancel(); this.config = config; this.callback = callback;
        Location best = null;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                Location last = manager.getLastKnownLocation(provider);
                if (valid(last, config) && (best == null || last.getAccuracy() < best.getAccuracy())) best = last;
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
        if (best != null) { finish(best); return; }
        int providers = 0;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 0, 0, this, Looper.getMainLooper()); providers++;
                }
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
        if (providers == 0) { finish(null); return; }
        main.postDelayed(timeout, Math.min(30000, config.interval / 2));
    }
    public static boolean valid(Location l, GeofenceConfig config) {
        if (l == null || !l.hasAccuracy() || !Float.isFinite(l.getAccuracy()) || l.getAccuracy() < 0 || l.getAccuracy() > config.maxAccuracy
            || !Double.isFinite(l.getLatitude()) || !Double.isFinite(l.getLongitude())
            || Math.abs(l.getLatitude()) > 90 || Math.abs(l.getLongitude()) > 180) return false;
        long age = SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos();
        return l.getElapsedRealtimeNanos() > 0 && age >= 0 && age / 1000000 <= config.maxAge;
    }
    @Override public void onLocationChanged(Location location) { if (callback != null && valid(location, config)) finish(location); }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    private void finish(Location location) {
        Callback result = callback;
        cancel();
        if (result != null) result.accept(location);
    }
    public void cancel() {
        callback = null; main.removeCallbacks(timeout);
        try { manager.removeUpdates(this); } catch (SecurityException | IllegalArgumentException ignored) { }
    }
}
