package com.nas.dashboard;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.NasBridgeClient;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class NasOverviewActivity extends Activity {
    private HostRouter router;
    private TextView route;
    private TextView status;
    private TextView metrics;
    private LinearLayout content;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile NasBridgeClient client;
    private Future<?> task;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_nas_overview);
        route = findViewById(R.id.overview_route);
        status = findViewById(R.id.overview_status);
        metrics = findViewById(R.id.overview_metrics);
        content = findViewById(R.id.overview_content);
        router = new HostRouter(this, (selection, diagnostic) -> select(selection));
        findViewById(R.id.overview_back).setOnClickListener(view -> finish());
        findViewById(R.id.overview_refresh).setOnClickListener(view -> router.refresh());
    }

    private void select(HostRouter.Selection selection) {
        cancel();
        int request = generation;
        route.setText(selection.baseUrl + "\n" + selection.reason);
        content.removeAllViews();
        status.setTextColor(Color.parseColor("#52745F"));
        status.setText(selection.baseUrl.isEmpty() ? R.string.bridge_unconfigured : R.string.bridge_wait);
        metrics.setText(R.string.bridge_description);
        if (selection.checking || selection.baseUrl.isEmpty()) return;
        NasBridgeClient next = new NasBridgeClient();
        client = next;
        task = worker.submit(() -> {
            if (request != generation) return;
            try {
                NasBridgeClient.Overview data = next.load(path -> selection.openConnection(path));
                runOnUiThread(() -> { if (request == generation) render(data); });
            } catch (IOException failure) {
                runOnUiThread(() -> {
                    if (request != generation) return;
                    status.setText(R.string.bridge_not_loaded);
                    status.setTextColor(Color.parseColor("#A34D46"));
                    metrics.setText(failure.getMessage() == null ? getString(R.string.bridge_connection_needed) : failure.getMessage());
                });
            }
        });
    }

    private void render(NasBridgeClient.Overview data) {
        status.setText(R.string.bridge_connected);
        String state = "online".equals(data.mihomoStatus) ? "在线" : data.mihomoStatus;
        metrics.setText("Mihomo：" + state + "\n活动连接：" + data.connections
            + "（代理 " + data.proxiedConnections + " / 直连 " + data.directConnections + "）"
            + "\n累计上传：" + bytes(data.uploadBytes) + "\n累计下载：" + bytes(data.downloadBytes)
            + "\n内存：" + bytes(data.memoryBytes) + "\n更新时间："
            + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()));
        heading(getString(R.string.overview_apps) + " · " + data.applications);
        if (data.applicationItems.isEmpty()) card(getString(R.string.overview_empty), "");
        for (NasBridgeClient.Application app : data.applicationItems) {
            card(app.name, "连接：" + app.connections + " · 模式：" + app.mode
                + "\n上传 " + bytes(app.upload) + " / 下载 " + bytes(app.download));
        }
        heading(getString(R.string.overview_certs) + " · " + data.certificates);
        if (data.certificateItems.isEmpty()) card(getString(R.string.overview_empty), "");
        for (NasBridgeClient.Certificate cert : data.certificateItems) {
            String days = cert.daysLeft == null || cert.daysLeft.isNaN() ? "未知"
                : String.format(Locale.getDefault(), "%.1f 天", cert.daysLeft);
            card(cert.name, cert.domain + "\n状态：" + cert.state + " · 剩余：" + days
                + (cert.expiresAt.isEmpty() ? "" : "\n到期：" + cert.expiresAt));
        }
    }

    private void heading(String text) {
        TextView view = label(text, 19, "#52745F");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(24);
        view.setLayoutParams(params);
        content.addView(view);
    }

    private void card(String title, String description) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        card.setLayoutParams(params);
        card.addView(label(title, 16, "#3B3A49"));
        if (!description.isEmpty()) {
            TextView body = label(description, 13, "#706B75");
            body.setPadding(0, dp(8), 0, 0);
            card.addView(body);
        }
        content.addView(card);
    }

    private TextView label(String text, int size, String color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        view.setLineSpacing(dp(4), 1);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        double amount = value;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
        return String.format(Locale.getDefault(), "%.1f %s", amount, units[unit]);
    }

    private void cancel() {
        generation++;
        if (client != null) client.cancel();
        if (task != null) task.cancel(true);
        client = null;
        task = null;
    }
    @Override protected void onStart() { super.onStart(); router.start(); }
    @Override protected void onStop() { cancel(); router.stop(); super.onStop(); }
    @Override protected void onDestroy() { router.close(); worker.shutdownNow(); super.onDestroy(); }
}
