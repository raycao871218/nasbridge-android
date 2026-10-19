package com.nas.dashboard;
import android.app.Activity;
import android.os.Bundle;
public final class SmsActivity extends Activity {
    private SmsController sms;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_sms);sms=new SmsController(this,findViewById(android.R.id.content),()->{});sms.restore(state);}
    @Override protected void onSaveInstanceState(Bundle state){sms.save(state);super.onSaveInstanceState(state);}
    @Override protected void onStart(){super.onStart();sms.start();}
    @Override protected void onStop(){sms.stop();super.onStop();}
    @Override protected void onDestroy(){sms.close();super.onDestroy();}
}
