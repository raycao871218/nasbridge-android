package com.nas.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Read-only task notebook: one expanded latest run, collapsible history, and recent outputs. */
final class DailyTasksView {
    private final Activity activity;
    private final LinearLayout host;
    private final Set<String> expanded = new HashSet<>();
    private boolean showHidden;
    private JSONObject snapshot;
    DailyTasksView(Activity activity, LinearLayout host) { this.activity=activity; this.host=host; }
    void save(Bundle state) { state.putStringArrayList("task_expanded", new ArrayList<>(expanded)); state.putBoolean("task_hidden",showHidden); }
    void restore(Bundle state) {
        if (state == null) return;
        ArrayList<String> saved=state.getStringArrayList("task_expanded");
        if(saved!=null) expanded.addAll(saved);
        showHidden=state.getBoolean("task_hidden");
    }
    static boolean hasRunning(JSONObject data) {
        JSONArray runs=data.optJSONArray("runs");
        for(int i=0;runs!=null && i<runs.length();i++) {
            JSONObject run=runs.optJSONObject(i);
            if(run!=null && "running".equals(run.optString("status"))) return true;
        }
        return false;
    }
    void render(JSONObject data) {
        snapshot=data;
        ScrollView scroll=(ScrollView)host.getParent().getParent();
        int position=scroll.getScrollY();
        host.removeAllViews();
        JSONArray runs=data.optJSONArray("runs");
        List<JSONObject> visible=new ArrayList<>(); int hidden=0;
        for(int i=0;runs!=null && i<runs.length();i++) {
            JSONObject run=runs.optJSONObject(i); if(run==null) continue;
            if(run.optBoolean("hidden")) hidden++;
            if(showHidden || !run.optBoolean("hidden")) visible.add(run);
        }
        heading("01  最近任务");
        if(visible.isEmpty()) text(card(),"暂无可显示的运行记录",15,"#706B75");
        else run(visible.get(0),true);
        heading("02  历史运行");
        if(hidden>0) button(host,(showHidden?"收起":"显示")+"已隐藏记录（"+hidden+"）",()->{showHidden=!showHidden;render(snapshot);});
        if(visible.size()<2) text(host,"暂无更多运行记录",14,"#706B75");
        for(int i=1;i<visible.size();i++) run(visible.get(i),false);
        text(host,"显示后台返回的最近运行记录；时间均为北京时间。",12,"#706B75");
        heading("03  最近产出");
        text(host,"后台最近更新的概要，不代表归属于上方某一次运行。",12,"#706B75");
        JSONArray summaries=data.optJSONArray("summaries");
        if(summaries==null || summaries.length()==0) text(card(),"暂时没有概要产出",14,"#706B75");
        for(int i=0;summaries!=null && i<summaries.length();i++) {
            JSONObject summary=summaries.optJSONObject(i); if(summary==null) continue;
            String id=DailyTaskFormat.value(summary,"summary_filename");
            LinearLayout box=card();
            text(box,id.isEmpty()?"未命名概要":id,16,"#3B3A49");
            text(box,(summary.optBoolean("is_read")?"已读":"未读")+" · "+DailyTaskFormat.time(summary.optString("updated_at")),12,"#706B75");
            if(!id.isEmpty()) {
                button(box,"阅读全文  →",()->open(ArticleActivity.class,id));
                if(!DailyTaskFormat.value(summary,"video_id").isEmpty()) button(box,"观看关联视频  →",()->open(VideoActivity.class,id));
            }
        }
        button(host,"查看全部文章  →",()->activity.startActivity(new Intent(activity,ArticlesActivity.class)));
        button(host,"浏览视频库  →",()->activity.startActivity(new Intent(activity,RhinoActivity.class)));
        scroll.post(()->scroll.scrollTo(0,position));
    }
    private void open(Class<?> type,String id) {
        activity.startActivity(new Intent(activity,type).putExtra("article_id",id).putExtra("article_title",id));
    }
    private void run(JSONObject run,boolean latest) {
        LinearLayout box=card(); String state=DailyTaskFormat.value(run,"status");
        text(box,(latest?"最近一次 · ":"")+run.optString("run_date","未记录日期")+(run.optBoolean("hidden")?" · 已隐藏":""),16,"#3B3A49");
        text(box,DailyTaskFormat.state(state),22,DailyTaskFormat.color(state));
        text(box,DailyTaskFormat.duration(run.optString("started_at"),run.optString("finished_at"),state,System.currentTimeMillis()),13,"#706B75");
        text(box,"视频 "+run.optInt("video_count")+"    原文 "+run.optInt("transcript_count")+"    概要 "+run.optInt("summary_count"),14,"#4D669D");
        String message=DailyTaskFormat.value(run,"status_message");
        if(!message.isEmpty()) text(box,message,14,DailyTaskFormat.color(state));
        JSONObject progress=run.optJSONObject("progress");
        JSONArray stages=progress==null?null:progress.optJSONArray("stages");
        if("running".equals(state) && stages!=null) {
            for(int i=0;i<stages.length();i++) {JSONObject stage=stages.optJSONObject(i);
                if(stage!=null && stage.optString("id").equals(progress.optString("current_stage")))
                    text(box,"当前阶段 · "+stage.optString("label","准备任务"),16,"#4D669D");
            }
        }
        LinearLayout details=new LinearLayout(activity);details.setOrientation(LinearLayout.VERTICAL);
        String key=DailyTaskFormat.value(run,"run_id");
        if(!latest) button(box,expanded.contains(key)?"收起任务详情 −":"展开任务详情 ＋",()->{
            boolean open=details.getVisibility()!=View.VISIBLE;
            if(open) expanded.add(key); else expanded.remove(key);
            details.setVisibility(open?View.VISIBLE:View.GONE);
            ((Button)box.getChildAt(box.indexOfChild(details)-1)).setText(open?"收起任务详情 −":"展开任务详情 ＋");
        });
        box.addView(details); details.setVisibility(latest||expanded.contains(key)?View.VISIBLE:View.GONE);
        text(details,"开始  "+DailyTaskFormat.time(run.optString("started_at"))+"\n结束  "+("running".equals(state)?"尚未结束":DailyTaskFormat.time(run.optString("finished_at"))),13,"#706B75");
        text(details,"媒体处理 · "+DailyTaskFormat.state(run.optString("media_status"))+"\n概要生成 · "+DailyTaskFormat.state(run.optString("summary_status"))+"\n分析技能 · "+(run.optBoolean("skill_updated")?"已更新":"未变化"),14,"#514D58");
        String error=DailyTaskFormat.value(run,"error_message"), result=DailyTaskFormat.value(run,"run_summary");
        if(!error.isEmpty()) text(details,"失败原因\n"+error,14,"#A34D46").setTextIsSelectable(true);
        if(!result.isEmpty()) text(details,"执行结果\n"+result,14,"#514D58").setTextIsSelectable(true);
        if(stages==null || stages.length()==0) text(details,"这条记录没有逐阶段进度，仍可查看上方执行结果。",13,"#706B75");
        else {
            text(details,"执行阶段",17,"#3B3A49");
            text(details,"最近心跳  "+DailyTaskFormat.time(progress.optString("heartbeat_at"))+"\n最近进展  "+DailyTaskFormat.time(progress.optString("last_progress_at")),12,"#706B75");
            for(int i=0;i<stages.length();i++) {
                JSONObject stage=stages.optJSONObject(i);if(stage==null)continue;
                String status=DailyTaskFormat.stageState(state,stage.optString("status"));
                text(details,String.format(Locale.CHINA,"%02d  %s · %s",i+1,stage.optString("label","未命名阶段"),DailyTaskFormat.state(status)),15,DailyTaskFormat.color(status));
                for(String field:new String[]{"message","item"}) {String v=DailyTaskFormat.value(stage,field);if(!v.isEmpty())text(details,v,13,"#514D58");}
                Object percent=stage.opt("percent");
                if(percent instanceof Number && Double.isFinite(((Number)percent).doubleValue())) {
                    int value=(int)Math.max(0,Math.min(100,((Number)percent).doubleValue()));
                    ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(value);
                    details.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));text(details,"进度 "+value+"%",12,"#706B75");
                }
                if(!DailyTaskFormat.value(stage,"started_at").isEmpty()) text(details,"开始 "+DailyTaskFormat.time(stage.optString("started_at"))
                    +(DailyTaskFormat.value(stage,"finished_at").isEmpty()?"":"\n结束 "+DailyTaskFormat.time(stage.optString("finished_at"))),12,"#706B75");
            }
        }
    }
    private LinearLayout card() {
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(18));box.setBackgroundResource(R.drawable.card);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);host.addView(box,p);return box;
    }
    private void heading(String label) {TextView v=text(host,label,20,"#3B3A49");v.setPadding(0,dp(26),0,dp(4));}
    private TextView text(LinearLayout parent,String value,int size,String color) {
        TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(Color.parseColor(color));v.setLineSpacing(dp(4),1);v.setPadding(0,dp(9),0,dp(5));parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;
    }
    private void button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(activity);b.setText(label);b.setTextColor(Color.parseColor("#3B3A49"));b.setTextSize(14);b.setAllCaps(false);b.setBackgroundResource(R.drawable.button);b.setMinHeight(dp(48));
        b.setBackgroundTintList(null);b.setPadding(dp(16),dp(12),dp(16),dp(12));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
