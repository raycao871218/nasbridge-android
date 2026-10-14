package com.nas.dashboard;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.Locale;

/** Locks one axis per gesture; seeks only on release and changes brightness only for this window. */
final class VideoGestures implements View.OnTouchListener {
    interface Playback {
        boolean ready();
        int position();
        int duration();
        void seek(int milliseconds);
    }
    private final Activity activity;
    private final AudioManager audio;
    private final TextView feedback;
    private final Playback playback;
    private final Handler handler=new Handler();
    private final Runnable hide;
    private float x,y,brightness;
    private int mode,position,target,volume;
    private boolean tracking;
    VideoGestures(Activity activity, TextView feedback, Playback playback) {
        this.activity=activity;this.feedback=feedback;this.playback=playback;
        hide=() -> feedback.setVisibility(View.GONE);
        audio=(AudioManager)activity.getSystemService(Activity.AUDIO_SERVICE);
    }
    @Override public boolean onTouch(View view,MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            tracking=true;mode=0;x=event.getX();y=event.getY();
            position=playback.ready()?playback.position():0;target=position;
            volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            brightness=activity.getWindow().getAttributes().screenBrightness;
            if(brightness<0) {
                try {brightness=Settings.System.getInt(activity.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128)/255f;}
                catch(SecurityException unavailable){brightness=0.5f;}
            }
            handler.removeCallbacks(hide); return true;
        }
        if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN) {cancel();return true;}
        if(!tracking)return true;
        if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
            float dx=event.getX()-x,dy=event.getY()-y;
            float threshold=12*view.getResources().getDisplayMetrics().density;
            if(mode==0 && Math.max(Math.abs(dx),Math.abs(dy))>threshold) {
                mode=Math.abs(dx)>Math.abs(dy)*1.15f ? 1 : Math.abs(dy)>Math.abs(dx)*1.15f ? (x<view.getWidth()/2f?2:3) : 0;
            }
            if(mode==1 && playback.ready()) {
                int span=Math.min(120000,playback.duration());
                target=(int)Math.max(0,Math.min(Math.max(0,playback.duration()-1),position+Math.round(dx/Math.max(1,view.getWidth())*span)));
                int delta=(target-position)/1000;
                show((delta>=0?"快进 +":"快退 ")+delta+" 秒\n"+clock(target)+" / "+clock(playback.duration()));
            } else if(mode==2) {
                float value=Math.max(0.02f,Math.min(1f,brightness-dy/Math.max(1,view.getHeight())));
                WindowManager.LayoutParams params=activity.getWindow().getAttributes(); params.screenBrightness=value; activity.getWindow().setAttributes(params);
                show("亮度 "+Math.round(value*100)+"%");
            } else if(mode==3) {
                int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int value=Math.max(0,Math.min(maximum,Math.round(volume-dy/Math.max(1,view.getHeight())*maximum)));
                try {
                    if(!audio.isVolumeFixed())audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);
                    int actual=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    show("音量 "+(maximum==0?0:Math.round(actual*100f/maximum))+"%");
                } catch(SecurityException unavailable){show("系统暂不允许调整音量");}
            }
            if(action==MotionEvent.ACTION_UP) {
                tracking=false;
                if(mode==0)view.performClick();
                else if(mode==1 && playback.ready())playback.seek(target);
                handler.postDelayed(hide,800);
            }
        }
        return true;
    }
    private void show(String message){feedback.setText(message);feedback.setVisibility(View.VISIBLE);}
    private static String clock(int ms){int seconds=ms/1000;return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
    void cancel(){tracking=false;mode=0;handler.removeCallbacks(hide);feedback.setVisibility(View.GONE);}
}
