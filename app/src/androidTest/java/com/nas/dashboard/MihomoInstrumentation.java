package com.nas.dashboard;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.lang.reflect.*;

/** Synthetic UI smoke test; does not alter settings or dispatch infrastructure mutations. */
public final class MihomoInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static TextView find(View v,String text){
        if(v instanceof TextView && ((TextView)v).getText().toString().contains(text))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView result=find(((ViewGroup)v).getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
    private static void field(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void invoke(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}
    @Override public void onStart(){
        Bundle result=new Bundle();Activity screen=null;
        try{
            check((getTargetContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,"Requires debug package");
            screen=startActivitySync(new Intent(getTargetContext(),MihomoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity activity=screen;
            JSONObject fixture=new JSONObject("{\"status\":\"online\",\"connection_count\":2,\"proxied_count\":1,\"direct_count\":1,\"container\":{\"state\":\"running\",\"running\":true},\"connections\":[{\"host\":\"example.test\",\"destination_port\":443,\"source_key\":\"alias:fixture\",\"source_alias\":\"测试手机\",\"source_ip\":\"192.0.2.1\",\"route\":\"proxy\",\"download\":1024},{\"host\":\"local.test\",\"source_key\":\"ip:192.0.2.2\",\"source_ip\":\"192.0.2.2\",\"route\":\"direct\"}]}");
            runOnMainSync(()->{try{
                field(activity,"snapshot",fixture);field(activity,"fresh",true);invoke(activity,"render");
                View host=activity.findViewById(R.id.overview_content);
                check(find(host,"重启").isEnabled(),"Restart enabled for available container");
                check(!find(host,"启动").isEnabled(),"Cannot start running container");
                check(find(host,"example.test")!=null && find(host,"local.test")!=null,"Both connections shown");
                field(activity,"source","alias:fixture");invoke(activity,"render");
                check(find(host,"local.test")==null && find(host,"example.test")!=null,"Source filter works");
                check(find(host,"1 个连接 · ↑")!=null,"Filtered counters rendered");
                field(activity,"fresh",false);invoke(activity,"render");check(!find(host,"停止").isEnabled(),"Stale data disables mutations");
                check(find(host,"当前连接")!=null,"Default status includes connections");
                check(find(host,"恢复自动选择")==null,"Nodes hidden by default");
                org.json.JSONArray groups=new org.json.JSONArray("[{\"name\":\"PROXY\",\"type\":\"Selector\",\"selectable\":true,\"now\":\"AU\",\"members\":[{\"name\":\"AU\",\"type\":\"Vless\",\"delay\":123}]},{\"name\":\"AUTO-MATCH\",\"type\":\"URLTest\",\"automatic\":true,\"selectable\":true,\"fixed\":\"AU\",\"now\":\"AU\",\"members\":[{\"name\":\"AU\",\"type\":\"Vless\"}]}]");
                field(activity,"groups",groups);field(activity,"tab","nodes");invoke(activity,"render");
                check(find(host,"当前：AU")!=null,"Proxy group current node shown");
                check(find(host,"123 ms")!=null,"Node latency shown");
                find(host,"AUTO-MATCH").performClick();
                check(find(host,"恢复自动选择")!=null,"Automatic group shows restore action");
                check(!find(host,"恢复自动选择").isEnabled(),"Stale groups disable writes");
                check(find(host,"运行状态")!=null,"Group switch preserves main navigation");
                field(activity,"tab","status");invoke(activity,"render");
                check(find(host,"当前连接")!=null,"Return to default status works");

            }catch(Exception e){throw new AssertionError(e);}});
            result.putString("stream","PASS: Mihomo rendering, source filtering, traffic totals, container controls, stale-state protection\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}
        finally{if(screen!=null){Activity a=screen;runOnMainSync(a::finish);}}
    }
}
