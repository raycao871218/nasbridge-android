package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** On-entry business data reads. Host probes still only run manually or on network changes. */
public final class OverviewController {
    private interface JsonRenderer { void render(JSONObject data); }
    private final Activity activity;
    private final ConnectionMonitor monitor;
    private final ArticleStore articles;
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final List<NasBridgeClient> clients = new ArrayList<>();
    private final List<Future<?>> jobs = new ArrayList<>();
    private final ConnectionMonitor.Listener listener = this::connectionChanged;
    private volatile int generation;
    private boolean visible;
    private String routeKey = "";
    private boolean googleRunning;
    private JSONObject lastTasks;
    private HostRouter.Selection lastArticleRoute;

    public OverviewController(Activity activity) {
        this.activity = activity;
        DashboardApplication app = (DashboardApplication) activity.getApplication();
        monitor = app.monitor(); articles = app.articles();
        activity.findViewById(R.id.home_refresh).setOnClickListener(v -> { routeKey = ""; connectionChanged(monitor.current()); });
        activity.findViewById(R.id.home_google_check).setOnClickListener(v -> checkGoogle());
    }

    public void show() {
        if (visible) return;
        visible = true; routeKey = "";
        monitor.addListener(listener);
    }
    public void hide() { if (!visible) return; visible = false; monitor.removeListener(listener); cancel(); routeKey = ""; }
    public void refreshReadMarkers() {
        if (visible && lastTasks != null && lastArticleRoute != null) renderArticles(lastArticleRoute, lastTasks);
    }
    public void close() { hide(); worker.shutdownNow(); }

    private TextView text(int id) { return activity.findViewById(id); }
    private LinearLayout list(int id) { return activity.findViewById(id); }
    private void connectionChanged(ConnectionMonitor.State state) {
        if (!visible) return;
        HostRouter.Selection route = monitor.routeForRequests();
        if (route == null) {
            cancel(); routeKey = "";
            text(R.id.home_overview_status).setText(state.checking ? "网络切换中，等待地址选择…" : "请先在配置中保存访问地址");
            text(R.id.home_mihomo_status).setText("暂不可用");
            text(R.id.home_mihomo_detail).setText(state.checking ? "" : "进入「配置 → 网络配置」设置 Host 和端口。");
            text(R.id.home_articles_status).setText("暂无可用数据"); list(R.id.home_articles).removeAllViews();
            text(R.id.home_certificates_status).setText("暂无可用数据"); list(R.id.home_certificates).removeAllViews();
            activity.findViewById(R.id.home_google_check).setEnabled(false);
            return;
        }
        String key = route.baseUrl + "|" + route.local + "|" + System.identityHashCode(route);
        if (key.equals(routeKey)) return;
        routeKey = key; cancel();
        lastTasks = null; lastArticleRoute = null;
        activity.findViewById(R.id.home_google_check).setEnabled(true);
        text(R.id.home_google_result).setText(R.string.google_not_checked);
        text(R.id.home_overview_status).setText(route.baseUrl + "\n正在读取总览数据…");
        text(R.id.home_mihomo_status).setText("读取中…");
        text(R.id.home_mihomo_detail).setText("");
        text(R.id.home_articles_status).setText("正在读取今日未读文章…"); list(R.id.home_articles).removeAllViews();
        text(R.id.home_certificates_status).setText("正在读取证书状态…"); list(R.id.home_certificates).removeAllViews();
        load(route, "api/overview", data -> {
            JSONObject mihomo = data.optJSONObject("mihomo");
            if (mihomo == null) { text(R.id.home_mihomo_status).setText("状态不可用"); return; }
            boolean online = "online".equals(mihomo.optString("status"));
            text(R.id.home_mihomo_status).setText(online ? "在线" : "离线 / 状态不可用");
            text(R.id.home_mihomo_status).setTextColor(Color.parseColor(online ? OverviewPolicy.GREEN : OverviewPolicy.YELLOW));
            JSONObject container = mihomo.optJSONObject("container");
            text(R.id.home_mihomo_detail).setText("活动连接：" + mihomo.optInt("connection_count")
                + (container == null ? "" : "\n容器：" + container.optString("state", "未知")));
        }, R.id.home_mihomo_detail);
        load(route, "api/daily-tasks", data -> renderArticles(route, data), R.id.home_articles_status);
        load(route, "api/reverse-proxies", this::renderCertificates, R.id.home_certificates_status);
    }

