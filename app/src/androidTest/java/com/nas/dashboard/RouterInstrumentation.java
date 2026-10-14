package com.nas.dashboard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.TextView;
import android.view.View;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.HostSettings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only tests. Uses a local server and restores all saved Host configuration. */
@android.annotation.TargetApi(26)
public final class RouterInstrumentation extends Instrumentation {
    private int checks;
    private final java.util.Set<String> readArticles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean failReadOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicBoolean failHead = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger googleRequests = new AtomicInteger();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private String text(Activity activity, int id) {
        AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> value.set(((TextView) activity.findViewById(id)).getText().toString()));
        return value.get();
    }
    private void waitForText(Activity activity, int id, String expected) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 12000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (expected.equals(text(activity, id))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Expected " + expected + ", got " + text(activity, id));
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) { }
        }
    }
    private Activity openNetwork(Activity main) {
        ActivityMonitor monitor = addMonitor(NetworkSettingsActivity.class.getName(), null, false);
        runOnMainSync(() -> {
            main.findViewById(R.id.tab_settings).performClick();
            main.findViewById(R.id.network_settings_button).performClick();
        });
        Activity activity = waitForMonitorWithTimeout(monitor, 3000);
        if (activity == null) throw new AssertionError("Network settings did not open");
        return activity;
    }
    private void waitForRequests(AtomicInteger requests, int previous) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (requests.get() > previous) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Network change did not trigger detection");
    }
    private Activity openModule(Activity main, int id) {
        ActivityMonitor monitor = addMonitor(ServiceModuleActivity.class.getName(), null, false);
        runOnMainSync(() -> { main.findViewById(R.id.tab_services).performClick(); main.findViewById(id).performClick(); });
        Activity activity = waitForMonitorWithTimeout(monitor, 3000);
        if (activity == null) throw new AssertionError("Service module did not open");
        return activity;
    }

    private String stamp(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new java.util.Date(millis));
    }
    private String fixture(String path) throws Exception {
        if(path.startsWith("/api/articles?")) {
            boolean next=path.contains("offset=2");
            org.json.JSONArray items=new org.json.JSONArray();
            for(String id: next ? new String[]{"third.md"} : new String[]{"first.md","read.md"}) {
                StringBuilder preview=new StringBuilder();for(int i=0;i<80;i++)preview.append("Compact preview paragraph. ");
                items.put(new org.json.JSONObject().put("summary_filename",id).put("video_id","video")
                    .put("updated_at",stamp(System.currentTimeMillis())).put("preview",preview.toString()).put("is_read",id.equals("read.md")));
            }
            return new org.json.JSONObject().put("items",items).put("has_more",!next).toString();
        }
        if (path.startsWith("/api/daily-tasks/article?id=")) {
            String id = java.net.URLDecoder.decode(path.substring(path.indexOf('=') + 1), "UTF-8");
            StringBuilder body = new StringBuilder("# Full article\n");
            for (int i = 0; i < 900; i++) body.append("A complete article paragraph.\n");
            body.append("FULL_ARTICLE_END");
            return new org.json.JSONObject().put("summary_filename", id).put("summary_markdown", body.toString())
                .put("is_read", id.equals("read.md") || readArticles.contains(id)).toString();
        }
        switch (path) {
            case "/api/videos": return "{\"items\":[{\"video_id\":\"test-video\",\"title\":\"Downloaded video\",\"size\":123456}]}";
            case "/api/health": return "{\"status\":\"ok\"}";
            case "/api/overview": return "{\"app\":{\"name\":\"NASBridge\"},\"mihomo\":{\"status\":\"online\",\"connection_count\":2},\"applications\":[],\"certificates\":[]}";
            case "/api/daily-tasks": {
                org.json.JSONArray articles = new org.json.JSONArray();
                for (String id : new String[]{"first.md", "second.md", "yesterday.md"}) {
                    articles.put(new org.json.JSONObject().put("summary_filename", id).put("video_id", "video")
                        .put("updated_at", stamp(System.currentTimeMillis() - (id.equals("yesterday.md") ? 86400000L : 0)))
                        .put("summary_markdown", "# " + id + "\nPreview")
                        .put("is_read", readArticles.contains(id)));
                }
                return new org.json.JSONObject().put("status", "ok").put("metrics", new org.json.JSONObject().put("today_runs", 1))
                    .put("runs", new org.json.JSONArray()).put("summaries", articles).toString();
            }
            case "/api/reverse-proxies": {
                org.json.JSONArray certificates = new org.json.JSONArray();
                certificates.put(new org.json.JSONObject().put("id", "long").put("name", "Long certificate").put("expires_at", stamp(System.currentTimeMillis() + 22 * 86400000L)));
                certificates.put(new org.json.JSONObject().put("id", "short").put("name", "Short certificate").put("expires_at", stamp(System.currentTimeMillis() + 10 * 86400000L)));
                return new org.json.JSONObject().put("status", "ok").put("proxies", new org.json.JSONArray()).put("certificates", certificates).toString();
            }
            case "/api/mihomo/google-check": googleRequests.incrementAndGet(); return "{\"status\":\"ok\",\"success\":true,\"message\":\"Google 连接成功\",\"delay_ms\":100}";
            case "/api/aliyun/status": return "{\"configured\":true}";
            case "/api/zerotier/networks": return "{\"items\":[{\"id\":\"test-network\",\"name\":\"Test network\",\"private\":true}]}";
            default: return "{}";
        }
    }
    private View tagged(Activity activity, String tag) {
        AtomicReference<View> result = new AtomicReference<>();
        runOnMainSync(() -> result.set(activity.findViewById(android.R.id.content).findViewWithTag(tag)));
        return result.get();
    }
    private void waitForTag(Activity activity, String tag) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 12000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (tagged(activity, tag) != null) return; Thread.sleep(100); }
        throw new AssertionError("Missing " + tag);
    }
    private Activity openArticle(Activity main, String id) throws Exception {
        waitForTag(main, "article:" + id);
        View button = tagged(main, "article:" + id);
        ActivityMonitor monitor = addMonitor(ArticleActivity.class.getName(), null, false);
        runOnMainSync(button::performClick);
        Activity article = waitForMonitorWithTimeout(monitor, 3000);
        if (article == null) throw new AssertionError("Article did not open");
        waitForText(article, R.id.article_status, "未读 · 阅读后可在顶部或底部标记已读");
        return article;
    }

    @Override public void onStart() {
        waitForIdleSync();
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("network_hosts", Context.MODE_PRIVATE);
        Map<String, ?> backup = preferences.getAll();
        ConnectionMonitor monitor = ((DashboardApplication) context.getApplicationContext()).monitor();
        Bundle result = new Bundle();
        ServerSocket server = null;
        boolean wifiChanged = false;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean wifiEnabled = wifi.isWifiEnabled();
        try {
            if (!android.os.Build.MODEL.contains("sdk")) throw new AssertionError("Emulator-only integration test");
            server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            ServerSocket running = server;
            AtomicInteger requests = new AtomicInteger();
            AtomicInteger headRequests = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            Thread responder = new Thread(() -> {
                while (!running.isClosed()) {
                    try (Socket socket = running.accept()) {
                        socket.setSoTimeout(2000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String first = reader.readLine();
                        if (first == null) continue;
                        String[] request = first.split(" ");
                        boolean head = "HEAD".equals(request[0]);
                        if (head) headRequests.incrementAndGet();
                        if (head && failHead.get()) { Thread.sleep(700); continue; }
                        if (!head && !"GET".equals(request[0])) writes.incrementAndGet();
                        String line; int length = 0;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.US).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        }
                        requests.incrementAndGet();
                        int responseCode = 200;
                        String response;
                        if ("POST".equals(request[0]) && request[1].equals("/api/daily-tasks/state")) {
                            char[] posted = new char[length]; int read = 0;
                            while (read < length) { int n = reader.read(posted, read, length - read); if (n < 0) break; read += n; }
                            org.json.JSONObject payload = new org.json.JSONObject(new String(posted));
                            if (!"read_summary".equals(payload.optString("action"))) throw new AssertionError("Unexpected write action");
                            String id = payload.getString("id");
                            if (id.equals("second.md") && failReadOnce.getAndSet(false)) { responseCode = 500; response = "{}"; }
                            else { readArticles.add(id); response = "{\"status\":\"ok\"}"; }
                        } else response = fixture(request[1]);
                        boolean thumbnail=request[1].startsWith("/api/videos/test-video/thumbnail");
                        byte[] body = thumbnail ? android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=",0) : response.getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 " + responseCode + " Result\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        if (!head) socket.getOutputStream().write(body);
                    } catch (Exception failure) { if (running.isClosed()) return; }
                }
            });
            responder.setDaemon(true);
            responder.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
            runOnMainSync(() -> {
                new HostSettings(context).save(new HostConfig("test-not-current-wifi", url, url));
                monitor.configurationChanged();
            });
            Activity main = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1000);
            require("总览".equals(text(main, R.id.page_title)), "Overview should be default tab");
            runOnMainSync(() -> main.findViewById(R.id.tab_notifications).performClick());
            require("通知".equals(text(main, R.id.page_title)), "Notifications tab");
            require(headRequests.get() == 0, "Opening overview may read business data but must not probe Host");
            for (int tabId : new int[]{R.id.tab_overview, R.id.tab_services, R.id.tab_notifications, R.id.tab_settings}) {
                java.util.concurrent.atomic.AtomicBoolean icon = new java.util.concurrent.atomic.AtomicBoolean();
                runOnMainSync(() -> icon.set(((TextView) main.findViewById(tabId)).getCompoundDrawables()[1] != null));
                require(icon.get(), "Bottom tabs must include icons");
            }
            int[] moduleIds = {R.id.module_mihomo, R.id.module_tasks, R.id.module_certificates, R.id.module_aliyun, R.id.module_zerotier};
            String[] moduleTitles = {"Mihomo 代理", "每日任务", "证书与反向代理", "阿里云安全组", "ZeroTier"};
            for (int i = 0; i < moduleIds.length; i++) {
                Activity page = openModule(main, moduleIds[i]);
                require(moduleTitles[i].equals(text(page, R.id.module_title)), "Directory opens matching native page");
                waitForText(page, R.id.overview_status, "数据已更新");
                require(!monitor.current().success,"Modules load business data before any successful diagnostic");
                runOnMainSync(() -> page.findViewById(R.id.overview_back).performClick());
                waitForIdleSync();
            }
            require(headRequests.get() == 0, "Directory navigation must not trigger Host detection");
            Activity form = openNetwork(main);
            require("地址已选择".equals(text(form, R.id.connection_status)), "Address is ready without a diagnostic");
            runOnMainSync(() -> form.findViewById(R.id.save_button).performClick());
            Thread.sleep(800);
            require(!form.isFinishing(), "Save should stay in network settings");
            require(headRequests.get() == 0, "Saving configuration must not probe");
            runOnMainSync(() -> form.findViewById(R.id.refresh_button).performClick());
            waitForText(form, R.id.connection_status, "连接成功");
            require(requests.get() > 0, "Manual detection must make a real HTTP request");
            failHead.set(true);
            java.util.concurrent.atomic.AtomicBoolean usableDuringDiagnostic=new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> {
                form.findViewById(R.id.refresh_button).performClick();
                usableDuringDiagnostic.set(monitor.current().checking && monitor.routeForRequests()!=null);
            });
            require(usableDuringDiagnostic.get(),"In-progress HTTP diagnostic must not block API route");
            waitForText(form,R.id.connection_status,"连接失败");
            require(!monitor.current().reachable && monitor.routeForRequests()!=null,"Failed diagnostic must not disable API route");
            failHead.set(false);
            runOnMainSync(() -> form.findViewById(R.id.back_button).performClick());
            waitForIdleSync();
            int probesBeforeModules = headRequests.get();
            for (int i = 0; i < moduleIds.length; i++) {
                Activity page = openModule(main, moduleIds[i]);
                waitForText(page, R.id.overview_status, "数据已更新");
                require(moduleTitles[i].equals(text(page, R.id.module_title)), "Module reads native API data");
                if (moduleIds[i] == R.id.module_tasks) require(tagged(page,"video:test-video")==null,"Daily tasks no longer contains video playback list");
                runOnMainSync(() -> page.findViewById(R.id.overview_back).performClick());
                waitForIdleSync();
            }
            ActivityMonitor archiveMonitor=addMonitor(ArticlesActivity.class.getName(),null,false);
            runOnMainSync(() -> { main.findViewById(R.id.tab_services).performClick();main.findViewById(R.id.module_articles).performClick(); });
            Activity archive=waitForMonitorWithTimeout(archiveMonitor,3000);
            require(archive!=null,"Independent article archive opens");
            waitForTag(archive,"archive:first.md");
            View firstRow=tagged(archive,"archive:first.md");
            java.util.concurrent.atomic.AtomicInteger previewLength=new java.util.concurrent.atomic.AtomicInteger();
            runOnMainSync(() -> previewLength.set(((TextView)firstRow.findViewById(R.id.articles_row_preview)).getText().length()));
            require(previewLength.get()<=181,"Article list only shows a short preview");
            View more=tagged(archive,"articles:more");runOnMainSync(more::performClick);
            waitForTag(archive,"archive:third.md"); require(tagged(archive,"archive:third.md")!=null,"Article archive loads next page");
            ActivityMonitor historyReaderMonitor=addMonitor(ArticleActivity.class.getName(),null,false);
            runOnMainSync(() -> {
                android.widget.ListView list=archive.findViewById(R.id.articles_list);
                list.performItemClick(list.getChildAt(1),1,1);
            });
            Activity historyReader=waitForMonitorWithTimeout(historyReaderMonitor,3000);
            require(historyReader!=null,"Archive preview opens reader");
            waitForText(historyReader,R.id.article_status,"已读");
            require(!historyReader.isFinishing() && text(historyReader,R.id.article_body).contains("FULL_ARTICLE_END"),"Already-read archive article expands to full content");
            runOnMainSync(() -> historyReader.findViewById(R.id.article_back).performClick());waitForIdleSync();
            runOnMainSync(archive::finish);waitForIdleSync();
            ActivityMonitor rhinoMonitor=addMonitor(RhinoActivity.class.getName(),null,false);
            runOnMainSync(() -> { main.findViewById(R.id.tab_services).performClick(); main.findViewById(R.id.module_rhino).performClick(); });
            Activity rhino=waitForMonitorWithTimeout(rhinoMonitor,3000);
            require(rhino!=null,"Dedicated Rhino entry opens native video list");
            waitForText(rhino,R.id.rhino_status,"共 1 个视频 · 点击即可播放");
            waitForTag(rhino,"video:test-video");
            require(tagged(rhino,"video:test-video").findViewById(R.id.rhino_thumb)!=null,"Video row contains thumbnail and title");
            ActivityMonitor videoMonitor=addMonitor(VideoActivity.class.getName(),null,false);
            runOnMainSync(() -> { android.widget.ListView list=rhino.findViewById(R.id.rhino_list); list.performItemClick(list.getChildAt(0),0,0); });
            Activity video=waitForMonitorWithTimeout(videoMonitor,3000);
            require(video!=null && "test-video".equals(video.getIntent().getStringExtra("video_id")),"Rhino video row opens matching player");
            runOnMainSync(video::finish); waitForIdleSync();
            runOnMainSync(rhino::finish); waitForIdleSync();
            require(headRequests.get() == probesBeforeModules, "Module API reads must not repeat Host probes");
            require(writes.get() == 0, "Modules must only make read-only requests");
            runOnMainSync(() -> main.findViewById(R.id.tab_services).performClick());
            Thread.sleep(500);
            try (FileOutputStream file = context.openFileOutput("services-navigation.png", Context.MODE_PRIVATE)) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
            }
            runOnMainSync(() -> main.findViewById(R.id.tab_overview).performClick());
            waitForText(main, R.id.home_mihomo_status, "在线");
            waitForTag(main, "article:first.md");
            waitForTag(main, "certificate:short");
            require(tagged(main, "article:yesterday.md") == null, "Yesterday's articles must not appear");
            require(googleRequests.get() == 0, "Google check must be manually triggered");
            java.util.concurrent.atomic.AtomicInteger certColor = new java.util.concurrent.atomic.AtomicInteger();
            TextView longCertificate = (TextView) tagged(main, "certificate:long");
            TextView shortCertificate = (TextView) tagged(main, "certificate:short");
            runOnMainSync(() -> certColor.set(longCertificate.getCurrentTextColor()));
            require(certColor.get() == android.graphics.Color.parseColor(com.nas.dashboard.network.OverviewPolicy.GREEN), "Certificate over 15 days should be green");
            runOnMainSync(() -> certColor.set(shortCertificate.getCurrentTextColor()));
            require(certColor.get() == android.graphics.Color.parseColor(com.nas.dashboard.network.OverviewPolicy.YELLOW), "Certificate under 15 days should be yellow");
            runOnMainSync(() -> main.findViewById(R.id.home_google_check).performClick());
            long googleDeadline = android.os.SystemClock.elapsedRealtime() + 8000;
            while (!text(main, R.id.home_google_result).contains("Google 连接成功") && android.os.SystemClock.elapsedRealtime() < googleDeadline) Thread.sleep(100);
            require(googleRequests.get() == 1 && text(main, R.id.home_google_result).contains("Google 连接成功"), "Google button shows actual response");
            Activity firstArticle = openArticle(main, "first.md");
            require(text(firstArticle, R.id.article_body).contains("FULL_ARTICLE_END"), "Reader must fetch full text beyond preview");
            runOnMainSync(() -> firstArticle.findViewById(R.id.article_read_top).performClick());
            waitForText(main, R.id.home_articles_status, "今日 1 篇未读文章，已读后将隐藏");
            require(readArticles.contains("first.md") && tagged(main, "article:first.md") == null, "Top read button persists read state and hides article");
            Activity secondArticle = openArticle(main, "second.md");
            runOnMainSync(() -> secondArticle.findViewById(R.id.article_read_bottom).performClick());
            waitForText(secondArticle, R.id.article_status, "已读状态保存失败，请重试");
            require(!readArticles.contains("second.md") && !secondArticle.isFinishing(), "Failed read update must leave article unread");
            runOnMainSync(() -> secondArticle.findViewById(R.id.article_read_bottom).performClick());
            waitForText(main, R.id.home_articles_status, "今日没有未读文章");
            require(readArticles.contains("second.md") && tagged(main, "article:second.md") == null, "Bottom read button hides article after success");
            try (FileOutputStream file = context.openFileOutput("overview-home.png", Context.MODE_PRIVATE)) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
            }
            runOnMainSync(() -> main.findViewById(R.id.tab_services).performClick());
            Thread.sleep(500);
            int afterManual = requests.get();
            runOnMainSync(() -> {
                main.findViewById(R.id.tab_services).performClick();
                main.findViewById(R.id.tab_notifications).performClick();
            });
            Activity reopened = openNetwork(main);
            require("连接失败".equals(text(reopened, R.id.connection_status)), "Reopening preserves diagnostic result without gating business features");
            ActivityMonitor recreation = addMonitor(NetworkSettingsActivity.class.getName(), null, false);
            runOnMainSync(() -> reopened.recreate());
            Activity recreated = waitForMonitorWithTimeout(recreation, 3000);
            require(recreated != null, "Settings should recreate");
            require("连接失败".equals(text(recreated, R.id.connection_status)), "Recreation should retain diagnostic result");
            require(requests.get() == afterManual, "Tab/screen changes and recreation must not probe");
            Bundle progress = new Bundle();
            progress.putString("stream", "PASS: tabs, save without detection, manual detection and cached results. Checking 31 seconds without polling…\n");
            sendStatus(0, progress);
            Thread.sleep(31000);
            require(requests.get() == afterManual, "Idle must not trigger the removed 30-second timer");
            if (wifiEnabled) {
                wifiChanged = true;
                shell("svc wifi disable");
                waitForRequests(requests, afterManual);
                require(requests.get() > afterManual, "Wi-Fi disconnect automatically detects");
                Thread.sleep(1000);
                int afterDisconnect = requests.get();
                shell("svc wifi enable");
                waitForRequests(requests, afterDisconnect);
                require(requests.get() > afterDisconnect, "Wi-Fi reconnect automatically detects");
            }
            waitForText(recreated, R.id.connection_status, "连接成功");
            try (FileOutputStream file = context.openFileOutput("tabs-network-settings.png", Context.MODE_PRIVATE)) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
            }
            runOnMainSync(() -> recreated.findViewById(R.id.back_button).performClick());
            waitForIdleSync();
            Thread.sleep(300);
            try (FileOutputStream file = context.openFileOutput("tabs-settings.png", Context.MODE_PRIVATE)) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
            }
            result.putBoolean("passed", true);
            result.putString("stream", "PASS: " + checks + " tabs/event-driven detection checks\n");
        } catch (Throwable failure) {
            result.putBoolean("passed", false);
            result.putString("stream", "FAIL: " + failure + "\n");
        } finally {
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : backup.entrySet()) editor.putString(entry.getKey(), (String) entry.getValue());
            editor.commit();
            runOnMainSync(monitor::configurationChanged);
            if (wifiChanged) try { shell("svc wifi enable"); } catch (Exception ignored) { }
            if (server != null) try { server.close(); } catch (Exception ignored) { }
        }
        finish(result.getBoolean("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
