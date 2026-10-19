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
            screen=startActivitySync(new Intent(getTargetContext(),ServiceModuleActivity.class).putExtra("module","tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity activity=screen;
            JSONObject data=new JSONObject("{\"runs\":[{\"run_id\":\"latest\",\"run_date\":\"2026-10-19\",\"status\":\"timed_out\",\"error_message\":\"下载超时，请检查网络\",\"progress\":{\"stages\":[{\"id\":\"download\",\"label\":\"下载视频\",\"status\":\"running\",\"percent\":45,\"item\":\"示例视频\"}]}},{\"run_id\":\"history\",\"status\":\"success\",\"run_summary\":\"历史任务结果\"},{\"run_id\":\"hidden\",\"hidden\":true,\"status\":\"failed\"}],\"summaries\":[{\"summary_filename\":\"示例概要.md\",\"video_id\":\"fixture-video\"}]}");
            runOnMainSync(()->{
                LinearLayout host=activity.findViewById(R.id.overview_content);
                DailyTasksView ui=new DailyTasksView(activity,host);ui.render(data);
                check(find(host,"下载视频 · 已超时")!=null,"Terminal run fixes stale stage state");
                check(find(host,"下载超时，请检查网络")!=null,"Failure reason rendered");
                check(find(host,"进度 45%")!=null,"Progress rendered");
                check(find(host,"阅读全文")!=null && find(host,"观看关联视频")!=null,"Output actions rendered");
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
