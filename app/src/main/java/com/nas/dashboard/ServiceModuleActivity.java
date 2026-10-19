package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.NasBridgeClient;
import com.nas.dashboard.network.OverviewPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native read-only modules matching the existing NASBridge web navigation. */
public class ServiceModuleActivity extends Activity {
    private String module;
    private ConnectionMonitor monitor;
    private TextView route;
    private TextView status;
    private TextView metrics;
    private LinearLayout content;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile NasBridgeClient client;
    private Future<?> task;
    private HostRouter.Selection loadedRoute;
    private final ConnectionMonitor.Listener listener = this::connectionChanged;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_service_module);
        module = getIntent().getStringExtra("module");
        int title;
        if ("mihomo".equals(module)) title = R.string.module_mihomo_title;
        else if ("tasks".equals(module)) title = R.string.module_tasks_title;
        else if ("certificates".equals(module)) title = R.string.module_certificates_title;
        else if ("aliyun".equals(module)) title = R.string.module_aliyun_title;
        else if ("zerotier".equals(module)) title = R.string.module_zerotier_title;
        else { finish(); return; }
        ((TextView) findViewById(R.id.module_title)).setText(title);
        route = findViewById(R.id.overview_route);
        status = findViewById(R.id.overview_status);
        metrics = findViewById(R.id.overview_metrics);
        content = findViewById(R.id.overview_content);
        monitor = ((DashboardApplication) getApplication()).monitor();
        findViewById(R.id.overview_back).setOnClickListener(v -> finish());
        findViewById(R.id.overview_refresh).setOnClickListener(v -> { loadedRoute = null; connectionChanged(monitor.current()); });
        findViewById(R.id.module_go_config).setOnClickListener(v -> startActivity(new Intent(this, NetworkSettingsActivity.class)));
    }

    private void connectionChanged(ConnectionMonitor.State state) {
        HostRouter.Selection selection = monitor.routeForRequests();
        if (selection == null) {
            cancel();
            loadedRoute = null;
            content.removeAllViews();
            route.setText(state.baseUrl);
            status.setText(state.checking ? "正在自动选择访问地址…" : getString(R.string.module_need_connection));
            metrics.setText(state.checking ? "地址选好后会自动加载，无需手动检测。" : getString(R.string.module_configure_first));
            findViewById(R.id.module_go_config).setVisibility(state.checking ? View.GONE : View.VISIBLE);
            return;
        }
        if (selection == loadedRoute) return;
        cancel();
        loadedRoute = selection;
        int request = generation;
        route.setText(selection.baseUrl + "\n" + selection.reason);
        status.setText("正在读取服务数据…");
        status.setTextColor(Color.parseColor("#52745F"));
        metrics.setText("正在加载当前栏目…");
        content.removeAllViews();
        findViewById(R.id.module_go_config).setVisibility(View.GONE);
        NasBridgeClient next = new NasBridgeClient();
        client = next;
        task = worker.submit(() -> {
            if (request != generation) return;
            try {
                NasBridgeClient.ConnectionFactory factory = path -> selection.openConnection(path);
                NasBridgeClient.Overview overview = null;
                JSONObject data = null;
                String partialError = null;
                if ("mihomo".equals(module) || "certificates".equals(module)) overview = next.load(factory);
                if ("tasks".equals(module)) data = next.getJson(factory, "api/daily-tasks");
                if ("aliyun".equals(module)) data = next.getJson(factory, "api/aliyun/status");
                if ("zerotier".equals(module)) data = next.getJson(factory, "api/zerotier/networks");
                if ("certificates".equals(module)) {
                    try { data = next.getJson(factory, "api/reverse-proxies"); }
                    catch (IOException error) { partialError = error.getMessage(); }
                }
                if (data != null && "error".equals(data.optString("status"))) throw new IOException(data.optString("message", "服务返回错误"));
                NasBridgeClient.Overview snapshot = overview;
                JSONObject json = data;
                String warning = partialError;
                runOnUiThread(() -> {
                    if (request != generation) return;
                    render(snapshot, json, warning);
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (request != generation) return;
                    status.setText("读取失败");
                    status.setTextColor(Color.parseColor("#A34D46"));
                    metrics.setText(error.getMessage() == null ? "请检查服务地址和网络" : error.getMessage());
                    loadedRoute = null;
                });
            }
        });
    }

    private void render(NasBridgeClient.Overview overview, JSONObject data, String warning) {
        status.setText(warning == null ? "数据已更新" : "部分数据未能加载");
        status.setTextColor(Color.parseColor(warning == null ? "#52745F" : "#916524"));
        if ("mihomo".equals(module)) {
            metrics.setText("Mihomo：" + state(overview.mihomoStatus) + "\n活动连接：" + overview.connections
                + "（代理 " + overview.proxiedConnections + " / 直连 " + overview.directConnections + "）"
                + "\n累计上传：" + bytes(overview.uploadBytes) + "\n累计下载：" + bytes(overview.downloadBytes));
            heading("应用连接 · " + overview.applications);
            if (overview.applicationItems.isEmpty()) card("暂无应用", "");
            for (NasBridgeClient.Application app : overview.applicationItems) card(app.name,
                "模式：" + state(app.mode) + " · 连接：" + app.connections + "\n上传 " + bytes(app.upload) + " / 下载 " + bytes(app.download));
        } else if ("tasks".equals(module)) {
            JSONObject summary = data.optJSONObject("metrics");
            if (summary == null) summary = new JSONObject();
            metrics.setText("今日运行：" + summary.optInt("today_runs") + "\n总运行：" + summary.optInt("total_runs")
                + " · 成功：" + summary.optInt("success_runs") + " · 失败：" + summary.optInt("failed_runs")
                + "\n概要记录：" + summary.optInt("summary_count"));
            heading("最近运行");
            JSONArray runs = data.optJSONArray("runs");
            if (runs == null || runs.length() == 0) card("暂无运行记录", "");
            for (int i = 0; runs != null && i < Math.min(runs.length(), 30); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run == null || run.optBoolean("hidden")) continue;
                card(value(run, "run_date") + " · " + state(value(run, "status")),
                    "开始：" + value(run, "started_at") + "\n视频：" + run.optInt("video_count")
                    + " · 概要：" + run.optInt("summary_count") + "\n" + value(run, "run_summary"));
            }
        } else if ("certificates".equals(module)) {
            metrics.setText("证书：" + overview.certificates + "\n" + (warning == null ? "证书与反向代理状态已读取" : "反向代理读取失败：" + warning));
            heading("证书状态");
            for (NasBridgeClient.Certificate cert : overview.certificateItems) {
                String days = cert.daysLeft == null || cert.daysLeft.isNaN() ? "未知" : String.format(Locale.getDefault(), "%.1f 天", cert.daysLeft);
                card(cert.name, cert.domain + "\n状态：" + state(cert.state) + " · 剩余：" + days + "\n到期：" + cert.expiresAt, OverviewPolicy.certificateColor(cert.daysLeft));
            }
            heading("反向代理");
            JSONArray proxies = data == null ? null : data.optJSONArray("proxies");
            if (proxies == null || proxies.length() == 0) card(warning == null ? "暂无反向代理" : "暂不可用", warning == null ? "" : warning);
            for (int i = 0; proxies != null && i < Math.min(proxies.length(), 100); i++) {
                JSONObject proxy = proxies.optJSONObject(i);
                if (proxy != null) card(value(proxy, "service_name"), join(proxy.optJSONArray("server_name"))
                    + "\n监听端口：" + join(proxy.optJSONArray("listen")) + "\n目标：" + join(proxy.optJSONArray("proxy_pass"))
                    + "\nHTTPS：" + (proxy.optBoolean("ssl") ? "是" : "否"));
            }
        } else if ("aliyun".equals(module)) {
            boolean configured = data.optBoolean("configured");
            metrics.setText(configured ? "阿里云凭据：已配置" : "阿里云凭据：未配置");
            card("安全组接入", configured ? "鸟巢已配置访问凭据。地域选择、安全组规则查询和管理操作后续接入。"
                : "请先在 鸟巢服务端配置阿里云凭据。应用不保存或显示 AccessKey。");
        } else if ("zerotier".equals(module)) {
            JSONArray networks = data.optJSONArray("items");
            metrics.setText("网络数量：" + (networks == null ? 0 : networks.length()));
            if (networks == null || networks.length() == 0) card("暂无网络", "");
            for (int i = 0; networks != null && i < Math.min(networks.length(), 100); i++) {
                JSONObject network = networks.optJSONObject(i);
                if (network == null) continue;
                StringBuilder description = new StringBuilder("网络 ID：" + value(network, "id")
                    + "\n类型：" + (network.optBoolean("private") ? "私有" : "公开"));
                JSONArray pools = network.optJSONArray("ipAssignmentPools");
                for (int j = 0; pools != null && j < pools.length(); j++) {
                    JSONObject pool = pools.optJSONObject(j);
                    if (pool != null) description.append("\n地址池：").append(value(pool, "ipRangeStart")).append(" – ").append(value(pool, "ipRangeEnd"));
                }
                JSONArray routes = network.optJSONArray("routes");
                for (int j = 0; routes != null && j < routes.length(); j++) {
                    JSONObject entry = routes.optJSONObject(j);
                    if (entry != null) description.append("\n路由：").append(value(entry, "target")).append(" → ").append(value(entry, "via"));
                }
                card(value(network, "name"), description.toString());
            }
        }
        card("更新时间", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date()));
    }

    private static String value(JSONObject data, String key) { return data.isNull(key) ? "—" : data.optString(key, "—"); }
    private static String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "\n…内容较长，已截取显示"; }
    private static String join(JSONArray values) {
        if (values == null || values.length() == 0) return "—";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length(); i++) { if (i > 0) text.append("、"); text.append(values.optString(i)); }
        return text.toString();
    }
    private static String state(String value) {
        switch (value) {
            case "online": return "在线"; case "offline": return "离线";
            case "proxy": return "代理"; case "direct": return "直连"; case "mixed": return "混合";
            case "idle": return "空闲"; case "success": return "成功"; case "failed": return "失败";
            case "running": return "运行中"; case "valid": return "有效"; case "expiring": return "即将到期";
            case "expired": return "已过期"; case "unknown": return "未知"; default: return value;
        }
    }
    private void heading(String text) {
        TextView view = label(text, 19, "#52745F");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(24); view.setLayoutParams(params); content.addView(view);
    }
    private void card(String title, String description) { card(title, description, "#706B75"); }
    private void card(String title, String description, String color) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setBackgroundResource(R.drawable.card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12); card.setLayoutParams(params);
        card.addView(label(title, 16, "#3B3A49"));
        if (!description.isEmpty()) { TextView body = label(description, 13, color); body.setPadding(0, dp(8), 0, 0); card.addView(body); }
        content.addView(card);
    }
    private TextView label(String text, int size, String color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(Color.parseColor(color)); view.setLineSpacing(dp(4), 1); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String bytes(long value) {
        double amount = value; String[] units = {"B", "KB", "MB", "GB", "TB"}; int unit = 0;
        while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
        return String.format(Locale.getDefault(), "%.1f %s", amount, units[unit]);
    }
    private void cancel() { generation++; if (client != null) client.cancel(); if (task != null) task.cancel(true); client = null; task = null; }
    @Override protected void onStart() { super.onStart(); if (monitor != null) monitor.addListener(listener); }
    @Override protected void onStop() { if (monitor != null) monitor.removeListener(listener); cancel(); loadedRoute = null; super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
