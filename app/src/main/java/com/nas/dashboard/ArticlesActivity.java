package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.NasBridgeClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compact paged archive; opening an article never writes read status. */
public final class ArticlesActivity extends Activity {
    private final List<JSONObject> rows=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ConnectionMonitor monitor;
    private HostRouter.Selection loadedRoute;
    private NasBridgeClient client;
    private TextView status;
    private Button more;
    private ArticleAdapter adapter;
    private boolean visible, loading, hasMore;
    private int generation;
    private final ConnectionMonitor.Listener listener=state -> {
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null) {
            cancel(); loadedRoute=null;
            status.setText(state.checking ? "正在自动选择访问地址，选好后自动加载…" : "请先配置 Host 和端口");
        } else if(route!=loadedRoute) load(true);
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_articles);
        monitor=((DashboardApplication)getApplication()).monitor();
        status=findViewById(R.id.articles_status);
        ListView list=findViewById(R.id.articles_list);
        more=new Button(this); more.setText(R.string.articles_more); more.setTag("articles:more"); more.setTextColor(android.graphics.Color.parseColor("#75F0D2"));
        more.setBackgroundColor(android.graphics.Color.TRANSPARENT); list.addFooterView(more,null,false);
        more.setVisibility(View.GONE);
        more.setOnClickListener(v -> load(false));
        adapter=new ArticleAdapter(); list.setAdapter(adapter);
        findViewById(R.id.articles_back).setOnClickListener(v -> finish());
        findViewById(R.id.articles_refresh).setOnClickListener(v -> load(true));
        list.setOnItemClickListener((parent,view,position,id) -> {
            if(position>=rows.size() || loadedRoute==null)return;
            JSONObject row=rows.get(position);
            String articleId=row.optString("summary_filename");
            ArticleStore.Article article=((DashboardApplication)getApplication()).articles().put(loadedRoute.baseUrl,articleId,
                title(row),row.optString("updated_at"),row.optString("preview"));
            startActivity(new Intent(this,ArticleActivity.class).putExtra("article_key",article.key)
                .putExtra("article_id",article.id).putExtra("article_title",article.title).putExtra("allow_read",true));
        });
    }
    private void load(boolean reset) {
        if(loading && !reset)return;
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null)return;
        if(reset) { cancel(); rows.clear(); adapter.notifyDataSetChanged(); }
        loadedRoute=route; loading=true; more.setEnabled(false); status.setText(R.string.articles_loading);
        int request=generation, offset=rows.size();
        NasBridgeClient api=new NasBridgeClient(); client=api;
        worker.execute(() -> {
            try {
                JSONObject data=api.getJson(route::openConnection,"api/articles?offset="+offset+"&limit=40");
                JSONArray items=data.getJSONArray("items");
                List<JSONObject> page=new ArrayList<>();
                for(int i=0;i<items.length();i++)if(items.optJSONObject(i)!=null)page.add(items.getJSONObject(i));
                runOnUiThread(() -> {
                    if(!visible || request!=generation)return;
                    rows.addAll(page); hasMore=data.optBoolean("has_more"); loading=false;
                    more.setVisibility(hasMore ? View.VISIBLE : View.GONE); more.setEnabled(hasMore);
                    status.setText(rows.isEmpty() ? "暂无文章" : "已加载 "+rows.size()+" 篇 · 点击摘要阅读全文"); adapter.notifyDataSetChanged();
                });
            } catch(Exception error) {
                runOnUiThread(() -> {
                    if(!visible || request!=generation)return;
                    loading=false; status.setText("文章加载失败，点击刷新重试\n"+error.getMessage());
                    more.setEnabled(hasMore);
                });
            }
        });
    }
    private static String title(JSONObject row) { return row.optString("summary_filename","文章").replaceFirst("\\.md$", ""); }
    private static String preview(JSONObject row) {
        String text=row.optString("preview","").replaceAll("(?m)^#{1,6}\\s+.*$", "")
            .replaceAll("[*_`>#]", "").replaceAll("\\s+", " ").trim();
        return text.length()>180 ? text.substring(0,180)+"…" : text;
    }
    private final class ArticleAdapter extends BaseAdapter {
        public int getCount(){return rows.size();}
        public Object getItem(int i){return rows.get(i);}
        public long getItemId(int i){return i;}
        public View getView(int i,View recycled,ViewGroup parent){
            View view=recycled==null ? getLayoutInflater().inflate(R.layout.item_article,parent,false) : recycled;
            JSONObject row=rows.get(i); String id=row.optString("summary_filename");
            view.setTag("archive:"+id);
            ((TextView)view.findViewById(R.id.articles_row_title)).setText(title(row).replaceFirst("^\\d{8}[-_ ]?", ""));
            ((TextView)view.findViewById(R.id.articles_row_preview)).setText(preview(row));
            String stamp=row.optString("updated_at"); if(stamp.length()>10)stamp=stamp.substring(0,10);
            ((TextView)view.findViewById(R.id.articles_row_meta)).setText(stamp+" · "+(row.optBoolean("is_read") ? "已读" : "未读"));
            return view;
        }
    }
    private void cancel(){generation++; loading=false; if(client!=null){client.cancel();client=null;}}
    @Override protected void onStart(){super.onStart(); visible=true; loadedRoute=null; monitor.addListener(listener);}
    @Override protected void onStop(){visible=false; monitor.removeListener(listener);cancel();super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
