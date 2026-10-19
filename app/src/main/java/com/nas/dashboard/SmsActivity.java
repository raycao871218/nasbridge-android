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
public final class SmsActivity extends Activity {
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
    private int generation;
    private String search="";
    private final ConnectionMonitor.Listener listener=state -> {
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null){cancel();loadedRoute=null;rows.clear();adapter.notifyDataSetChanged();more.setVisibility(View.GONE);status.setText(state.checking?"正在自动选择访问地址…":"请先配置 Host 和端口");}
        else if(route!=loadedRoute)load(true);
    };
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_sms);
        monitor=((DashboardApplication)getApplication()).monitor();
        status=findViewById(R.id.sms_status);query=findViewById(R.id.sms_query);
        if(state!=null){search=state.getString("search","");query.setText(search);}
        ListView list=findViewById(R.id.sms_list);
        more=new Button(this);more.setText("加载更多短信");more.setTextColor(android.graphics.Color.parseColor("#52745F"));more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        list.addFooterView(more,null,false);more.setVisibility(View.GONE);more.setOnClickListener(v->load(false));
        adapter=new SmsAdapter();list.setAdapter(adapter);
        findViewById(R.id.sms_back).setOnClickListener(v->finish());
        findViewById(R.id.sms_refresh).setOnClickListener(v->load(true));
        findViewById(R.id.sms_search).setOnClickListener(v->search());
        query.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        list.setOnItemClickListener((parent,view,position,id)->{
            if(position>=rows.size())return;JSONObject row=rows.get(position);
            TextView content=new TextView(this);content.setText(row.optString("body", "")+(row.optBoolean("truncated")?"\n\n仅显示前 16000 字。":""));content.setTextSize(16);content.setPadding(24,16,24,24);content.setTextIsSelectable(true);
            ScrollView scroll=new ScrollView(this);scroll.addView(content);
            AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(sender(row)+"\n"+stamp(row.optString("sent_at"))).setView(scroll).setPositiveButton("关闭",null);
            String code=row.optString("verification_code", "");
            if(!code.isEmpty())dialog.setNeutralButton(R.string.sms_copy_code,(d,w)->copyCode(code));
            dialog.show();
        });
    }
    private void copyCode(String code){
        android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData data=android.content.ClipData.newPlainText("验证码",code);
        if(android.os.Build.VERSION.SDK_INT>=24){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);data.getDescription().setExtras(extras);}
        clipboard.setPrimaryClip(data);
        if(android.os.Build.VERSION.SDK_INT<33)Toast.makeText(this,R.string.sms_code_copied,Toast.LENGTH_SHORT).show();
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
                runOnUiThread(()->{if(!visible||request!=generation)return;rows.addAll(page);loading=false;hasMore=data.optBoolean("has_more");more.setVisibility(hasMore?View.VISIBLE:View.GONE);more.setEnabled(hasMore);status.setText(rows.isEmpty()?"暂无匹配短信":"已加载 "+rows.size()+" 条 · 点击摘要展开全文");adapter.notifyDataSetChanged();});
            }catch(Exception error){runOnUiThread(()->{if(!visible||request!=generation)return;loading=false;status.setText("短信加载失败，点击刷新重试\n"+error.getMessage());more.setEnabled(hasMore);});}
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
            View v=recycled==null?getLayoutInflater().inflate(R.layout.item_sms,parent,false):recycled;JSONObject r=rows.get(i);
            ((TextView)v.findViewById(R.id.articles_row_title)).setText(sender(r));
            ((TextView)v.findViewById(R.id.articles_row_preview)).setText(r.optString("body","").replaceAll("\\s+"," "));
            ((TextView)v.findViewById(R.id.articles_row_meta)).setText(stamp(r.optString("sent_at"))+" · "+r.optString("source","短信"));
            String code=r.optString("verification_code", "");
            Button copy=v.findViewById(R.id.sms_copy_code);copy.setVisibility(code.isEmpty()?View.GONE:View.VISIBLE);
            copy.setOnClickListener(code.isEmpty()?null:button->copyCode(code));
            v.setTag("sms:"+r.optString("device_id")+":"+r.optString("message_id"));return v;
        }
    }
    private void cancel(){generation++;loading=false;if(client!=null){client.cancel();client=null;}}
    @Override protected void onStart(){super.onStart();visible=true;loadedRoute=null;monitor.addListener(listener);}
    @Override protected void onStop(){visible=false;monitor.removeListener(listener);cancel();super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("search",search);super.onSaveInstanceState(state);}
}
