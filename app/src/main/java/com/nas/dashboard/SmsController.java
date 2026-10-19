package com.nas.dashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.nas.dashboard.network.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Read-only SMS archive. Uses the selected business route without a network probe. */
final class SmsController {
    private final Activity activity;
    private final View root;
    private final SmsReadStore store;
    private final Runnable changed;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private NasBridgeClient pollClient;
    private final Runnable poll=()->checkNew();
    private final List<JSONObject> rows=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ConnectionMonitor monitor;
    private HostRouter.Selection loadedRoute;
    private NasBridgeClient client;
    private TextView status;
    private EditText query;
    private Button more;
    private SmsAdapter adapter;
    private boolean visible,loading,hasMore;
    private volatile int generation;
    private NasBridgeClient bulkClient;
    private String search="";
    private final ConnectionMonitor.Listener listener=state -> {
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null){cancel();loadedRoute=null;rows.clear();adapter.notifyDataSetChanged();more.setVisibility(View.GONE);status.setText(state.checking?"正在自动选择访问地址…":"请先配置 Host 和端口");}
        else if(route!=loadedRoute)load(true);
    };
    SmsController(Activity activity, View root, Runnable changed){
        this.activity=activity;this.root=root;this.changed=changed;store=new SmsReadStore(activity);
        monitor=((DashboardApplication)activity.getApplication()).monitor();
        status=findViewById(R.id.sms_status);query=findViewById(R.id.sms_query);
        ListView list=findViewById(R.id.sms_list);
        more=new Button(activity);more.setText("加载更多短信");more.setTextColor(android.graphics.Color.parseColor("#52745F"));more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        list.addFooterView(more,null,false);more.setVisibility(View.GONE);more.setOnClickListener(v->load(false));
        adapter=new SmsAdapter();list.setAdapter(adapter);
        View back=findViewById(R.id.sms_back);if(back!=null)back.setOnClickListener(v->activity.finish());
        findViewById(R.id.sms_mark_all_read).setOnClickListener(v->markAllRead());
        findViewById(R.id.sms_refresh).setOnClickListener(v->load(true));
        findViewById(R.id.sms_search).setOnClickListener(v->search());
        query.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        list.setOnItemClickListener((parent,view,position,id)->{
            if(position>=rows.size())return;JSONObject row=rows.get(position);markRead(row);
            TextView content=new TextView(activity);content.setText(row.optString("body", "")+(row.optBoolean("truncated")?"\n\n仅显示前 16000 字。":""));content.setTextSize(16);content.setPadding(24,16,24,24);content.setTextIsSelectable(true);
            ScrollView scroll=new ScrollView(activity);scroll.addView(content);
            AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(sender(row)+"\n"+stamp(row.optString("sent_at"))).setView(scroll).setPositiveButton("关闭",null);
            String code=row.optString("verification_code", "");
            if(!code.isEmpty())dialog.setNeutralButton(R.string.sms_copy_code,(d,w)->copyCode(code));
            dialog.show();
        });
    }
    private void markAllRead(){
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null){status.setText("请先配置 Host 和端口");return;}
        Button button=findViewById(R.id.sms_mark_all_read);
        if(!button.isEnabled())return;
        button.setEnabled(false);status.setText("正在将全部短信标为已读…");
        int request=generation;
        Set<String> keys=store.unreadKeys();
        NasBridgeClient api=new NasBridgeClient();bulkClient=api;
        worker.execute(()->{
            try{
                // Fetch every page without the current search filter. Commit once,
                // only after a complete successful scan; never partially clear on failure.
                keys.addAll(SmsReadBatch.collect(offset->{
                    if(request!=generation)throw new CancellationException();
                    return api.getJson(route::openConnection,"api/sms?limit=100&offset="+offset);
                }));
                activity.runOnUiThread(()->{
                    if(!visible||request!=generation)return;
                    store.markAllRead(keys);adapter.notifyDataSetChanged();changed.run();
                    status.setText("全部短信已标为已读");button.setEnabled(true);bulkClient=null;
                });
            }catch(Exception error){activity.runOnUiThread(()->{
                if(!visible||request!=generation)return;
                status.setText("全部已读失败，请重试");button.setEnabled(true);bulkClient=null;
            });}
        });
    }
    private void copyCode(String code){
        android.content.ClipboardManager clipboard=activity.getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData data=android.content.ClipData.newPlainText("验证码",code);
        if(android.os.Build.VERSION.SDK_INT>=24){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);data.getDescription().setExtras(extras);}
        clipboard.setPrimaryClip(data);
        if(android.os.Build.VERSION.SDK_INT<33)Toast.makeText(activity,R.string.sms_code_copied,Toast.LENGTH_SHORT).show();
    }
    private void search(){search=query.getText().toString().trim();load(true);}
    private void load(boolean reset){
        if(loading&&!reset)return;
        HostRouter.Selection route=monitor.routeForRequests();if(route==null)return;
        if(reset){cancel();rows.clear();adapter.notifyDataSetChanged();hasMore=false;more.setVisibility(View.GONE);}
        loadedRoute=route;loading=true;more.setEnabled(false);status.setText(R.string.sms_loading);
        int request=generation,offset=rows.size();String q=android.net.Uri.encode(search);
        NasBridgeClient api=new NasBridgeClient();client=api;
        worker.execute(()->{
            try{
                JSONObject data=api.getJson(route::openConnection,"api/sms?offset="+offset+"&limit=40&q="+q);
                JSONArray items=data.getJSONArray("items");List<JSONObject> page=new ArrayList<>();
                for(int i=0;i<items.length();i++)page.add(items.getJSONObject(i));
                activity.runOnUiThread(()->{if(!visible||request!=generation)return;rows.addAll(page);store.observe(page);changed.run();loading=false;hasMore=data.optBoolean("has_more");more.setVisibility(hasMore?View.VISIBLE:View.GONE);more.setEnabled(hasMore);status.setText(rows.isEmpty()?"暂无匹配短信":"已加载 "+rows.size()+" 条 · 点击摘要展开全文");adapter.notifyDataSetChanged();});
            }catch(Exception error){activity.runOnUiThread(()->{if(!visible||request!=generation)return;loading=false;status.setText("短信加载失败，点击刷新重试\n"+error.getMessage());more.setEnabled(hasMore);});}
        });
    }
    private static String sender(JSONObject r){String s=r.optString("sender","");return s.isEmpty()?"未知发送人":s;}
    private static String stamp(String s){
        if(s==null||s.isEmpty()||s.equals("null"))return "—";
        if(android.os.Build.VERSION.SDK_INT<26)return s.replace('T',' ');
        try{return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(java.util.Date.from(java.time.OffsetDateTime.parse(s).toInstant()));}
        catch(Exception e){return s.replace('T',' ');}
    }
    private final class SmsAdapter extends BaseAdapter{
        public int getCount(){return rows.size();}public Object getItem(int i){return rows.get(i);}public long getItemId(int i){return i;}
        public View getView(int i,View recycled,ViewGroup parent){
            View v=recycled==null?activity.getLayoutInflater().inflate(R.layout.item_sms,parent,false):recycled;JSONObject r=rows.get(i);
            ((TextView)v.findViewById(R.id.articles_row_title)).setText(sender(r));
            ((TextView)v.findViewById(R.id.articles_row_preview)).setText(r.optString("body","").replaceAll("\\s+"," "));
            ((TextView)v.findViewById(R.id.articles_row_meta)).setText(stamp(r.optString("sent_at"))+" · "+r.optString("source","短信"));
            boolean read=store.isRead(r);
            v.setBackground(new CrayonDrawable(read?0xfffffcf4:0xffd7e5fa,false,false));
            TextView marker=v.findViewById(R.id.sms_read_state);
            marker.setText(read?"已读":"● 未读");marker.setTextColor(android.graphics.Color.parseColor(read?"#706B75":"#375C98"));
            ((TextView)v.findViewById(R.id.articles_row_title)).setTypeface(null,read?0:1);
            String code=r.optString("verification_code", "");
            Button copy=v.findViewById(R.id.sms_copy_code);copy.setVisibility(code.isEmpty()?View.GONE:View.VISIBLE);
            copy.setOnClickListener(code.isEmpty()?null:button->{copyCode(code);markRead(r);});
            v.setTag("sms:"+r.optString("device_id")+":"+r.optString("message_id"));return v;
        }
    }
    private void cancel(){generation++;if(bulkClient!=null){bulkClient.cancel();bulkClient=null;}View all=findViewById(R.id.sms_mark_all_read);if(all!=null)all.setEnabled(true);loading=false;if(client!=null){client.cancel();client=null;}}
    private <T extends View> T findViewById(int id){return root.findViewById(id);}
    void save(Bundle state){state.putString("sms_search",query.getText().toString());}
    void restore(Bundle state){if(state!=null){search=state.getString("sms_search","");query.setText(search);}}
    void refresh(){if(visible)load(true);}
    boolean hasUnread(){return store.hasUnread();}
    private void markRead(JSONObject row){store.markRead(row);adapter.notifyDataSetChanged();changed.run();}
    void start(){if(visible)return;visible=true;loadedRoute=null;monitor.addListener(listener);handler.post(poll);changed.run();}
    void stop(){visible=false;monitor.removeListener(listener);handler.removeCallbacks(poll);cancel();if(pollClient!=null)pollClient.cancel();}
    void close(){stop();worker.shutdownNow();}
    private void checkNew(){
        if(!visible)return;
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null){handler.postDelayed(poll,30000);return;}
        int request=generation;
        NasBridgeClient api=new NasBridgeClient();pollClient=api;
        worker.execute(()->{
            try{
                JSONObject data=api.getJson(route::openConnection,"api/sms?limit=100");
                List<JSONObject> latest=new ArrayList<>();JSONArray items=data.getJSONArray("items");
                for(int i=0;i<items.length();i++)latest.add(items.getJSONObject(i));
                activity.runOnUiThread(()->{if(visible&&request==generation){store.observe(latest);changed.run();
                    if(search.isEmpty()&&!latest.isEmpty()&&!rows.isEmpty()&&!SmsReadStore.key(latest.get(0)).equals(SmsReadStore.key(rows.get(0))))
                        status.setText("有新信息 · 点击刷新查看");}});
            }catch(Exception ignored){/* Keep known unread state when offline. */}
            finally{activity.runOnUiThread(()->{if(visible){handler.removeCallbacks(poll);handler.postDelayed(poll,30000);}});}
        });
    }
}
