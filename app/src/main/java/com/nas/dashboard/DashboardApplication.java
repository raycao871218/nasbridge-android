package com.nas.dashboard;

import android.app.Application;
import android.app.Activity;
import android.os.Bundle;
import com.nas.dashboard.presence.PresenceService;
import com.nas.dashboard.network.ConnectionMonitor;

public class DashboardApplication extends Application {
    private ConnectionMonitor monitor;
    private final ArticleStore articles = new ArticleStore();
    @Override public void onCreate() {
        super.onCreate();
        monitor = new ConnectionMonitor(this);
        monitor.start();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                PresenceService.resumeIfEnabled(activity);
                // Recheck after screen-off/process recreation instead of trusting a stale route.
                monitor.refreshRouting();
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
    public ArticleStore articles() { return articles; }
    public ConnectionMonitor monitor() { return monitor; }
}
