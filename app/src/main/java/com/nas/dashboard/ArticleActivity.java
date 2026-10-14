package com.nas.dashboard;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.widget.TextView;
import android.widget.Toast;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.NasBridgeClient;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ArticleActivity extends Activity {
    private ArticleStore store;
    private ArticleStore.Article article;
    private TextView status;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private volatile NasBridgeClient client;
    private Future<?> job;
    private boolean saving;
    private ConnectionMonitor monitor;
    private HostRouter.Selection loadedRoute;
    private final ConnectionMonitor.Listener routeListener=state -> {
        if(article!=null && !saving && monitor.routeForRequests()!=loadedRoute) load();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_article);
        store = ((DashboardApplication) getApplication()).articles();
        monitor = ((DashboardApplication)getApplication()).monitor();
        article = store.get(getIntent().getStringExtra("article_key"));
        if (article == null) {
            String id = getIntent().getStringExtra("article_id");
            HostRouter.Selection route = ((DashboardApplication) getApplication()).monitor().routeForRequests();
            if (id != null && route != null) article = store.put(route.baseUrl, id, getIntent().getStringExtra("article_title"), "", "");
        }
        if (article == null) { Toast.makeText(this, "请返回总览重新打开文章", Toast.LENGTH_LONG).show(); finish(); return; }
        ((TextView) findViewById(R.id.article_title)).setText(article.title);
        status = findViewById(R.id.article_status);
        findViewById(R.id.article_back).setOnClickListener(v -> finish());
        findViewById(R.id.article_read_top).setOnClickListener(v -> markRead());
        findViewById(R.id.article_read_bottom).setOnClickListener(v -> markRead());
        findViewById(R.id.article_play_video).setOnClickListener(v -> startActivity(new android.content.Intent(this, VideoActivity.class)
            .putExtra("article_id", article.id).putExtra("article_title", article.title)));
        buttons(false);
    }

    private HostRouter.Selection route() { return monitor.routeForRequests(); }
    private void buttons(boolean enabled) {
        findViewById(R.id.article_read_top).setEnabled(enabled);
        findViewById(R.id.article_read_bottom).setEnabled(enabled);
    }
    private void load() {
        cancel(); buttons(false);
        HostRouter.Selection route = route();
        loadedRoute = route;
        if (route == null) { status.setText(monitor.current().checking ? "正在自动选择访问地址，选好后自动加载…" : "请在网络配置中保存 Host 和端口"); return; }
        int request = generation;
        status.setText("正在读取完整文章…");
        NasBridgeClient next = new NasBridgeClient(); client = next;
        job = worker.submit(() -> {
            if (request != generation) return;
            try {
                JSONObject data = next.article(path -> route.openConnection(path), article.id);
                String markdown = data.optString("summary_markdown", "");
                if (markdown.trim().isEmpty()) throw new IOException("文章内容为空");
                Spanned content = renderMarkdown(markdown);
                runOnUiThread(() -> {
                    if (request != generation) return;
                    boolean read = data.optBoolean("is_read");
                    if (read) {
                        store.markRead(article.key);
                        if (!getIntent().getBooleanExtra("allow_read", false)) { finish(); return; }
                    }
                    ((TextView) findViewById(R.id.article_body)).setText(content);
                    status.setText(read ? "已读" : "未读 · 阅读后可在顶部或底部标记已读");
                    ((android.widget.Button)findViewById(R.id.article_read_top)).setText(read ? "已读" : getString(R.string.article_mark_read));
                    ((android.widget.Button)findViewById(R.id.article_read_bottom)).setText(read ? "已读" : getString(R.string.article_mark_read));
                    buttons(!read);
                });
            } catch (IOException error) {
                runOnUiThread(() -> { if (request == generation) status.setText("文章读取失败：" + error.getMessage()); });
            }
        });
    }

    private void markRead() {
        if (saving) return;
        HostRouter.Selection route = route();
        if (route == null) { Toast.makeText(this, "网络切换中，请稍后重试", Toast.LENGTH_LONG).show(); return; }
        cancel(); saving = true; buttons(false);
        status.setText("正在同步已读状态…");
        int request = generation;
        NasBridgeClient next = new NasBridgeClient(); client = next;
        job = worker.submit(() -> {
            if (request != generation) return;
            try {
                next.markArticleRead(path -> route.openConnection(path), article.id);
                runOnUiThread(() -> {
                    if (request != generation) return;
                    store.markRead(article.key);
                    Toast.makeText(this, "已标记已读", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (request != generation) return;
                    saving = false; buttons(true);
                    status.setText("已读状态保存失败，请重试");
                    Toast.makeText(this, "保存失败，文章仍保留未读：" + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static Spanned renderMarkdown(String markdown) {
        StringBuilder html = new StringBuilder(); boolean code = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.trim().startsWith("```")) { html.append(code ? "</pre>" : "<pre>"); code = !code; continue; }
            String escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            if (code) { html.append(escaped).append("\n"); continue; }
            escaped = escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
            if (escaped.startsWith("### ")) html.append("<h3>").append(escaped.substring(4)).append("</h3>");
            else if (escaped.startsWith("## ")) html.append("<h2>").append(escaped.substring(3)).append("</h2>");
            else if (escaped.startsWith("# ")) html.append("<h1>").append(escaped.substring(2)).append("</h1>");
            else if (escaped.trim().isEmpty()) html.append("<br>");
            else html.append(escaped.startsWith("- ") ? "• " + escaped.substring(2) : escaped).append("<br>");
        }
        if (code) html.append("</pre>");
        return Html.fromHtml(html.toString());
    }
    private void cancel() { generation++; if (client != null) client.cancel(); if (job != null) job.cancel(true); client = null; job = null; saving = false; }
    @Override protected void onStart() { super.onStart(); if (article != null) { loadedRoute=null; monitor.addListener(routeListener); if(route()==null) load(); } }
    @Override protected void onStop() { monitor.removeListener(routeListener); loadedRoute=null; cancel(); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
