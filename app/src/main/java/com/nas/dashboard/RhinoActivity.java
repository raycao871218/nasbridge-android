package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.NasBridgeClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated, recycled video list. Thumbnails come from the NAS, never from third-party sites. */
public final class RhinoActivity extends Activity {
    private static final class Video {
        final String id, title, thumb, version;
        final long size;
        Video(JSONObject json) {
            id=json.optString("video_id"); title=json.optString("title", "视频"); size=json.optLong("size");
            version=json.optString("thumbnail_version", "");
            thumb="api/videos/"+id+"/thumbnail"+(version.matches("[a-f0-9]{16}") ? "?v="+version : "");
        }
    }
    private final List<Video> videos=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Set<HttpURLConnection> connections=java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> pending=java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ExecutorService thumbnails;
    private NasBridgeClient client;
    private HostRouter.Selection route;
    private TextView status;
    private VideoAdapter adapter;
    private volatile int generation;
    private boolean visible;
    private ConnectionMonitor monitor;
    private HostRouter.Selection loadedRoute;
    private final ConnectionMonitor.Listener routeListener=state -> {
        HostRouter.Selection next=monitor.routeForRequests();
        if(next==null) {
            stopRequests(); loadedRoute=null;
            status.setText(state.checking ? "正在自动选择访问地址，选好后自动加载…" : "请在网络配置中保存 Host 和端口");
        } else if(next!=loadedRoute) load();
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_rhino);
        status=findViewById(R.id.rhino_status);
        monitor=((DashboardApplication)getApplication()).monitor();
        ListView list=findViewById(R.id.rhino_list); adapter=new VideoAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id) -> {
            Video video=videos.get(position);
            startActivity(new Intent(this,VideoActivity.class).putExtra("video_id",video.id).putExtra("article_title",video.title));
        });
        findViewById(R.id.rhino_back).setOnClickListener(v -> finish());
        findViewById(R.id.rhino_refresh).setOnClickListener(v -> load());
    }
    private void load() {
        stopRequests();
        route=monitor.routeForRequests();
        loadedRoute=route;
        videos.clear(); adapter.notifyDataSetChanged();
        if(route==null) { status.setText(monitor.current().checking ? "正在自动选择访问地址，选好后自动加载…" : "请在网络配置中保存 Host 和端口"); return; }
        int request=generation;
        HostRouter.Selection selection=route;
        thumbnails=Executors.newFixedThreadPool(2);
        status.setText(R.string.rhino_loading);
        NasBridgeClient api=new NasBridgeClient(); client=api;
        worker.execute(() -> {
            try {
                JSONArray items=api.getJson(selection::openConnection,"api/videos").optJSONArray("items");
                List<Video> loaded=new ArrayList<>();
                for(int i=0;items!=null && i<Math.min(items.length(),200);i++) {
                    JSONObject item=items.optJSONObject(i); if(item==null)continue;
                    Video video=new Video(item);
                    if(video.id.matches("[A-Za-z0-9_-]{1,64}")) loaded.add(video);
                }
                runOnUiThread(() -> {
                    if(!visible || request!=generation)return;
                    videos.addAll(loaded); adapter.notifyDataSetChanged();
                    status.setText(loaded.isEmpty() ? "暂无已下载视频" : "共 "+loaded.size()+" 个视频 · 点击即可播放");
                });
            } catch(Exception error) {
                runOnUiThread(() -> { if(visible && request==generation) status.setText("视频列表加载失败，点击刷新重试\n"+error.getMessage()); });
            }
        });
    }
    private void thumbnail(Video video, ImageView view) {
        String key=route.baseUrl+video.thumb;
        view.setTag(key);
        Bitmap cached=images.get(key);
        view.setImageBitmap(cached);
        if(cached!=null || thumbnails==null || !pending.add(key))return;
        int request=generation;
        HostRouter.Selection selection=route;
        thumbnails.execute(() -> {
            HttpURLConnection connection=null;
            Bitmap bitmap=null;
            try {
                if(request!=generation)return;
                connection=selection.openConnection(video.thumb); connections.add(connection);
                if(request!=generation)return;
                connection.setConnectTimeout(5000); connection.setReadTimeout(45000); connection.setInstanceFollowRedirects(false);
                if(connection.getResponseCode()!=200)return;
                try(InputStream input=connection.getInputStream(); ByteArrayOutputStream data=new ByteArrayOutputStream()) {
                    byte[] bytes=new byte[8192]; int count;
                    while((count=input.read(bytes))!=-1) {
                        if(request!=generation || data.size()+count>512*1024)return;
                        data.write(bytes,0,count);
                    }
                    byte[] raw=data.toByteArray();
                    BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
                    if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>1024 || bounds.outHeight>1024)return;
                    bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length);
                }
                if(bitmap!=null && request==generation)images.put(key,bitmap);
            } catch(Exception ignored) {
                // Keep the playable row and placeholder when a thumbnail is unavailable.
            } finally {
                if(connection!=null) { connections.remove(connection); connection.disconnect(); }
                pending.remove(key);
                Bitmap result=bitmap;
                runOnUiThread(() -> {
                    if(!visible || request!=generation || result==null)return;
                    if(key.equals(view.getTag())) view.setImageBitmap(result);
                    // A recycled row may now be showing another item waiting on this same request.
                    adapter.notifyDataSetChanged();
                });
            }
        });
    }
    private final class VideoAdapter extends BaseAdapter {
        public int getCount() { return videos.size(); }
        public Video getItem(int position) { return videos.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position,View recycled,ViewGroup parent) {
            View row=recycled==null ? getLayoutInflater().inflate(R.layout.item_rhino_video,parent,false) : recycled;
            Video video=getItem(position); row.setTag("video:"+video.id);
            ((TextView)row.findViewById(R.id.rhino_video_title)).setText(video.title.replaceFirst("^\\d{8}[-_ ]?", ""));
            String date=video.title.matches("^\\d{8}.*") ? video.title.substring(0,4)+"-"+video.title.substring(4,6)+"-"+video.title.substring(6,8)+" · " : "";
            ((TextView)row.findViewById(R.id.rhino_video_meta)).setText(date+String.format(Locale.ROOT,"%.0f MB",video.size/1048576.0));
            row.setContentDescription(video.title+"，点击播放");
            thumbnail(video,row.findViewById(R.id.rhino_thumb));
            return row;
        }
    }
    private void stopRequests() {
        generation++;
        if(client!=null) { client.cancel(); client=null; }
        if(thumbnails!=null) { thumbnails.shutdownNow(); thumbnails=null; }
        for(HttpURLConnection connection:connections)connection.disconnect();
        connections.clear(); pending.clear();
    }
    @Override protected void onStart() { super.onStart(); visible=true; loadedRoute=null; monitor.addListener(routeListener); }
    @Override protected void onStop() { visible=false; monitor.removeListener(routeListener); loadedRoute=null; stopRequests(); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); images.evictAll(); super.onDestroy(); }
}
