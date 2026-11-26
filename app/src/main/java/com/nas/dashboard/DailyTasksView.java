package com.nas.dashboard;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Compact task summary with optional execution details. */
final class DailyTasksView {
    private final Activity activity;
    private final LinearLayout host;
    private final Set<String> expanded = new HashSet<>();
    private boolean showHidden;
    private JSONObject snapshot;
    interface Actions { void perform(String action, String id); }
    private Actions actions;
    private Runnable dayChanged;
    private String selectedDay = "";
    private boolean busy;
    private boolean controlStale;
    void accept(JSONObject data) { controlStale=false; render(data); }
    private String feedback = "";
    void setActions(Actions value) { actions=value; }
    void setDayChanged(Runnable callback) { dayChanged=callback; }
    String selectedDay() { return selectedDay; }
    static String taskDay(JSONObject control, String selected) {
        if (control == null) return "";
        String today=control.optString("today");
        return !selected.isEmpty() && selected.compareTo(control.optString("first_date"))>=0
            && selected.compareTo(today)<=0 ? selected : today;
    }
    void setBusy(boolean value,String message) { busy=value; controlStale=true; feedback=message; if(snapshot!=null)render(snapshot); }
    static boolean needsRefresh(JSONObject data) {
        JSONObject c=data.optJSONObject("manual_control");
        JSONObject r=c==null?null:c.optJSONObject("retry");
        return hasRunning(data) || (c!=null && c.optBoolean("running")) || (r!=null &&
            ("waiting".equals(r.optString("status")) || "retrying".equals(r.optString("status"))));
    }
    private void actionButton(LinearLayout parent,String label,String action,String id,boolean enabled) {
        button(parent,label,()->{if(!busy && !controlStale && actions!=null)actions.perform(action,id);});
        parent.getChildAt(parent.getChildCount()-1).setEnabled(enabled && !busy && !controlStale && actions!=null);
    }
    DailyTasksView(Activity activity, LinearLayout host) { this.activity=activity; this.host=host; }
    void save(Bundle state) {
        state.putStringArrayList("task_expanded", new ArrayList<>(expanded));
        state.putBoolean("task_hidden",showHidden);
        state.putString("task_day",selectedDay);
    }
    void restore(Bundle state) {
        if (state == null) return;
        ArrayList<String> saved=state.getStringArrayList("task_expanded");
        if(saved!=null) expanded.addAll(saved);
        showHidden=state.getBoolean("task_hidden");
        selectedDay=state.getString("task_day","");
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
        control(data);
        JSONArray runs=data.optJSONArray("runs");
        List<JSONObject> visible=new ArrayList<>(); int hidden=0;
        for(int i=0;runs!=null && i<runs.length();i++) {
            JSONObject run=runs.optJSONObject(i); if(run==null) continue;
            if(run.optBoolean("hidden")) hidden++;
            if(showHidden || !run.optBoolean("hidden")) visible.add(run);
        }
        heading("最近任务");
        if(visible.isEmpty()) text(card(),"暂无可显示的运行记录",15,"#706B75");
        else run(visible.get(0),true);
        boolean history=expanded.contains("section:history");
        button(host,(history?"收起":"查看")+"历史记录（"+Math.max(0,visible.size()-1)+"）",()->toggle("section:history"));
        if(history) {
            if(hidden>0) button(host,(showHidden?"收起":"显示")+"已隐藏记录（"+hidden+"）",()->{showHidden=!showHidden;render(snapshot);});
            if(visible.size()<2) text(host,"暂无更多运行记录",14,"#706B75");
            for(int i=1;i<visible.size();i++) run(visible.get(i),false);
        }
        heading("最近概要");
        JSONArray summaries=data.optJSONArray("summaries");
        if(summaries==null || summaries.length()==0) text(card(),"暂时没有概要产出",14,"#706B75");
        for(int i=0;summaries!=null && i<(expanded.contains("section:outputs")?summaries.length():Math.min(3,summaries.length()));i++) {
            JSONObject summary=summaries.optJSONObject(i); if(summary==null) continue;
            String id=DailyTaskFormat.value(summary,"summary_filename");
            LinearLayout box=card();
            text(box,id.isEmpty()?"未命名概要":id,16,"#3B3A49");
            text(box,(summary.optBoolean("is_read")?"已读":"未读")+" · "+DailyTaskFormat.time(summary.optString("updated_at")),12,"#706B75");
            if(!id.isEmpty()) {
                LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);box.addView(row);
                if(!summary.optBoolean("is_read"))actionButton(row,"标记已读","read_summary",id,true);
                button(row,"阅读",()->open(ArticleActivity.class,id));
                if(!DailyTaskFormat.value(summary,"video_id").isEmpty()) button(row,"视频",()->open(VideoActivity.class,id));
            }
        }
        if(summaries!=null && summaries.length()>3)button(host,expanded.contains("section:outputs")?"收起概要":"更多概要",()->toggle("section:outputs"));
        button(host,"查看全部文章  →",()->activity.startActivity(new Intent(activity,ArticlesActivity.class)));
        button(host,"浏览视频库  →",()->activity.startActivity(new Intent(activity,RhinoActivity.class)));
        scroll.post(()->scroll.scrollTo(0,position));
    }
    private void control(JSONObject data) {
        JSONObject c=data.optJSONObject("manual_control");if(c==null)c=new JSONObject();
        LinearLayout box=card();text(box,"每日任务",20,"#3B3A49");
        if(!c.optBoolean("can_trigger") && !c.optBoolean("running"))text(box,c.optString("message","暂时无法确认执行状态"),14,"#514D58");
        String day=taskDay(c,selectedDay);
        boolean historical=!day.equals(c.optString("today"));
        if (!day.isEmpty()) {
            String first=c.optString("first_date"), today=c.optString("today");
            button(box,"视频日期  "+day+"  ›",()->pickDay(first,today,day));
            JSONObject result=data.optJSONObject("selected_day");
            String label="正在查询所选日期…";
            if (result!=null && day.equals(result.optString("date"))) {
                if (result.optBoolean("unavailable")) label="暂时无法确认这一天的抓取记录，请刷新重试";
                else {
                    int count=result.optInt("archived_count");
                    JSONObject latest=result.optJSONObject("latest_run");
                    label=count>0?"已成功归档 "+count+" 部视频":
                        latest!=null && latest.optInt("matched_count",-1)==0?"已检查，当天没有发现公开视频":"尚无已归档视频";
                    label+=latest==null?" · 无运行记录":" · 最近任务："+DailyTaskFormat.state(latest.optString("status"));
                }
            }
            text(box,label,14,"#514D58");
        }
        if (historical && !c.optBoolean("backfill")) text(box,"执行端尚未启用往日补抓",14,"#514D58");
        else if (historical && !c.optBoolean("can_backfill") && !c.optBoolean("running"))
            text(box,"自动重试正在执行，请稍后补抓",14,"#514D58");
        actionButton(box,busy?"正在处理…":c.optBoolean("running")?"执行中…":historical?"补抓所选日期":"手动执行",
            "start",historical?day:"", "ok".equals(data.optString("status")) && c.optBoolean("can_trigger")
                && (!historical || (c.optBoolean("backfill") && c.optBoolean("can_backfill"))));
        if(!feedback.isEmpty())text(box,feedback,14,"#4D669D");
        JSONObject r=c.optJSONObject("retry");if(r==null)r=new JSONObject();
        String title="自动重试状态待确认",detail="暂时无法读取执行端状态，请稍后刷新。",timing="";
        if(r.optBoolean("available"))switch(r.optString("status")) {
            case "waiting":title="等待 11:00 自动重试";detail="本轮未发现新视频，已安排再检查一次。";
                timing="预计再次检查："+DailyTaskFormat.time(r.optString("retry_at"));
                Long at=com.nas.dashboard.network.OverviewPolicy.parseTimestamp(r.optString("retry_at"));
                Long observed=com.nas.dashboard.network.OverviewPolicy.parseTimestamp(c.optString("observed_at"));
                if(at!=null && observed!=null)timing+=at>observed?" · 约 "+(long)Math.ceil((at-observed)/60000.0)+" 分钟后":
                    c.optBoolean("running")?" · 已到时间，等待当前任务结束":" · 已到时间，等待执行端启动";
                break;
            case "retrying":title="自动重试执行中";detail="正在再次检查、下载及后续处理，本次结束后不再自动重试。";
                timing="重试开始："+DailyTaskFormat.time(r.optString("attempted_at"));break;
            case "completed":
                boolean failed=r.opt("returncode") instanceof Number?r.optInt("returncode")!=0:
                    !r.optString("service_result").isEmpty() && !"success".equals(r.optString("service_result")) && !"unknown".equals(r.optString("service_result"));
                title=failed?"自动重试未成功":"自动重试已结束";detail="本次自动重试已结束，不会因没有新视频继续重试。";
                timing="结束时间："+DailyTaskFormat.time(r.optString("finished_at"));break;
            case "cancelled":title="自动重试已取消";detail="后续检查已发现新视频，取消尚未执行的重试。";timing="原安排日期："+r.optString("date","待确认");break;
            case "none":title="暂无自动重试安排";detail="10 点确认没有新视频时，将安排 11 点再检查一次。";break;
        }
        text(box,title+(c.optBoolean("shutdown_protected")?" · 关机保护中":""),14,"#706B75");
        if("waiting".equals(r.optString("status")) && !timing.isEmpty())text(box,timing,13,"#706B75");
        boolean show=expanded.contains("section:retry");
        button(box,show?"收起重试说明":"重试详情",()->toggle("section:retry"));
        if(show) {
            text(box,detail,14,"#514D58");
            if(!timing.isEmpty() && !"waiting".equals(r.optString("status")))text(box,timing+"（北京时间）",13,"#706B75");
            text(box,c.isNull("shutdown_protected")?"关机保护状态待确认":c.optBoolean("shutdown_protected")?
                "任务执行或等待重试期间暂停自动关机。":"自动关机遵循原有时段及空闲判断。",13,"#706B75");
        }
    }
    private void pickDay(String first, String today, String selected) {
        LocalDate initial=LocalDate.parse(selected);
        DatePickerDialog dialog=new DatePickerDialog(activity,(view,year,month,day)->{
            selectedDay=LocalDate.of(year,month+1,day).toString();
            render(snapshot);
            if (dayChanged!=null) dayChanged.run();
        },initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());
        ZoneId local=ZoneId.systemDefault();
        dialog.getDatePicker().setMinDate(LocalDate.parse(first).atStartOfDay(local).toInstant().toEpochMilli());
        dialog.getDatePicker().setMaxDate(LocalDate.parse(today).atStartOfDay(local).toInstant().toEpochMilli());
        dialog.show();
    }
    private void toggle(String key) {
        if(!expanded.remove(key))expanded.add(key);
        render(snapshot);
    }
    private void open(Class<?> type,String id) {
        activity.startActivity(new Intent(activity,type).putExtra("article_id",id).putExtra("article_title",id));
    }
    private void run(JSONObject run,boolean latest) {
        LinearLayout box=card(); String state=DailyTaskFormat.value(run,"status");
        text(box,(latest?"最近一次 · ":"")+run.optString("run_date","未记录日期")+(run.optBoolean("hidden")?" · 已隐藏":""),16,"#3B3A49");
        text(box,DailyTaskFormat.state(state)+" · "+DailyTaskFormat.duration(run.optString("started_at"),run.optString("finished_at"),state,System.currentTimeMillis()),18,DailyTaskFormat.color(state));

        text(box,"视频 "+run.optInt("video_count")+"    原文 "+run.optInt("transcript_count")+"    概要 "+run.optInt("summary_count"),14,"#4D669D");
        String message=DailyTaskFormat.value(run,"status_message");
        if(!message.isEmpty()) { TextView note=text(box,message,14,DailyTaskFormat.color(state));note.setMaxLines(2);note.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        JSONObject progress=run.optJSONObject("progress");
        JSONArray stages=progress==null?null:progress.optJSONArray("stages");
        if("running".equals(state) && stages!=null) {
            for(int i=0;i<stages.length();i++) {JSONObject stage=stages.optJSONObject(i);
                if(stage!=null && stage.optString("id").equals(progress.optString("current_stage")))
                    text(box,"当前 · "+stage.optString("label","准备任务")+(stage.opt("percent") instanceof Number?" · "+Math.max(0,Math.min(100,stage.optInt("percent")))+"%":""),15,"#4D669D");
            }
        }
        String runId=DailyTaskFormat.value(run,"run_id");

        LinearLayout details=new LinearLayout(activity);details.setOrientation(LinearLayout.VERTICAL);
        String key=DailyTaskFormat.value(run,"run_id");
        button(box,expanded.contains(key)?"收起任务详情 −":"展开任务详情 ＋",()->{
            boolean open=details.getVisibility()!=View.VISIBLE;
            if(open) expanded.add(key); else expanded.remove(key);
            details.setVisibility(open?View.VISIBLE:View.GONE);
            ((Button)box.getChildAt(box.indexOfChild(details)-1)).setText(open?"收起任务详情 −":"展开任务详情 ＋");
        });
        box.addView(details); details.setVisibility(expanded.contains(key)?View.VISIBLE:View.GONE);
        String start=DailyTaskFormat.time(run.optString("started_at"));
        String finish="running".equals(state)?"进行中":DailyTaskFormat.time(run.optString("finished_at"));
        compactRow(details,"时间",start+" → "+finish,"#706B75");
        compactRow(details,"处理结果","媒体 "+DailyTaskFormat.state(run.optString("media_status"))+" · 概要 "+DailyTaskFormat.state(run.optString("summary_status")),"#514D58");
        if(run.optBoolean("skill_updated"))compactRow(details,"分析技能","已更新","#52745F");
        if(stages==null || stages.length()==0)compactRow(details,"执行阶段","无阶段记录","#706B75");
        else {
            View line=new View(activity);line.setBackgroundColor(Color.parseColor("#DED8CE"));
            LinearLayout.LayoutParams divider=new LinearLayout.LayoutParams(-1,dp(1));divider.topMargin=dp(10);divider.bottomMargin=dp(6);details.addView(line,divider);
            for(int i=0;i<stages.length();i++) {
                JSONObject stage=stages.optJSONObject(i);if(stage==null)continue;
                String status=DailyTaskFormat.stageState(state,stage.optString("status"));
                String progressText="";Object percent=stage.opt("percent");
                if(percent instanceof Number && Double.isFinite(((Number)percent).doubleValue()))
                    progressText=" · "+(int)Math.max(0,Math.min(100,((Number)percent).doubleValue()))+"%";
                LinearLayout row=compactRow(details,stage.optString("label","未命名阶段"),DailyTaskFormat.state(status)+progressText+"  ›",DailyTaskFormat.color(status));
                StringBuilder log=new StringBuilder();
                for(String field:new String[]{"item","message"}) {
                    String value=DailyTaskFormat.value(stage,field);
                    if(!value.isEmpty() && log.indexOf(value)<0)log.append(value).append("\n\n");
                }
                for(String field:new String[]{"started_at","finished_at"}) {
                    String value=DailyTaskFormat.value(stage,field);
                    if(!value.isEmpty())log.append(field.equals("started_at")?"开始：":"结束：").append(DailyTaskFormat.time(value)).append('\n');
                }
                String content=log.toString().trim();
                row.setOnClickListener(v->showLog(stage.optString("label","阶段详情"),content.isEmpty()?"暂无详细记录":content));
                row.setBackgroundResource(android.R.drawable.list_selector_background);
                row.setContentDescription(stage.optString("label")+"，"+DailyTaskFormat.state(status)+progressText+"，查看日志");
            }
            if("running".equals(state))compactRow(details,"心跳",DailyTaskFormat.time(progress.optString("heartbeat_at")),"#706B75");
        }
        String error=DailyTaskFormat.value(run,"error_message"), result=DailyTaskFormat.value(run,"run_summary");
        if(!error.isEmpty()) {
            TextView issue=text(box,error,14,"#A34D46");issue.setMaxLines(2);issue.setEllipsize(android.text.TextUtils.TruncateAt.END);
            issue.setOnClickListener(v->showLog("失败原因",error));
        }
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(LinearLayout.HORIZONTAL);details.addView(actions);
        if(!error.isEmpty() || !result.isEmpty())button(actions,"查看报告",()->showLog("执行报告",
            (error.isEmpty()?"":"失败原因\n"+error+"\n\n")+result));
        actionButton(actions,run.optBoolean("hidden")?"恢复显示":"隐藏记录",run.optBoolean("hidden")?"restore_run":"hide_run",runId,!runId.isEmpty());
    }
    private LinearLayout compactRow(LinearLayout parent,String label,String value,String color) {
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));row.setMinimumHeight(dp(48));
        TextView name=new TextView(activity);name.setText(label);name.setTextSize(14);name.setTextColor(Color.parseColor("#514D58"));
        TextView detail=new TextView(activity);detail.setText(value);detail.setTextSize(13);detail.setTextColor(Color.parseColor(color));detail.setGravity(android.view.Gravity.END);detail.setPadding(dp(12),0,0,0);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(detail,new LinearLayout.LayoutParams(0,-2,2));
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;
    }
    private void showLog(String title,String content) {
        ScrollView scroll=new ScrollView(activity);
        TextView body=new TextView(activity);body.setText(content);body.setTextSize(14);body.setTextColor(Color.parseColor("#514D58"));
        body.setTextIsSelectable(true);body.setPadding(dp(20),dp(12),dp(20),dp(20));scroll.addView(body);
        new android.app.AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("关闭",null).show();
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
        LinearLayout.LayoutParams p=parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
