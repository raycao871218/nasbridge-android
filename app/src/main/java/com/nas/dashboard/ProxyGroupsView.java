package com.nas.dashboard;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.*;
import org.json.*;

/** Compact native proxy group tabs; backend remains authoritative for writes. */
final class ProxyGroupsView {
    interface Actions { void run(String path,String method,JSONObject body); }
    private final Activity activity;
    private LinearLayout host;
    private final Actions actions;
    private String selected="";
    ProxyGroupsView(Activity activity,LinearLayout host,Actions actions){this.activity=activity;this.host=host;this.actions=actions;}
    void attach(LinearLayout parent){host=parent;}
    void save(Bundle state){state.putString("proxy_group",selected);}
    void restore(Bundle state){if(state!=null)selected=state.getString("proxy_group","");}
    void render(JSONArray groups,boolean enabled,String error){
        if(!error.isEmpty())text(host,error,14);
        if(groups==null){text(host,error.isEmpty()?"正在读取代理组…":"点击刷新重试",14);return;}
        if(groups.length()==0){text(host,"暂无代理组",14);return;}
        JSONObject current=null;
        for(int i=0;i<groups.length();i++){JSONObject group=groups.optJSONObject(i);if(group!=null&&selected.equals(group.optString("name")))current=group;}
        if(current==null){current=groups.optJSONObject(0);for(int i=0;i<groups.length();i++){JSONObject group=groups.optJSONObject(i);if(group!=null&&"PROXY".equals(group.optString("name")))current=group;}selected=current.optString("name");}
        HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=new LinearLayout(activity);tabs.setOrientation(LinearLayout.HORIZONTAL);tabs.setBaselineAligned(false);scroll.addView(tabs);host.addView(scroll);
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);if(group==null)continue;
            Button tab=button(tabs,group.optString("name"),true,()->{selected=group.optString("name");host.removeAllViews();render(groups,enabled,error);},false);
            MihomoButtonStyle.apply(tab,selected.equals(group.optString("name")));
        }
        JSONObject group=current;
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(10),dp(14),dp(12));box.setBackgroundResource(R.drawable.card);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);host.addView(box,p);
        String fixed=group.optString("fixed"),now=group.optString("now");
        text(box,group.optString("type")+" · "+(group.optBoolean("automatic")?(fixed.isEmpty()?"自动选择":"固定："+fixed):group.optBoolean("selectable")?"手动选择":"只读"),13);
        text(box,now.isEmpty()?"按组策略分配连接":"当前："+now,16);
        if(group.optBoolean("automatic"))button(box,"恢复自动选择",enabled&&!fixed.isEmpty(),()->act("/automatic","POST",group.optString("name"),null),true);
        JSONArray members=group.optJSONArray("members");
        for(int i=0;members!=null&&i<members.length();i++){
            JSONObject member=members.optJSONObject(i);if(member==null)continue;
            String name=member.optString("name");boolean active=now.equals(name);
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setBaselineAligned(false);row.setGravity(android.view.Gravity.TOP);row.setPadding(0,0,0,dp(4));box.addView(row);
            String caption=(active?"✓ ":"")+name+"\n"+member.optString("type")+(member.optString("now").isEmpty()?"":" → "+member.optString("now"))+(member.optBoolean("udp")?" · UDP":"");
            Button choice=button(row,caption,enabled&&group.optBoolean("selectable")&&(!active||(group.optBoolean("automatic")&&!name.equals(fixed))),()->act("/selection","PUT",group.optString("name"),name),true);
            MihomoButtonStyle.apply(choice,active);choice.setMinHeight(dp(64));
            Object delay=member.opt("delay");String latency=delay instanceof Number?(((Number)delay).intValue()>0?((Number)delay).intValue()+" ms":"超时"):"测速";
            Button test=button(row,latency,enabled,()->act("/delay","POST",null,name),false);test.setMinHeight(dp(64));test.setContentDescription("测试 "+name+" 的延迟");
        }
        text(host,"点选切换新连接；自动组点选后固定节点。",12);
    }
    private void act(String path,String method,String group,String name){
        try{JSONObject body=new JSONObject();if(group!=null)body.put("group",group);if(name!=null)body.put("name",name);actions.run(path,method,body);}
        catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    private TextView text(LinearLayout parent,String value,int size){TextView v=new TextView(activity);v.setText(value);v.setTextColor(0xff514d58);v.setTextSize(size);v.setPadding(0,dp(7),0,dp(7));parent.addView(v);return v;}
    private Button button(LinearLayout parent,String title,boolean enabled,Runnable action,boolean fill){
        Button b=new Button(activity);b.setText(title);b.setTextSize(13);b.setTextColor(0xff3b3a49);b.setAllCaps(false);b.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);b.setMinHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));MihomoButtonStyle.apply(b,false);b.setEnabled(enabled);
        LinearLayout.LayoutParams p=parent.getOrientation()==LinearLayout.HORIZONTAL&&fill?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(fill?-1:-2,-2);p.topMargin=dp(8);p.rightMargin=dp(6);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
}
