package com.nas.dashboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class DailyTaskFormatTest {
    @Test public void terminatedRunCannotKeepStageRunning() {
        for(String terminal:new String[]{"failed","timed_out","interrupted","success"})
            assertEquals(terminal,DailyTaskFormat.stageState(terminal,"running"));
        assertEquals("success",DailyTaskFormat.stageState("failed","success"));
        assertEquals("pending",DailyTaskFormat.stageState("failed","pending"));
        assertEquals("running",DailyTaskFormat.stageState("running","running"));
    }
    @Test public void missingFinishDoesNotTurnFailedRunIntoLiveTimer() {
        assertEquals("未记录",DailyTaskFormat.duration("2026-10-19T01:00:00Z","","failed",0));
        assertEquals("未记录",DailyTaskFormat.duration("invalid","","running",0));
        assertEquals("耗时 1 小时 2 分 3 秒",DailyTaskFormat.duration("2026-10-19T01:00:00Z","2026-10-19T02:02:03Z","success",0));
        assertEquals("耗时 0 秒",DailyTaskFormat.duration("2026-10-19T02:00:00Z","2026-10-19T01:00:00Z","success",0));
    }
    @Test public void timestampUsesShanghaiAndHandlesNullText() {
        assertEquals("10月19日 09:00:00",DailyTaskFormat.time("2026-10-19T01:00:00Z"));
        assertEquals("10月19日 09:00:00",DailyTaskFormat.time("2026-10-19T09:00:00+08:00"));
        assertEquals("未记录",DailyTaskFormat.time("null"));
        assertEquals("已超时",DailyTaskFormat.state("timed_out"));
    }
}
