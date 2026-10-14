package com.nas.dashboard;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.nas.dashboard.network.HostRouter;
import com.nas.dashboard.network.ConnectionMonitor;
import com.nas.dashboard.network.NasBridgeClient;
import com.nas.dashboard.network.VideoDataSource;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Native streaming player. Leaving the page releases playback and never marks an article read. */
public final class VideoActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private VideoDataSource source;
    private NasBridgeClient client;
    private SurfaceView surface;
    private TextView status, time;
    private Button play, speed;
    private PopupMenu speedMenu;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final String[] SPEED_LABELS = {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"};
    private float playbackSpeed = 1f;
    private SeekBar seek;
    private boolean started, hasSurface, prepared, dragging, videoRendered, resumePlaying = true;
    private int generation, savedPosition;
    private boolean loading, waitingForRoute;
    private ConnectionMonitor monitor;
    private VideoGestures gestures;
    private final ConnectionMonitor.Listener routeListener=state -> {
        if(started && hasSurface && waitingForRoute && monitor.routeForRequests()!=null) begin();
    };
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (!started || !prepared || player == null) return;
            if (!dragging) seek.setProgress(player.getCurrentPosition());
            time.setText(clock(player.getCurrentPosition()) + " / " + clock(player.getDuration()));
            handler.postDelayed(this, 500);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_video);
        monitor=((DashboardApplication)getApplication()).monitor();
        if (state != null) { savedPosition = state.getInt("position"); resumePlaying = state.getBoolean("playing", true); playbackSpeed = state.getFloat("speed", 1f); }
        surface = findViewById(R.id.video_surface); surface.getHolder().addCallback(this);
        status = findViewById(R.id.video_status); time = findViewById(R.id.video_time);
        play = findViewById(R.id.video_play); seek = findViewById(R.id.video_seek);
        speed = findViewById(R.id.video_speed); speedLabel();
        speed.setOnClickListener(v -> {
            if (!prepared) return;
            PopupMenu menu = new PopupMenu(this, speed); speedMenu = menu;
            for (int i = 0; i < SPEEDS.length; i++) menu.getMenu().add(0, i, i, SPEED_LABELS[i]).setCheckable(true).setChecked(SPEEDS[i] == playbackSpeed);
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.setOnMenuItemClickListener(item -> {
                if (prepared && player != null) changeSpeed(SPEEDS[item.getItemId()], player.isPlaying());
                return true;
            });
            menu.show();
        });
        ((TextView) findViewById(R.id.video_title)).setText(getIntent().getStringExtra("article_title"));
        ((Button)findViewById(R.id.video_back)).setText(getIntent().hasExtra("video_id") ? "返回视频列表" : "返回文章");
        findViewById(R.id.video_back).setOnClickListener(v -> finish());
        play.setOnClickListener(v -> {
            if (!prepared) { resumePlaying = true; begin(); return; }
            if (player.isPlaying()) { player.pause(); resumePlaying = false; status.setText("已暂停"); }
            else { player.start(); resumePlaying = true; status.setText("正在播放"); }
            playingUi();
        });
        findViewById(R.id.video_fullscreen).setOnClickListener(v -> setRequestedOrientation(
            getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onProgressChanged(SeekBar bar, int value, boolean user) { }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (prepared) { status.setText("正在跳转…"); player.seekTo(bar.getProgress()); }
            }
        });
        findViewById(R.id.video_frame).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fit());
        surface.setOnClickListener(v -> {
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                View controls=findViewById(R.id.video_controls);
                controls.setVisibility(controls.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        gestures=new VideoGestures(this,findViewById(R.id.video_gesture_feedback),new VideoGestures.Playback(){
            public boolean ready(){return prepared && player!=null;}
            public int position(){return player.getCurrentPosition();}
            public int duration(){return player.getDuration();}
            public void seek(int milliseconds){
                if(!ready())return;
                status.setText("正在跳转…");
                if(android.os.Build.VERSION.SDK_INT>=26)player.seekTo(milliseconds,MediaPlayer.SEEK_CLOSEST);
                else player.seekTo(milliseconds);
            }
        });
        surface.setOnTouchListener(gestures);
        findViewById(R.id.video_frame).setOnTouchListener(gestures);
        findViewById(R.id.video_frame).setOnClickListener(v -> surface.performClick());
        fullscreenUi();
    }
    private void playingUi() {
        boolean playing = prepared && player != null && player.isPlaying();
        play.setText(playing ? "暂停" : "播放");
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void begin() {
        if (!started || !hasSurface || player != null || loading) return;
        HostRouter.Selection route = monitor.routeForRequests();
        if (route == null) { waitingForRoute=monitor.current().checking; status.setText(monitor.current().checking ? "正在自动选择访问地址，选好后自动播放…" : "请先在网络配置中保存 Host 和端口"); return; }
        waitingForRoute=false; loading=true;
        int request = ++generation;
        status.setText("正在加载视频…"); play.setEnabled(false);
        NasBridgeClient api = new NasBridgeClient(); client = api;
        worker.execute(() -> {
            try {
                String id = getIntent().getStringExtra("article_id");
                String directId = getIntent().getStringExtra("video_id");
                String endpoint;
                if (directId != null && directId.matches("[A-Za-z0-9_-]{1,64}")) endpoint = "api/videos/" + directId + "/info";
                else if (id != null && !id.isEmpty()) endpoint = "api/daily-tasks/video?id=" + java.net.URLEncoder.encode(id, "UTF-8");
                else throw new java.io.IOException("缺少视频标识");
                JSONObject info = api.getJson(route::openConnection, endpoint);
                String videoId = info.getString("video_id");
                long size = info.getLong("size");
                if (!videoId.matches("[A-Za-z0-9_-]{1,64}") || size <= 0) throw new java.io.IOException("视频信息无效");
                VideoDataSource data = new VideoDataSource(route::openConnection, "api/videos/" + videoId, size);
                runOnUiThread(() -> {
                    if (request != generation || !started || !hasSurface) { data.close(); return; }
                    try {
                        loading=false; source = data; player = new MediaPlayer();
                        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                        player.setDisplay(surface.getHolder()); player.setDataSource(data);
                        player.setOnPreparedListener(p -> {
                            prepared = true; seek.setMax(p.getDuration()); seek.setEnabled(true); play.setEnabled(true); fit();
                            if (savedPosition > 0) p.seekTo(savedPosition);
                            changeSpeed(playbackSpeed, resumePlaying);
                            if (!prepared) return;
                            speed.setEnabled(true);
                            status.setText(resumePlaying ? "正在播放" : "已暂停"); playingUi();
                            handler.removeCallbacks(progress); handler.post(progress);
                        });
                        player.setOnSeekCompleteListener(p -> status.setText(p.isPlaying() ? "正在播放" : "已暂停"));
                        player.setOnCompletionListener(p -> { resumePlaying = false; savedPosition = 0; status.setText("播放完成"); playingUi(); });
                        player.setOnInfoListener((p, what, extra) -> {
                            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoRendered = true;
                            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status.setText("正在缓冲…");
                            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) status.setText(p.isPlaying() ? "正在播放" : "已暂停");
                            return false;
                        });
                        player.setOnErrorListener((p, what, extra) -> { fail("播放失败，请检查网络；若持续失败，可能是手机不支持此视频编码（" + what + "/" + extra + "）"); return true; });
                        player.setOnVideoSizeChangedListener((p,w,h) -> fit());
                        player.prepareAsync();
                    } catch (Exception error) { fail("无法打开视频，请重试"); }
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (request == generation && started) fail("视频加载失败：" + error.getMessage()); });
            }
        });
    }
    private void speedLabel() {
        String label = "1×";
        for (int i=0; i<SPEEDS.length; i++) if (SPEEDS[i] == playbackSpeed) label = SPEED_LABELS[i];
        speed.setText(label);
        speed.setContentDescription("播放速度 " + label + "，点击选择倍速");
    }
    private void changeSpeed(float value, boolean shouldPlay) {
        float previous = playbackSpeed;
        try {
            player.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(value));
            if (shouldPlay) player.start(); else player.pause();
            playbackSpeed = value;
        } catch (IllegalArgumentException | IllegalStateException unsupported) {
            try {
                player.setPlaybackParams(new PlaybackParams().allowDefaults().setPitch(1f).setSpeed(previous));
                if (shouldPlay) player.start(); else player.pause();
            } catch (IllegalArgumentException | IllegalStateException restoreFailed) {
                fail("倍速调整失败，请重试播放"); return;
            }
            Toast.makeText(this, "当前设备不支持该倍速", Toast.LENGTH_SHORT).show();
        }
        speedLabel(); playingUi();
    }
    private void fit() {
        if (player == null || !prepared) return;
        View frame = findViewById(R.id.video_frame);
        int w = player.getVideoWidth(), h = player.getVideoHeight();
        if (w <= 0 || h <= 0 || frame.getWidth() <= 0 || frame.getHeight() <= 0) return;
        double scale = Math.min((double) frame.getWidth()/w, (double) frame.getHeight()/h);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams((int)(w*scale), (int)(h*scale), android.view.Gravity.CENTER);
        if (surface.getWidth() != params.width || surface.getHeight() != params.height) surface.setLayoutParams(params);
    }
    private void fail(String message) { release(false); status.setText(message); play.setText("重试"); play.setEnabled(true); }
    private void release(boolean save) {
        if(gestures!=null)gestures.cancel();
        generation++; loading=false; waitingForRoute=false; handler.removeCallbacks(progress);
        if (speedMenu != null) { speedMenu.dismiss(); speedMenu = null; }
        if (client != null) { client.cancel(); client = null; }
        if (save && prepared && player != null) { savedPosition = player.getCurrentPosition(); resumePlaying = player.isPlaying(); }
        if (source != null) { source.close(); source = null; }
        if (player != null) { player.release(); player = null; }
        prepared = false; videoRendered = false; seek.setEnabled(false); speed.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private static String clock(int ms) { int seconds = ms / 1000; return String.format(Locale.ROOT, "%02d:%02d", seconds/60, seconds%60); }
    @Override protected void onStart() { super.onStart(); started = true; monitor.addListener(routeListener); begin(); }
    @Override protected void onStop() { started = false; monitor.removeListener(routeListener); release(true); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putFloat("speed", playbackSpeed);
        state.putInt("position", prepared && player != null ? player.getCurrentPosition() : savedPosition);
        state.putBoolean("playing", prepared && player != null ? player.isPlaying() : resumePlaying); super.onSaveInstanceState(state);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { hasSurface = true; begin(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { hasSurface = false; release(true); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { fit(); }
    private void fullscreenUi() {
        boolean full = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        getWindow().getDecorView().setSystemUiVisibility(full ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
        findViewById(R.id.video_header).setVisibility(full ? View.GONE : View.VISIBLE);
        findViewById(R.id.video_controls).setVisibility(View.VISIBLE);
        View root=findViewById(R.id.video_root);
        int padding=full ? 0 : Math.round(12*getResources().getDisplayMetrics().density);
        root.setPadding(padding,padding,padding,padding);
        FrameLayout.LayoutParams frame=(FrameLayout.LayoutParams)findViewById(R.id.video_frame).getLayoutParams();
        frame.topMargin=full ? 0 : Math.round(112*getResources().getDisplayMetrics().density);
        frame.bottomMargin=full ? 0 : Math.round(148*getResources().getDisplayMetrics().density);
        findViewById(R.id.video_frame).setLayoutParams(frame);
        Button fullscreen=findViewById(R.id.video_fullscreen);
        fullscreen.setText(full ? R.string.video_exit_fullscreen : R.string.video_fullscreen);
        fullscreen.setCompoundDrawablesRelativeWithIntrinsicBounds(full ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen,0,0,0);
    }
    @Override public void onBackPressed() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); fullscreenUi(); fit(); }
}
