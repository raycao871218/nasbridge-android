package com.nas.dashboard;

import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.nas.dashboard.network.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Native Mihomo management; polls only while visible and never retries mutations. */
public final class MihomoActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=()->refresh();
    private ConnectionMonitor monitor;
    private final ConnectionMonitor.Listener listener=state->refresh();
    private LinearLayout host;
    private TextView status,metrics;
    private JSONObject snapshot;
    private String tab="status",groupsError="";
    private JSONArray groups;
    private boolean groupsFresh;
    private HostRouter.Selection groupRoute;
    private ProxyGroupsView proxyGroups;
    private String source="",feedback="";
    private boolean visible,loading,mutating,fresh;
    private HostRouter.Selection loadedRoute;
    private EditText configEditor;
    private String configBase;
    private String editorBase;
    private String restoredDraft,restoredBase,restoredAddress;
    private AlertDialog configDialog,aliasDialog;
    private interface Result { void accept(JSONObject data) throws Exception; }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);setContentView(R.layout.activity_service_module);
        monitor=((DashboardApplication)getApplication()).monitor();
        host=findViewById(R.id.overview_content);status=findViewById(R.id.overview_status);metrics=findViewById(R.id.overview_metrics);
        proxyGroups=new ProxyGroupsView(this,host,(path,method,body)->{
            if(!groupsFresh||groupRoute!=monitor.routeForRequests()){toast("请先刷新代理组");refresh();return;}
            request(method,"api/mihomo/proxies"+path,body,data->{
                groupsFresh=false;
                if(!"/delay".equals(path)&&!"ok".equals(data.optString("status")))throw new IOException("服务端未确认节点切换结果，请刷新确认");
                feedback="/delay".equals(path)?body.optString("name")+"："+(data.optInt("delay")>0?data.optInt("delay")+" ms":"测速失败或超时"):data.optString("message","操作已完成");
                toast(feedback);
            });
        });
        proxyGroups.restore(saved);
        ((TextView)findViewById(R.id.module_title)).setText("Mihomo 代理");
        findViewById(R.id.overview_route).setVisibility(View.GONE);
        findViewById(R.id.module_notice).setVisibility(View.GONE);
        findViewById(R.id.module_go_config).setVisibility(View.GONE);
        findViewById(R.id.overview_back).setOnClickListener(v->finish());
        findViewById(R.id.overview_refresh).setOnClickListener(v->refresh());
        if(saved!=null)source=saved.getString("source","");
        Object retained=getLastNonConfigurationInstance();
        if(retained instanceof String[]){String[] draft=(String[])retained;restoredDraft=draft[0];restoredBase=draft[1];restoredAddress=draft[2];}
    }
    @Override protected void onStart(){super.onStart();visible=true;monitor.addListener(listener);refresh();}
    @Override protected void onStop(){visible=false;fresh=false;groupsFresh=false;handler.removeCallbacks(poll);monitor.removeListener(listener);super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacks(poll);worker.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("source",source);proxyGroups.save(state);super.onSaveInstanceState(state);}
    @Override public Object onRetainNonConfigurationInstance(){
        return configDialog!=null&&configDialog.isShowing()&&configEditor!=null?
            new String[]{configEditor.getText().toString(),configBase,editorBase}:null;
    }
    private void refresh() {
        handler.removeCallbacks(poll);
        if(!visible || loading || mutating)return;
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null){fresh=false;groupsFresh=false;status.setText("正在选择访问地址…");if(snapshot!=null)render();return;}
        loading=true;
        boolean readGroups="nodes".equals(tab);
        worker.submit(()->{
            try {
                JSONObject data=new NasBridgeClient().getJson(route::openConnection,"api/overview").getJSONObject("mihomo");
                JSONArray loadedGroups=null;String groupError="";
                if(readGroups)try{loadedGroups=new NasBridgeClient().mihomoRequest(route::openConnection,"GET","api/mihomo/proxies",null).getJSONArray("items");}
                catch(Exception error){groupError="代理组读取失败，请确认 NAS 已更新后重试："+error.getMessage();}
                JSONArray nextGroups=loadedGroups;String nextError=groupError;
                runOnUiThread(()->{loading=false;if(isDestroyed())return;
                    if(!visible)return;
                    if(route!=monitor.routeForRequests()){fresh=false;refresh();return;}
                    snapshot=data;loadedRoute=route;fresh=true;
                    if(readGroups){groups=nextGroups;groupsFresh=nextGroups!=null;groupsError=nextError;groupRoute=route;}
                    render();schedule();
                    if("nodes".equals(tab)&&!readGroups)refresh();
                    if(restoredDraft!=null){String draft=restoredDraft;restoredDraft=null;editConfig(restoredBase);editorBase=restoredAddress;configEditor.setText(draft);}});
            } catch(Exception e) {runOnUiThread(()->{loading=false;if(isDestroyed() || !visible)return;
                fresh=false;groupsFresh=false;status.setText("刷新失败 · "+e.getMessage());if(snapshot!=null)render();schedule();});}
        });
    }
    private void schedule(){handler.removeCallbacks(poll);if(visible)handler.postDelayed(poll,10000);}
    private void request(String method,String path,JSONObject body,Result done) {
        if(mutating)return;
        HostRouter.Selection route=monitor.routeForRequests();
        if(route==null || !fresh || route!=loadedRoute){toast("请先刷新并确认连接状态");refresh();return;}
        mutating=true;handler.removeCallbacks(poll);if(snapshot!=null)render();
        worker.submit(()->{
            try {
                JSONObject data=new NasBridgeClient().mihomoRequest(route::openConnection,method,path,body);
                runOnUiThread(()->{mutating=false;if(isDestroyed())return;
                    if(!visible){fresh=false;return;}
                    if(route!=monitor.routeForRequests()){fresh=false;toast("网络地址已变化，请刷新确认操作结果");refresh();return;}
                    try{done.accept(data);}catch(Exception e){toast(e.getMessage());}
                    refresh();});
            }catch(Exception e){runOnUiThread(()->{mutating=false;if(isDestroyed())return;
                feedback="操作未确认："+e.getMessage()+"；请刷新确认状态";fresh=false;groupsFresh=false;toast(feedback);if(snapshot!=null)render();refresh();});}
        });
    }
    private void render() {
        if(snapshot==null)return;
        int y=((ScrollView)host.getParent().getParent()).getScrollY();
        host.removeAllViews();
        if(fresh)status.setText(mutating?"正在处理…":"已更新 · 每 10 秒刷新");
        metrics.setText(("online".equals(snapshot.optString("status"))?"在线":"离线")+" · "+snapshot.optInt("connection_count")+" 个连接"
            +"\n代理 "+snapshot.optInt("proxied_count")+" · 直连 "+snapshot.optInt("direct_count")+" · 内存 "+bytes(snapshot.optLong("memory")));
        if(!feedback.isEmpty())text(host,feedback,13);
        LinearLayout tabs=row(host);
        for(String[] item:new String[][]{{"status","运行状态"},{"nodes","代理节点"},{"config","配置"}}){
            button(tabs,item[1],true,()->{tab=item[0];((ScrollView)host.getParent().getParent()).scrollTo(0,0);render();if("nodes".equals(tab))refresh();});
            MihomoButtonStyle.apply((Button)tabs.getChildAt(tabs.getChildCount()-1),tab.equals(item[0]));
        }
        if("nodes".equals(tab)){
            LinearLayout nodes=new LinearLayout(this);nodes.setOrientation(LinearLayout.VERTICAL);host.addView(nodes);proxyGroups.attach(nodes);
            proxyGroups.render(groups,fresh&&groupsFresh&&!mutating&&groupRoute==monitor.routeForRequests(),groupsError);
            restoreScroll(y);return;
        }
        if("config".equals(tab)){
            LinearLayout config=card("代理配置");text(config,"编辑节点与分流规则，或导入 YAML 配置。",14);
            button(config,"读取并编辑配置",fresh&&!mutating,()->request("GET","api/mihomo/config",null,d->editConfig(d.optString("content"))));
            restoreScroll(y);return;
        }
        JSONObject container=snapshot.optJSONObject("container");if(container==null)container=new JSONObject();
        String state=container.optString("state","unavailable");boolean running=container.optBoolean("running");
        LinearLayout controls=card("容器 · "+containerState(state));LinearLayout row=row(controls);
        boolean usable=fresh && !mutating && !"unavailable".equals(state);
        button(row,"启动",usable&&!running,()->control("start","启动"));
        button(row,"重启",usable,()->control("restart","重启"));button(row,"停止",usable&&running,()->control("stop","停止"));
        LinearLayout tools=row(host);
        button(tools,"Google 检测",fresh&&!mutating,()->request("GET","api/mihomo/google-check",null,d->{feedback=d.optString("message",d.optBoolean("success")?"Google 连接成功":"Google 连接失败")+(d.has("delay_ms")?" · "+d.optLong("delay_ms")+" ms":"")+"（NAS → Mihomo → Google）";toast(feedback);}));
        button(tools,"来源别名",fresh&&!mutating,this::aliases);
        LinearLayout connections=card("当前连接");
        JSONArray items=snapshot.optJSONArray("connections");if(items==null)items=new JSONArray();
        LinkedHashMap<String,String> choices=new LinkedHashMap<>();choices.put("","全部来源");
        for(int i=0;i<items.length();i++){JSONObject c=items.optJSONObject(i);if(c!=null)choices.put(c.optString("source_key","ip:"+c.optString("source_ip")),label(c));}
        if(!choices.containsKey(source))source="";
        List<String> keys=new ArrayList<>(choices.keySet());Spinner filter=new Spinner(this);
        filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>(choices.values())));
        filter.setSelection(keys.indexOf(source));connections.addView(filter);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){} public void onItemSelected(AdapterView<?> parent,View v,int position,long id){String next=keys.get(position);if(!source.equals(next)){source=next;render();}}
        });
        long up=0,down=0;int count=0;
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        for(int i=0;i<items.length();i++) {
            JSONObject c=items.optJSONObject(i);if(c==null || (!source.isEmpty()&&!source.equals(c.optString("source_key","ip:"+c.optString("source_ip")))))continue;
            count++;up+=c.optLong("upload");down+=c.optLong("download");
            TextView entry=text(list,c.optString("host","未知目标")+":"+c.optString("destination_port")+"\n"+label(c)+" · "+("direct".equals(c.optString("route"))?"直连":"代理")+"   ↑"+bytes(c.optLong("upload"))+" ↓"+bytes(c.optLong("download")),14);
            entry.setPadding(0,dp(12),0,dp(12));entry.setOnClickListener(v->connectionDetails(c));
        }
        text(connections,count+" 个连接 · ↑"+bytes(up)+" ↓"+bytes(down),13);
        text(connections,"活动连接累计流量 · 点按查看详情",12);connections.addView(list);
        if(count==0)text(connections,"暂无连接",14);
        if(!"online".equals(snapshot.optString("status")))text(connections,snapshot.optString("error","代理暂不可用"),13);
        ((ScrollView)host.getParent().getParent()).post(()->((ScrollView)host.getParent().getParent()).scrollTo(0,y));
    }
    private void restoreScroll(int y){((ScrollView)host.getParent().getParent()).post(()->((ScrollView)host.getParent().getParent()).scrollTo(0,y));}
    private void control(String action,String title) {
        Runnable submit=()->request("POST","api/mihomo/container/"+action,new JSONObject(),d->{feedback=title+"操作已完成";});
        if("start".equals(action))submit.run();else new AlertDialog.Builder(this).setTitle(title+" Mihomo？")
            .setMessage("将影响当前使用代理的连接。").setNegativeButton("取消",null).setPositiveButton(title,(d,w)->submit.run()).show();
    }
    private void connectionDetails(JSONObject c) {
        showText("连接详情","来源："+label(c)+"\n地址："+c.optString("source_ip")+":"+c.optString("source_port")
            +"\n目标："+c.optString("host")+":"+c.optString("destination_port")+"\n网络："+c.optString("network")
            +"\n进程："+c.optString("process")+"\n规则："+c.optString("rule")+"\n链路："+c.optJSONArray("chains")
            +"\n开始："+DailyTaskFormat.time(c.optString("start")));
    }
    private void aliases() {
        request("GET","api/mihomo/source-aliases",null,d->{
            LinearLayout panel=panel();button(panel,"添加来源别名",true,()->aliasEditor(null));
            JSONArray rules=d.optJSONArray("items");
            if(rules==null || rules.length()==0)text(panel,"暂无别名",14);
            for(int i=0;rules!=null&&i<rules.length();i++){JSONObject rule=rules.optJSONObject(i);if(rule==null)continue;
                LinearLayout row=row(panel);button(row,rule.optString("name")+" · "+rule.optString("ip")+(rule.isNull("port")?"":":"+rule.optInt("port")),true,()->aliasEditor(rule));
                button(row,"删除",true,()->new AlertDialog.Builder(this).setTitle("删除来源别名？").setMessage(rule.optString("name"))
                    .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,w)->request("DELETE","api/mihomo/source-aliases/"+rule.optString("id"),null,result->{feedback="来源别名已删除";if(aliasDialog!=null)aliasDialog.dismiss();})).show());
            }
            aliasDialog=new AlertDialog.Builder(this).setTitle("来源别名").setView(scrolled(panel)).setPositiveButton("关闭",null).show();
        });
    }
    private void aliasEditor(JSONObject rule) {
        LinearLayout panel=panel();EditText ip=input(panel,"来源 IP"),port=input(panel,"端口（可留空）"),name=input(panel,"别名");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if(rule!=null){ip.setText(rule.optString("ip"));if(!rule.isNull("port"))port.setText(rule.optString("port"));name.setText(rule.optString("name"));}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("来源别名").setView(scrolled(panel)).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{
            try{String address=ip.getText().toString().trim(),alias=name.getText().toString().trim(),raw=port.getText().toString().trim();
                if(address.isEmpty()||alias.isEmpty()||alias.length()>80)throw new IllegalArgumentException("请填写 IP 和 1–80 字的别名");
                int number=raw.isEmpty()?0:Integer.parseInt(raw);if(!raw.isEmpty()&&(number<1||number>65535))throw new IllegalArgumentException("端口须为 1–65535");
                JSONObject body=new JSONObject().put("ip",address).put("name",alias).put("port",raw.isEmpty()?JSONObject.NULL:number);
                request("PUT","api/mihomo/source-aliases",body,result->{feedback="来源别名已保存";dialog.dismiss();if(aliasDialog!=null)aliasDialog.dismiss();});
            }catch(Exception e){toast(e.getMessage());}
        }));dialog.show();
    }
    private void editConfig(String content) {
        editorBase=loadedRoute.baseUrl;configBase=content;
        LinearLayout panel=panel();text(panel,"修改节点、分流规则后校验并应用。",13);
        configEditor=input(panel,"YAML 配置");configEditor.setText(content);configEditor.setTypeface(Typeface.MONOSPACE);configEditor.setTextSize(12);
        configEditor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        configEditor.setGravity(Gravity.TOP);configEditor.setMinLines(12);
        configDialog=new AlertDialog.Builder(this).setTitle("Mihomo 配置").setView(scrolled(panel))
            .setNegativeButton("关闭",(d,w)->configEditor=null).setNeutralButton("导入 YAML",null).setPositiveButton("校验并应用",null).create();
        configDialog.setOnShowListener(v->{
            configDialog.getButton(-3).setOnClickListener(b->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),70));
            configDialog.getButton(-1).setOnClickListener(b->{
                String next=configEditor.getText().toString();if(next.trim().isEmpty()){toast("配置不能为空");return;}
                if(monitor.routeForRequests()==null || !editorBase.equals(monitor.routeForRequests().baseUrl)){toast("访问地址已变化，请关闭后重新读取配置");return;}
                new AlertDialog.Builder(this).setTitle("应用配置？").setMessage("将校验 YAML 并重载代理，可能影响当前连接。")
                    .setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->{
                        // Re-read before writing so another editor's changes are not silently overwritten.
                        request("GET","api/mihomo/config",null,current->{
                            if(!configBase.equals(current.optString("content"))){toast("服务端配置已变化，请重新读取后编辑");return;}
                            handler.post(()->{
                                try{request("PUT","api/mihomo/config",new JSONObject().put("content",next),result->{
                                    if(!"applied".equals(result.optString("status")))throw new IOException("服务端未确认配置应用结果");
                                    feedback=result.optString("message","配置已应用");configBase=next;toast(feedback);
                                });}catch(JSONException e){toast("配置提交失败");}
                            });
                        });
                    }).show();
            });
        });configDialog.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=70 || result!=RESULT_OK || data==null || data.getData()==null || configEditor==null)return;
        android.net.Uri uri=data.getData();
        worker.submit(()->{try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            if(input==null)throw new IOException("无法读取文件");byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("配置不能超过 4 MB");bytes.write(buffer,0,n);}
            String content=new String(bytes.toByteArray(),StandardCharsets.UTF_8);if(content.startsWith("\uFEFF"))content=content.substring(1);String imported=content;
            runOnUiThread(()->{if(!isDestroyed()&&configEditor!=null)configEditor.setText(imported);});
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())toast("导入失败："+e.getMessage());});}});
    }
    private void showText(String title,String message){LinearLayout p=panel();text(p,message,14).setTextIsSelectable(true);new AlertDialog.Builder(this).setTitle(title).setView(scrolled(p)).setPositiveButton("关闭",null).show();}
    private String label(JSONObject c){String name=c.optString("source_alias");return name.isEmpty()?c.optString("source_ip"):name;}
    private String containerState(String state){switch(state){case "running":return "运行中";case "exited":return "已停止";case "created":return "已创建";case "restarting":return "重启中";case "dead":return "异常";default:return "不可用";}}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(16),dp(8),dp(16),dp(12));return p;}
    private ScrollView scrolled(LinearLayout panel){ScrollView s=new ScrollView(this);s.addView(panel);return s;}
    private LinearLayout row(LinearLayout parent){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setBaselineAligned(false);parent.addView(row);return row;}
    private LinearLayout card(String title){LinearLayout box=panel();box.setBackgroundResource(R.drawable.card);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);host.addView(box,p);text(box,title,18);return box;}
    private TextView text(LinearLayout parent,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(0xff514d58);v.setPadding(0,dp(6),0,dp(6));parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private EditText input(LinearLayout parent,String hint){EditText v=new EditText(this);v.setHint(hint);v.setTextColor(0xff514d58);v.setSingleLine(false);parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private void button(LinearLayout parent,String label,boolean enabled,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(dp(48));MihomoButtonStyle.apply(b,false);b.setTextColor(0xff3b3a49);b.setEnabled(enabled);parent.addView(b,parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
    private String bytes(long value){double n=value;String[] units={"B","KB","MB","GB","TB"};int i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return String.format(Locale.CHINA,"%.1f %s",n,units[i]);}
}
