package com.nas.dashboard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.view.View;
import android.widget.SeekBar;
import com.nas.dashboard.network.HostSettings;
import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.VideoDataSource;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit read-only live playback smoke test; original configuration is always restored. */
public final class VideoInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int checks;
    @Override public void onCreate(Bundle args) { arguments = args; super.onCreate(args); start(); }
    private void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); checks++; }
    private String text(Activity page) { return text(page,R.id.video_status); }
    private String text(Activity page,int viewId) {
        AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> value.set(((TextView)page.findViewById(viewId)).getText().toString()));
        return value.get();
    }
    private void waitText(Activity page, String expected) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 45000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            String current = text(page);
            if (current.equals(expected)) return;
            if (current.contains("失败")) throw new AssertionError(current);
            Thread.sleep(100);
        }
        throw new AssertionError("Expected " + expected + "; got " + text(page));
    }
    private void selectSpeed(Activity page, String label) throws Exception {
        java.lang.reflect.Field field=VideoActivity.class.getDeclaredField("speedMenu"); field.setAccessible(true);
        runOnMainSync(() -> {
            page.findViewById(R.id.video_speed).performClick();
            try {
                android.widget.PopupMenu menu=(android.widget.PopupMenu)field.get(page);
                require(menu!=null && menu.getMenu().size()==7,"Seven speed choices shown");
                boolean selected=false;
                for(int i=0;i<menu.getMenu().size();i++) if(label.contentEquals(menu.getMenu().getItem(i).getTitle())) {
                    selected=menu.getMenu().performIdentifierAction(i,0); break;
                }
                require(selected,"Speed menu action handled"); menu.dismiss();
            } catch(Exception error) { throw new RuntimeException(error); }
        });
        Thread.sleep(300);
    }
    private void checkSpeed(Activity page, float expected, boolean playing) throws Exception {
        java.lang.reflect.Field field=VideoActivity.class.getDeclaredField("player"); field.setAccessible(true);
        java.lang.reflect.Field selected=VideoActivity.class.getDeclaredField("playbackSpeed"); selected.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<float[]> values=new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try {
                android.media.MediaPlayer player=(android.media.MediaPlayer)field.get(page);
                values.set(new float[]{player.getPlaybackParams().getSpeed(),player.getPlaybackParams().getPitch(),player.isPlaying()?1:0,selected.getFloat(page)});
            } catch(Exception error) { throw new RuntimeException(error); }
        });
        float[] v=values.get();
        require(Math.abs(v[3]-expected)<0.01f,"Selected speed retained: "+java.util.Arrays.toString(v));
        if(playing) require(Math.abs(v[0]-expected)<0.01f,"Requested speed applied to playing media: "+java.util.Arrays.toString(v));
        require(Math.abs(v[1]-1f)<0.01f,"Voice pitch unchanged");
        require((v[2]==1)==playing,"Speed selection preserves paused/playing state");
    }
    private int mediaPosition(Activity page) throws Exception {
        java.lang.reflect.Field field=VideoActivity.class.getDeclaredField("player");field.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger value=new java.util.concurrent.atomic.AtomicInteger();
        runOnMainSync(() -> {try{value.set(((android.media.MediaPlayer)field.get(page)).getCurrentPosition());}catch(Exception e){throw new RuntimeException(e);}});
        return value.get();
    }
    private void touch(Activity page,int action,float x,float y) {
        runOnMainSync(() -> {
            View view=page.findViewById(R.id.video_surface);
            long now=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now,action,x*view.getWidth(),y*view.getHeight(),0);
            view.dispatchTouchEvent(event);event.recycle();
        });
    }
    private void swipe(Activity page,float x1,float y1,float x2,float y2) {
        touch(page,android.view.MotionEvent.ACTION_DOWN,x1,y1);
        touch(page,android.view.MotionEvent.ACTION_MOVE,x2,y2);
        touch(page,android.view.MotionEvent.ACTION_UP,x2,y2);
    }
    private void gestureChecks(Activity page) throws Exception {
        android.media.AudioManager audio=(android.media.AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE);
        int originalVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        java.util.concurrent.atomic.AtomicReference<Float> originalBrightness=new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> originalBrightness.set(page.getWindow().getAttributes().screenBrightness));
        int systemBrightness=android.provider.Settings.System.getInt(getTargetContext().getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS,128);
        try {
            int start=mediaPosition(page);
            touch(page,android.view.MotionEvent.ACTION_DOWN,0.5f,0.5f);
            touch(page,android.view.MotionEvent.ACTION_MOVE,0.75f,0.5f);
            require(text(page,R.id.video_gesture_feedback).contains("快进"),"Horizontal gesture previews forward target");
            require(Math.abs(mediaPosition(page)-start)<1500,"Seek is not committed during drag");
            touch(page,android.view.MotionEvent.ACTION_CANCEL,0.75f,0.5f);
            require(Math.abs(mediaPosition(page)-start)<1500,"Cancelled horizontal gesture does not seek");
            swipe(page,0.5f,0.5f,0.75f,0.5f);waitText(page,"已暂停");
            int forward=mediaPosition(page);
            require(forward-start>20000,"Right swipe seeks forward");
            swipe(page,0.5f,0.5f,0.25f,0.5f);waitText(page,"已暂停");
            require(forward-mediaPosition(page)>20000,"Left swipe seeks backward");
            runOnMainSync(() -> {android.view.WindowManager.LayoutParams p=page.getWindow().getAttributes();p.screenBrightness=0.5f;page.getWindow().setAttributes(p);});
            swipe(page,0.2f,0.7f,0.2f,0.4f);
            java.util.concurrent.atomic.AtomicReference<Float> bright=new java.util.concurrent.atomic.AtomicReference<>();
            runOnMainSync(() -> bright.set(page.getWindow().getAttributes().screenBrightness));
            require(bright.get()>0.75f && text(page,R.id.video_gesture_feedback).contains("亮度"),"Left-side upward swipe increases window brightness");
            swipe(page,0.2f,0.4f,0.2f,0.7f);
            runOnMainSync(() -> bright.set(page.getWindow().getAttributes().screenBrightness));
            require(bright.get()<0.6f,"Left-side downward swipe decreases window brightness");
            require(android.provider.Settings.System.getInt(getTargetContext().getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS,128)==systemBrightness,"Brightness gesture does not change global setting");
            if(!audio.isVolumeFixed()) {
                int middle=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)/2;
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,middle,0);
                swipe(page,0.8f,0.7f,0.8f,0.4f);
                int louder=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                require(louder>middle && text(page,R.id.video_gesture_feedback).contains("音量"),"Right-side upward swipe increases media volume");
                swipe(page,0.8f,0.4f,0.8f,0.7f);
                require(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)<louder,"Right-side downward swipe decreases media volume");
            }
        } finally {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,originalVolume,0);
            runOnMainSync(() -> {android.view.WindowManager.LayoutParams p=page.getWindow().getAttributes();p.screenBrightness=originalBrightness.get();page.getWindow().setAttributes(p);});
        }
    }
    private void rangeChecks() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line; long start = 0, end = 0;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.startsWith("Range: bytes=")) { String[] pair = line.substring(13).split("-"); start = Long.parseLong(pair[0]); end = Long.parseLong(pair[1]); }
                        }
                        requests.incrementAndGet();
                        byte[] bytes = new byte[(int)(end-start+1)];
                        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)((start+i)%251);
                        String head="HTTP/1.1 206 Partial Content\r\nContent-Length: " + bytes.length + "\r\nContent-Range: bytes " + start + "-" + end + "/1200000\r\nConnection: close\r\n\r\n";
                        socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(bytes);
                    } catch (Exception ignored) { if (server.isClosed()) return; }
                }
            }); responder.setDaemon(true); responder.start();
            VideoDataSource data = new VideoDataSource(p -> (java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+server.getLocalPort()+"/"+p).openConnection(), "video", 1200000);
            byte[] bytes = new byte[2000];
            require(data.readAt(100, bytes, 0, 1000)==1000 && bytes[0]==(byte)100, "Range bytes correct");
            require(data.readAt(300, bytes, 0, 1000)==1000 && requests.get()==1, "Cache reuses chunk");
            require(data.readAt(1100000, bytes, 0, 1000)==1000 && bytes[0]==(byte)(1100000%251), "Seek fetches new chunk");
            require(data.readAt(1200000,bytes,0,1)==-1, "EOF correct");
            data.close();
            try { data.readAt(0,bytes,0,1); throw new AssertionError("Closed source readable"); } catch (java.io.IOException expected) { checks++; }
        }
    }
    @Override public void onStart() {
        waitForIdleSync();
        Context context = getTargetContext(); Bundle result = new Bundle();
        SharedPreferences prefs = context.getSharedPreferences("network_hosts", Context.MODE_PRIVATE);
        Map<String, ?> backup = prefs.getAll();
        ConnectionMonitor monitor = ((DashboardApplication)context.getApplicationContext()).monitor();
        Activity page = null; Activity library = null; int code = Activity.RESULT_OK;
        try {
            rangeChecks();
            String base = arguments.getString("base"); String id = arguments.getString("article");
            if (base != null && id != null) {
                runOnMainSync(() -> { new HostSettings(context).save(new HostConfig("video-smoke-test", base, base)); monitor.configurationChanged(); });
                if ("true".equals(arguments.getString("rhino"))) {
                    library=startActivitySync(new Intent(context,RhinoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    Activity listing=library;
                    long deadline=android.os.SystemClock.elapsedRealtime()+60000;
                    boolean ready=false;
                    while(android.os.SystemClock.elapsedRealtime()<deadline) {
                        java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();
                        runOnMainSync(() -> {
                            android.widget.ListView list=listing.findViewById(R.id.rhino_list);
                            if(list.getChildCount()>0) {
                                android.widget.ImageView image=list.getChildAt(0).findViewById(R.id.rhino_thumb);
                                loaded.set(image.getDrawable()!=null);
                            }
                        });
                        if(loaded.get()){ready=true;break;}
                        Thread.sleep(200);
                    }
                    require(ready,"Actual NAS thumbnail loaded in dedicated video list");
                    try(java.io.FileOutputStream out=context.openFileOutput("rhino-list.png",Context.MODE_PRIVATE)) {
                        getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                    }
                    ActivityMonitor playerMonitor=addMonitor(VideoActivity.class.getName(),null,false);
                    runOnMainSync(() -> {
                        android.widget.ListView list=listing.findViewById(R.id.rhino_list);
                        list.performItemClick(list.getChildAt(0),0,0);
                    });
                    page=waitForMonitorWithTimeout(playerMonitor,3000);
                    require(page!=null,"Actual video opens from thumbnail row");
                } else page = startActivitySync(new Intent(context, VideoActivity.class).putExtra("article_id",id).putExtra("article_title","视频播放验证").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Activity target = page;
                waitText(page,"正在播放"); checks++;
                long renderDeadline = android.os.SystemClock.elapsedRealtime() + 15000;
                boolean rendered = false;
                while (android.os.SystemClock.elapsedRealtime() < renderDeadline) {
                    java.lang.reflect.Field field = VideoActivity.class.getDeclaredField("videoRendered"); field.setAccessible(true);
                    java.util.concurrent.atomic.AtomicBoolean value = new java.util.concurrent.atomic.AtomicBoolean();
                    runOnMainSync(() -> { try { value.set(field.getBoolean(target)); } catch (Exception e) { throw new RuntimeException(e); } });
                    if (value.get()) { rendered = true; break; }
                    Thread.sleep(100);
                }
                require(rendered, "Actual video frame rendered");
                Thread.sleep(1500);
                runOnMainSync(() -> target.findViewById(R.id.video_play).performClick());
                waitText(page,"已暂停"); checks++;
                gestureChecks(page);
                selectSpeed(page,"1.5×"); checkSpeed(page,1.5f,false);
                runOnMainSync(() -> {
                    SeekBar seek=target.findViewById(R.id.video_seek);
                    require(seek.getMax()>60000,"Duration loaded");
                    seek.setProgress(60000);
                    try {
                        java.lang.reflect.Field field=VideoActivity.class.getDeclaredField("player"); field.setAccessible(true);
                        ((android.media.MediaPlayer)field.get(target)).seekTo(60000);
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                Thread.sleep(2000); waitText(page,"已暂停"); checks++;
                runOnMainSync(() -> target.findViewById(R.id.video_fullscreen).performClick());
                Thread.sleep(1200);
                require(context.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,"Fullscreen landscape");
                runOnMainSync(() -> target.findViewById(R.id.video_play).performClick());
                waitText(page,"正在播放"); checks++;
                checkSpeed(page,1.5f,true);
                selectSpeed(page,"2×"); checkSpeed(page,2f,true);
                Thread.sleep(1500);
                android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                if (root!=null) for (android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Got it")) node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                Thread.sleep(500);
                java.lang.reflect.Field playerField=VideoActivity.class.getDeclaredField("player"); playerField.setAccessible(true);
                java.util.concurrent.atomic.AtomicInteger position=new java.util.concurrent.atomic.AtomicInteger();
                runOnMainSync(() -> {
                    try { position.set(((android.media.MediaPlayer)playerField.get(target)).getCurrentPosition()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                // Legacy seekTo selects the preceding sync frame, not an exact timestamp.
                require(position.get()>=50000 && position.get()<80000,"Seek reached sync frame near one minute: "+position.get());
                try (java.io.FileOutputStream out=context.openFileOutput("video-player.png",Context.MODE_PRIVATE)) {
                    getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                }
                runOnMainSync(() -> target.findViewById(R.id.video_surface).performClick());
                java.util.concurrent.atomic.AtomicBoolean fullscreenVisible=new java.util.concurrent.atomic.AtomicBoolean();
                runOnMainSync(() -> fullscreenVisible.set(target.findViewById(R.id.video_fullscreen).isShown()
                    && target.findViewById(R.id.video_controls).getVisibility()==android.view.View.GONE));
                require(fullscreenVisible.get(),"Fullscreen exit remains visible while playback controls are hidden");
                runOnMainSync(() -> target.findViewById(R.id.video_fullscreen).performClick());
                long portraitDeadline=android.os.SystemClock.elapsedRealtime()+5000;
                while(context.getResources().getConfiguration().orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT
                    && android.os.SystemClock.elapsedRealtime()<portraitDeadline) Thread.sleep(100);
                require(context.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT,"Visible fullscreen exit restores portrait");
                waitText(page,"正在播放"); checkSpeed(page,2f,true);
                runOnMainSync(target::finish);
                Thread.sleep(500); checks++;
            }
            result.putString("stream", "PASS " + checks + " video checks\n");
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + failure + "\n" + android.util.Log.getStackTraceString(failure)); code = Activity.RESULT_CANCELED;
        } finally {
            Activity last = page; Activity lastLibrary=library;
            runOnMainSync(() -> {
                if (last!=null && !last.isFinishing()) last.finish();
                if(lastLibrary!=null && !lastLibrary.isFinishing())lastLibrary.finish();
                SharedPreferences.Editor edit=prefs.edit().clear();
                for(Map.Entry<String,?> item:backup.entrySet()) {
                    Object value=item.getValue();
                    if(value instanceof String)edit.putString(item.getKey(),(String)value);
                    else if(value instanceof Boolean)edit.putBoolean(item.getKey(),(Boolean)value);
                    else if(value instanceof Integer)edit.putInt(item.getKey(),(Integer)value);
                    else if(value instanceof Long)edit.putLong(item.getKey(),(Long)value);
                    else if(value instanceof Float)edit.putFloat(item.getKey(),(Float)value);
                }
                edit.commit(); monitor.configurationChanged();
            });
        }
        finish(code,result);
    }
}
