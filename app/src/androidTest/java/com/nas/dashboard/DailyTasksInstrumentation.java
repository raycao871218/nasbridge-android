package com.nas.dashboard;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONObject;

/** Read-only synthetic task data in the isolated preview package. */
public final class DailyTasksInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static TextView find(View view,String text){
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(text))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            TextView found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    private static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    @Override public void onStart(){
        Bundle result=new Bundle();Activity screen=null;
        try{
            check(getTargetContext().getPackageName().endsWith(".preview"),"Requires isolated preview package");
            // Shared ripple backgrounds must not share mutable child bounds across widths.
            android.graphics.drawable.Drawable first=getTargetContext().getDrawable(R.drawable.button);
            android.graphics.drawable.Drawable second=getTargetContext().getDrawable(R.drawable.button);
            first.setBounds(0,0,240,64);
            second.setBounds(0,0,480,64);
            android.graphics.drawable.Drawable firstChild=((android.graphics.drawable.LayerDrawable)first).getDrawable(0);
            android.graphics.drawable.Drawable secondChild=((android.graphics.drawable.LayerDrawable)second).getDrawable(0);
            check(firstChild!=secondChild,"Buttons own independent pigment drawables");
            check(firstChild.getBounds().width()==240,"Wide button cannot change narrow button bounds");
            check(secondChild.getBounds().width()==480,"Wide button retains its bounds");
            screen=startActivitySync(new Intent(getTargetContext(),ServiceModuleActivity.class).putExtra("module","tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity activity=screen;
            JSONObject data=new JSONObject("{\"runs\":[{\"run_id\":\"latest\",\"run_date\":\"2026-10-19\",\"status\":\"timed_out\",\"error_message\":\"下载超时，请检查网络\",\"progress\":{\"stages\":[{\"id\":\"download\",\"label\":\"下载视频\",\"status\":\"running\",\"percent\":45,\"item\":\"示例视频\"}]}},{\"run_id\":\"history\",\"status\":\"success\",\"run_summary\":\"历史任务结果\"},{\"run_id\":\"hidden\",\"hidden\":true,\"status\":\"failed\"}],\"summaries\":[{\"summary_filename\":\"示例概要.md\",\"video_id\":\"fixture-video\"}]}");
            runOnMainSync(()->{
                LinearLayout host=activity.findViewById(R.id.overview_content);
                DailyTasksView ui=new DailyTasksView(activity,host);ui.render(data);
                try {
                    data.put("status","ok");
                    data.put("manual_control",new JSONObject("{\"can_trigger\":true,\"shutdown_protected\":true,\"retry\":{\"available\":true,\"status\":\"waiting\",\"retry_at\":\"2026-11-02T03:00:00Z\"}}"));
                }catch(Exception e){throw new AssertionError(e);}
                String[] action={""};ui.setActions((a,id)->action[0]=a+":"+id);ui.accept(data);
                check(DailyTasksView.needsRefresh(data),"Waiting retry refreshes automatically");
                check(find(host,"等待 11:00 自动重试")!=null,"Retry state shown");
                check(find(host,"关机保护中")!=null,"Compact shutdown protection shown");
                find(host,"手动执行").performClick();check(action[0].equals("start:"),"Manual action dispatched");
                ui.setBusy(true,"正在提交操作…");check(!find(host,"正在处理").isEnabled(),"Busy prevents repeat action");
                ui.setBusy(false,"已提交");check(!find(host,"手动执行").isEnabled(),"Must refresh after submission");
                ui.accept(data);check(find(host,"手动执行").isEnabled(),"Fresh state enables control");
                check(find(host,"已超时 · 45%")!=null && !find(host,"已超时 · 45%").isShown(),"Stage details collapsed by default");
                check(find(host,"查看历史记录")!=null,"History collapsed by default");
                find(host,"展开任务详情").performClick();
                check(find(host,"已超时 · 45%").isShown(),"Stage details open on demand");
                find(host,"隐藏记录").performClick();check(action[0].equals("hide_run:latest"),"Hide exact record");
                find(host,"标记已读").performClick();check(action[0].equals("read_summary:示例概要.md"),"Mark exact summary read");
                check(find(host,"已超时 · 45%")!=null,"Terminal run fixes stale stage state");
                check(find(host,"下载超时，请检查网络")!=null,"Failure reason rendered");
                check(find(host,"已超时 · 45%")!=null,"Progress rendered");
                check(find(host,"阅读")!=null && find(host,"视频")!=null,"Output actions rendered");
                find(host,"查看历史记录").performClick();
                Button expand=(Button)find(host,"展开任务详情");check(expand!=null,"History collapsed initially");expand.performClick();
                check(expand.getText().toString().contains("收起"),"Expand works");
                ui.render(data);check(find(host,"收起任务详情")!=null,"Refresh retains expansion");
                Bundle state=new Bundle();ui.save(state);DailyTasksView restored=new DailyTasksView(activity,host);restored.restore(state);restored.render(data);
                check(find(host,"收起任务详情")!=null,"Recreation retains expansion");
                ((Button)find(host,"显示已隐藏记录")).performClick();check(find(host,"收起已隐藏记录")!=null,"Hidden toggle works");
                restored.render(new JSONObject());check(find(host,"暂无可显示的运行记录")!=null,"Empty state rendered");
            });
            result.putString("stream","PASS: task details, stage status, progress, output actions, expansion, refresh, restoration, hidden and empty states\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}
        finally{if(screen!=null){Activity a=screen;runOnMainSync(a::finish);}}
    }
}
