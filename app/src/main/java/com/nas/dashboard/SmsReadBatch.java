package com.nas.dashboard;
import java.util.*;
import org.json.*;
/** Collect before committing so a failed page cannot partially mark the archive read. */
final class SmsReadBatch {
    interface PageSource { JSONObject page(int offset) throws Exception; }
    static Set<String> collect(PageSource source) throws Exception {
        Set<String> keys=new HashSet<>();
        int offset=0;
        while(true){
            JSONObject data=source.page(offset);
            JSONArray items=data.getJSONArray("items");
            for(int i=0;i<items.length();i++)keys.add(SmsReadStore.key(items.getJSONObject(i)));
            if(!data.optBoolean("has_more"))return keys;
            if(items.length()==0)throw new IllegalStateException("Empty continuation page");
            offset+=items.length();
        }
    }
}
