package com.nas.dashboard;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class SmsReadBatchTest {
    private JSONObject page(boolean more,String... ids)throws Exception {
        JSONArray items=new JSONArray();
        for(String id:ids)items.put(new JSONObject().put("device_id","phone").put("message_id",id));
        return new JSONObject().put("items",items).put("has_more",more);
    }
    @Test public void collectsUnloadedPagesAndDeduplicates()throws Exception {
        List<Integer> offsets=new ArrayList<>();
        Set<String> keys=SmsReadBatch.collect(offset->{offsets.add(offset);return offset==0?page(true,"a","b"):page(false,"b","c");});
        assertEquals(Arrays.asList(0,2),offsets);assertEquals(3,keys.size());
    }
    @Test public void failedLaterPageDoesNotReturnPartialSuccess()throws Exception {
        try{SmsReadBatch.collect(offset->{if(offset>0)throw new java.io.IOException("offline");return page(true,"a");});fail();}
        catch(java.io.IOException expected){}
    }
    @Test public void rejectsEmptyContinuation()throws Exception {
        try{SmsReadBatch.collect(offset->page(true));fail();}
        catch(IllegalStateException expected){}
    }
    @Test public void emptyArchiveSucceeds()throws Exception {
        assertTrue(SmsReadBatch.collect(offset->page(false)).isEmpty());
    }
}