    private void load(HostRouter.Selection route, String path, JsonRenderer renderer, int errorId) {
        int request = generation;
        NasBridgeClient client = new NasBridgeClient(); clients.add(client);
        jobs.add(worker.submit(() -> {
            if (request != generation) return;
            try {
                JSONObject data = client.getJson(p -> route.openConnection(p), path);
                if ("error".equals(data.optString("status"))) throw new IOException(data.optString("message", "服务数据读取失败"));
                activity.runOnUiThread(() -> {
                    if (!visible || request != generation) return;
                    renderer.render(data);
                    text(R.id.home_overview_status).setText(route.baseUrl + "\n更新时间：" + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()));
                });
            } catch (IOException error) {
                activity.runOnUiThread(() -> {
                    if (!visible || request != generation) return;
                    text(errorId).setText("读取失败：" + error.getMessage());
                    if (errorId == R.id.home_mihomo_detail) text(R.id.home_mihomo_status).setText("状态暂不可用");
                });
            }
        }));
    }

    private void renderArticles(HostRouter.Selection route, JSONObject data) {
        lastTasks = data; lastArticleRoute = route;
        JSONArray summaries = data.optJSONArray("summaries");
        LinearLayout target = list(R.id.home_articles); target.removeAllViews();
        int count = 0; long now = System.currentTimeMillis();
        for (int i = 0; summaries != null && i < summaries.length(); i++) {
            JSONObject item = summaries.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("summary_filename", "");
            String stamp = item.optString("updated_at", "");
            if (id.isEmpty() || !OverviewPolicy.isTodayUnread(stamp, item.optBoolean("is_read"), item.optString("video_id", ""), now)
                    || articles.isRead(route.baseUrl, id)) continue;
            String preview = item.optString("summary_markdown", "");
            String title = articleTitle(preview, id);
            ArticleStore.Article article = articles.put(route.baseUrl, id, title, stamp, preview);
            LinearLayout card = card(target);
            card.addView(label(title, 17, "#3B3A49"));
            TextView meta = label("今日新文章 · 未读", 12, "#706B75"); meta.setPadding(0, dp(8), 0, 0); card.addView(meta);
            Button open = new Button(activity); open.setText("快速阅读文章　↗"); open.setTextColor(Color.parseColor("#3B3A49"));
            open.setBackgroundResource(R.drawable.button); open.setTag("article:" + id);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.topMargin = dp(14); open.setLayoutParams(params);
            open.setOnClickListener(v -> activity.startActivity(new Intent(activity, ArticleActivity.class)
                .putExtra("article_key", article.key).putExtra("article_id", article.id).putExtra("article_title", article.title)));
            card.addView(open); count++;
        }
        text(R.id.home_articles_status).setText(count == 0 ? "今日没有未读文章" : "今日 " + count + " 篇未读文章，已读后将隐藏");
    }

    private void renderCertificates(JSONObject data) {
        LinearLayout target = list(R.id.home_certificates); target.removeAllViews();
        JSONArray certificates = data.optJSONArray("certificates");
        int count = certificates == null ? 0 : certificates.length();
        text(R.id.home_certificates_status).setText(count == 0 ? "暂无反向代理证书" : "超过 15 天为绿色，15 天及以内为黄色");
        for (int i = 0; certificates != null && i < Math.min(certificates.length(), 100); i++) {
            JSONObject cert = certificates.optJSONObject(i); if (cert == null) continue;
            Double days = OverviewPolicy.daysRemaining(cert.optString("expires_at", ""), System.currentTimeMillis());
            LinearLayout card = card(target);
            card.addView(label(cert.optString("name", "未命名证书"), 16, "#3B3A49"));
            String lifetime = days == null ? "有效期未知" : days < 0 ? String.format(Locale.getDefault(), "已过期 %.1f 天", -days)
                : String.format(Locale.getDefault(), "剩余 %.1f 天", days);
            TextView value = label(lifetime, 18, OverviewPolicy.certificateColor(days)); value.setTag("certificate:" + cert.optString("id", ""));
            value.setPadding(0, dp(10), 0, dp(6)); card.addView(value);
            JSONArray domains = cert.optJSONArray("domains");
            if (domains != null) { StringBuilder names = new StringBuilder(); for (int j = 0; j < domains.length(); j++) { if (j > 0) names.append("、"); names.append(domains.optString(j)); } card.addView(label(names.toString(), 12, "#706B75")); }
        }
    }

    private void checkGoogle() {
        if (googleRunning) return;
        HostRouter.Selection route = monitor.routeForRequests();
        if (route == null) { text(R.id.home_google_result).setText("请先配置访问地址，或等待网络切换完成。"); return; }
        googleRunning = true; activity.findViewById(R.id.home_google_check).setEnabled(false);
        text(R.id.home_google_result).setText("正在从 NAS 经 Mihomo 检测 Google…");
        int request = generation;
        NasBridgeClient client = new NasBridgeClient(); clients.add(client);
        jobs.add(worker.submit(() -> {
            try {
                JSONObject result = client.googleCheck(p -> route.openConnection(p));
                activity.runOnUiThread(() -> {
                    if (!visible || request != generation) return;
                    boolean success = result.optBoolean("success");
                    text(R.id.home_google_result).setText(result.optString("message", success ? "Google 连接成功" : "Google 连接失败")
                        + (result.has("delay_ms") ? " · " + result.optLong("delay_ms") + " ms" : "") + "\n检测路径：NAS → Mihomo → Google");
                    text(R.id.home_google_result).setTextColor(Color.parseColor(success ? OverviewPolicy.GREEN : OverviewPolicy.YELLOW));
                    googleRunning = false; activity.findViewById(R.id.home_google_check).setEnabled(true);
                });
            } catch (IOException error) {
                activity.runOnUiThread(() -> {
                    if (!visible || request != generation) return;
                    text(R.id.home_google_result).setText("Google 检测失败：" + error.getMessage());
                    text(R.id.home_google_result).setTextColor(Color.parseColor(OverviewPolicy.YELLOW));
                    googleRunning = false; activity.findViewById(R.id.home_google_check).setEnabled(true);
                });
            }
        }));
    }
    private String articleTitle(String markdown, String fallback) {
        for (String line : markdown.split("\n")) if (line.startsWith("# ")) return line.substring(2).trim();
        return fallback;
    }
    private LinearLayout card(LinearLayout target) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.card); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12); card.setLayoutParams(params); target.addView(card); return card;
    }
    private TextView label(String value, int size, String color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(Color.parseColor(color)); view.setLineSpacing(dp(4), 1); return view; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void cancel() { generation++; for (NasBridgeClient client : clients) client.cancel(); for (Future<?> job : jobs) job.cancel(true); clients.clear(); jobs.clear(); googleRunning = false; }
}
