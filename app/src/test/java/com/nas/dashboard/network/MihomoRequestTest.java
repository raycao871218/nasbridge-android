package com.nas.dashboard.network;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import static org.junit.Assert.*;

public class MihomoRequestTest {
    static class Connection extends DailyTaskActionTest.Connection {
        String error="{\"detail\":\"YAML 格式错误\"}";
        Connection() throws Exception {super();code=200;response="{\"status\":\"applied\"}";}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(error.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void writesConfigAsJsonWithoutFollowingRedirects() throws Exception {
        Connection c=new Connection();String yaml="external-controller: :9090\nrules:\n  - MATCH,DIRECT\n# 测试\n";
        new NasBridgeClient().mihomoRequest(p->{assertEquals("api/mihomo/config",p);return c;},"PUT","api/mihomo/config",new JSONObject().put("content",yaml));
        assertEquals("PUT",c.getRequestMethod());assertEquals(yaml,new JSONObject(c.sent.toString("UTF-8")).getString("content"));assertFalse(c.getInstanceFollowRedirects());
    }
    @Test public void aliasAndContainerMethodsUseExactEndpoint() throws Exception {
        for(String[] entry:new String[][]{{"DELETE","api/mihomo/source-aliases/fixture"},{"PUT","api/mihomo/source-aliases"},{"POST","api/mihomo/container/restart"}}){
            Connection c=new Connection();new NasBridgeClient().mihomoRequest(p->{assertEquals(entry[1],p);return c;},entry[0],entry[1],null);
            assertEquals(entry[0],c.getRequestMethod());
        }
    }
    @Test public void preservesValidationErrorAndDoesNotRetryWrites() throws Exception {
        for(int code:new int[]{400,409,502}){
            Connection c=new Connection();c.code=code;int[] calls={0};
            try{new NasBridgeClient().mihomoRequest(p->{calls[0]++;return c;},"PUT","api/mihomo/config",new JSONObject());fail();}
            catch(IOException e){assertEquals("YAML 格式错误",e.getMessage());assertEquals(1,calls[0]);}
        }
    }
    @Test public void proxySelectionKeepsGroupAndNodeNamesInJson() throws Exception {
        Connection c=new Connection();c.response="{\"status\":\"ok\"}";
        JSONObject body=new JSONObject().put("group","PROXY / 中文").put("name","JP #1");
        new NasBridgeClient().mihomoRequest(p->{assertEquals("api/mihomo/proxies/selection",p);return c;},"PUT","api/mihomo/proxies/selection",body);
        JSONObject actual=new JSONObject(c.sent.toString("UTF-8"));
        assertEquals("PROXY / 中文",actual.getString("group"));assertEquals("JP #1",actual.getString("name"));
    }
    @Test public void proxyAutoAndDelayUseSeparateExplicitRequests() throws Exception {
        for(String path:new String[]{"automatic","delay"}){
            Connection c=new Connection();
            new NasBridgeClient().mihomoRequest(p->{assertEquals("api/mihomo/proxies/"+path,p);return c;},"POST","api/mihomo/proxies/"+path,new JSONObject().put(path.equals("automatic")?"group":"name","fixture"));
            assertEquals("POST",c.getRequestMethod());assertFalse(c.getInstanceFollowRedirects());
        }
    }
    @Test public void allowsFullSizedConfigResponses() throws Exception {
        Connection c=new Connection();String value="a".repeat(2200000);c.response=new JSONObject().put("content",value).toString();
        assertEquals(value,new NasBridgeClient().mihomoRequest(p->c,"GET","api/mihomo/config",null).getString("content"));
    }
}
