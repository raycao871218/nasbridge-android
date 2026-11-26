package com.nas.dashboard.network;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DailyTaskActionTest {
    static class Connection extends HttpURLConnection {
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        String response="{\"status\":\"accepted\"}";
        int code=202;
        Connection() throws Exception {super(new URL("http://fixture.invalid"));}
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public int getResponseCode(){return code;}
        public OutputStream getOutputStream(){return sent;}
        public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
    }
    @Test public void startsOnceWithEmptyJsonAndNoRedirects() throws Exception {
        Connection c=new Connection();
        new NasBridgeClient().dailyTaskAction(path->{assertEquals("api/daily-tasks/run",path);return c;},"start","");
        assertEquals("POST",c.getRequestMethod());
        assertEquals("{}",c.sent.toString("UTF-8"));
        assertFalse(c.getInstanceFollowRedirects());
        assertEquals(30000,c.getReadTimeout());
    }
    @Test public void persistsExactRecordActions() throws Exception {
        for(String action:new String[]{"hide_run","restore_run","read_summary"}) {
            Connection c=new Connection();c.code=200;c.response="{\"status\":\"ok\"}";
            new NasBridgeClient().dailyTaskAction(path->{assertEquals("api/daily-tasks/state",path);return c;},action,"概要.md");
            JSONObject body=new JSONObject(c.sent.toString("UTF-8"));
            assertEquals(action,body.getString("action"));assertEquals("概要.md",body.getString("id"));
        }
    }
    @Test public void sendsSelectedDateToHistoricalRunAndStatusEndpoints() throws Exception {
        Connection start=new Connection();
        NasBridgeClient client=new NasBridgeClient();
        client.dailyTaskAction(path->{assertEquals("api/daily-tasks/run",path);return start;},"start","2026-11-05");
        assertEquals("2026-11-05",new JSONObject(start.sent.toString("UTF-8")).getString("date"));
        Connection status=new Connection();
        status.code=200;
        status.response="{\"archived_count\":2}";
        assertEquals(2,client.dailyTaskDay(path->{
            assertEquals("api/daily-tasks/day?date=2026-11-05",path);return status;
        },"2026-11-05").getInt("archived_count"));
    }
    @Test public void refusesConflictAndUnconfirmedResultsWithoutRetry() throws Exception {
        for(int code:new int[]{409,503,200}) {
            Connection c=new Connection();c.code=code;c.response="{\"status\":\"unknown\"}";
            int[] calls={0};
            try {new NasBridgeClient().dailyTaskAction(path->{calls[0]++;return c;},"start","");fail();}
            catch(IOException expected){assertEquals(1,calls[0]);}
        }
    }
}
