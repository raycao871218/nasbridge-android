package com.nas.dashboard;

import android.app.Application;
import com.nas.dashboard.network.ConnectionMonitor;

public class DashboardApplication extends Application {
    private ConnectionMonitor monitor;
    private final ArticleStore articles = new ArticleStore();
    @Override public void onCreate() {
        super.onCreate();
        monitor = new ConnectionMonitor(this);
        monitor.start();
    }
    public ArticleStore articles() { return articles; }
    public ConnectionMonitor monitor() { return monitor; }
}
