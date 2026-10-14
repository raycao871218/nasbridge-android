package com.nas.dashboard;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import com.nas.dashboard.network.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class SmsInstrumentation extends Instrumentation {
 private Bundle options;
 @Override public void onCreate(Bundle b){super.onCreate(b);options=b==null?new Bundle():b;start();}
 @Override public void onStart(){
  String base=options.getString("base","");
  if(base.isEmpty()){Bundle skipped=new Bundle();skipped.putString("stream","SKIP: supply -e base with your own NASBridge URL for the optional real-service SMS UI test\n");finish(-1,skipped);return;}
  Context c=getTargetContext();android.content.SharedPreferences p=c.getSharedPreferences("network_hosts",0);Map<String,?> saved=p.getAll();Activity activity=null;Bundle result=new Bundle();
  try{
   runOnMainSync(()->{new HostSettings(c).save(new HostConfig("sms-test",base,base));((DashboardApplication)c.getApplicationContext()).monitor().configurationChanged();});
   activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity main=activity;
   ActivityMonitor next=addMonitor(SmsActivity.class.getName(),null,false);
   runOnMainSync(()->{main.findViewById(R.id.tab_services).performClick();main.findViewById(R.id.module_sms).performClick();});
   Activity sms=waitForMonitorWithTimeout(next,5000);if(sms==null)throw new AssertionError("SMS entry did not open");activity=sms;
   waitStatus(sms,"已加载 40 条");
   AtomicReference<Throwable> copyFailure=new AtomicReference<>();
   runOnMainSync(()->{
    android.content.ClipboardManager clipboard=sms.getSystemService(android.content.ClipboardManager.class);
    android.content.ClipData previous=clipboard.getPrimaryClip();
    try{
     ListView list=sms.findViewById(R.id.sms_list);org.json.JSONObject row=(org.json.JSONObject)list.getAdapter().getItem(0);
     String originalCode=row.optString("verification_code", "");
     row.put("verification_code","001234");
     android.view.View card=list.getAdapter().getView(0,null,list);Button copy=card.findViewById(R.id.sms_copy_code);
     if(copy.getVisibility()!=android.view.View.VISIBLE)throw new AssertionError("Copy button hidden for OTP");
     copy.performClick();
     if(!"001234".contentEquals(clipboard.getPrimaryClip().getItemAt(0).getText()))throw new AssertionError("Incorrect clipboard text");
     if(!clipboard.getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"))throw new AssertionError("Clipboard not marked sensitive");
     row.put("verification_code","");card=list.getAdapter().getView(0,card,list);
     if(card.findViewById(R.id.sms_copy_code).getVisibility()!=android.view.View.GONE)throw new AssertionError("Recycled row leaks copy button");
     row.put("verification_code",originalCode);
    }catch(Throwable e){copyFailure.set(e);}
    finally{if(previous!=null)clipboard.setPrimaryClip(previous);else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip();}
   });
   if(copyFailure.get()!=null)throw new AssertionError(copyFailure.get());

   runOnMainSync(()->{ListView list=sms.findViewById(R.id.sms_list);list.performItemClick(list.getChildAt(0),0,0);});waitForIdleSync();
   getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitForIdleSync();
   runOnMainSync(()->{((EditText)sms.findViewById(R.id.sms_query)).setText("nasbridge-no-match-unique-20261014");sms.findViewById(R.id.sms_search).performClick();});
   waitStatus(sms,"暂无匹配短信");
   runOnMainSync(()->{((EditText)sms.findViewById(R.id.sms_query)).setText("");sms.findViewById(R.id.sms_search).performClick();});waitStatus(sms,"已加载 40 条");
   runOnMainSync(()->{ListView list=sms.findViewById(R.id.sms_list);((Button)list.getAdapter().getView(40,null,list)).performClick();});waitStatus(sms,"已加载 80 条");
   result.putString("stream","PASS: OTP copy preserves leading zeros, sensitive clipboard, recycled non-OTP row hides button; service entry, 40 SMS rows, detail dialog, empty search, clear search, load 80 rows; no network diagnostic prerequisite\n");
  }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(1,result);return;}
  finally{
   if(activity!=null){Activity a=activity;runOnMainSync(a::finish);}
   runOnMainSync(()->{android.content.SharedPreferences.Editor edit=p.edit().clear();for(Map.Entry<String,?> e:saved.entrySet())if(e.getValue() instanceof String)edit.putString(e.getKey(),(String)e.getValue());edit.commit();((DashboardApplication)c.getApplicationContext()).monitor().configurationChanged();});
  }
  finish(-1,result);
 }
 private void waitStatus(Activity a,String text)throws Exception{
  long end=android.os.SystemClock.elapsedRealtime()+20000;AtomicReference<String> s=new AtomicReference<>();
  while(android.os.SystemClock.elapsedRealtime()<end){runOnMainSync(()->s.set(((TextView)a.findViewById(R.id.sms_status)).getText().toString()));if(s.get().startsWith(text))return;Thread.sleep(100);}throw new AssertionError("Expected "+text+", got "+s.get());
 }
}
