package com.nas.dashboard;

import com.nas.dashboard.network.OverviewPolicy;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Same terminal-state and timestamp semantics as the Bird Nest task panel. */
public final class DailyTaskFormat {
    private DailyTaskFormat() {}
    static String value(JSONObject row, String key) {
        return row.isNull(key) ? "" : row.optString(key, "").trim();
    }
    public static String state(String state) {
        switch (state) {
            case "success": return "成功"; case "failed": return "失败";
            case "running": return "运行中"; case "pending": return "待执行";
            case "not_run": return "未运行"; case "skipped": return "已跳过";
            case "partial": return "部分完成"; case "interrupted": return "已中断";
            case "timed_out": return "已超时"; case "unresponsive": return "心跳异常";
            case "logging_failed": return "记录失败"; case "unknown": return "状态待确认";
            default: return state.isEmpty() ? "未记录" : state;
        }
    }
    public static String stageState(String run, String stage) {
        return "running".equals(stage) && !"running".equals(run) ? run : stage;
    }
    static String color(String state) {
        switch (state) {
            case "success": return "#52745F";
            case "failed": case "logging_failed": case "interrupted": case "timed_out": return "#A34D46";
            case "running": return "#4D669D";
            case "partial": case "unresponsive": return "#916524";
            default: return "#706B75";
        }
    }
    public static String time(String raw) {
        Long stamp = OverviewPolicy.parseTimestamp(raw);
        if (stamp == null) return "未记录";
        SimpleDateFormat f = new SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date(stamp));
    }
    public static String duration(String start, String end, String state, long now) {
        Long from = OverviewPolicy.parseTimestamp(start), to = OverviewPolicy.parseTimestamp(end);
        if (from == null || (to == null && !"running".equals(state))) return "未记录";
        long seconds = Math.max(0, ((to == null ? now : to) - from) / 1000);
        return (to == null ? "已运行 " : "耗时 ") + (seconds >= 3600 ? seconds / 3600 + " 小时 " : "")
            + (seconds >= 60 ? seconds / 60 % 60 + " 分 " : "") + seconds % 60 + " 秒";
    }
}
