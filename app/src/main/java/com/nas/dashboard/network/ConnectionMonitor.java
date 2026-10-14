package com.nas.dashboard.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One process-wide, event-driven monitor. No polling and no probes on screen entry. */
public final class ConnectionMonitor {
    public interface Listener { void onState(State state); }
    public static final class State {
        public final String title;
        public final String detail;
        public final String baseUrl;
        public final String reason;
        public final boolean checking;
        public final boolean success;
        public final boolean reachable;
        State(String title, String detail, HostRouter.Selection route, boolean checking, boolean success, boolean reachable) {
            this.title = title;
            this.detail = detail;
            baseUrl = route == null ? "" : route.baseUrl;
            reason = route == null ? "尚未选择访问地址" : route.reason;
            this.checking = checking;
            this.success = success;
            this.reachable = reachable;
        }
    }

    private final HostRouter router;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile int generation;
    private volatile EndpointProbe probe;
    private Future<?> task;
    private volatile State state = idle();
    private volatile HostRouter.Selection selectedRoute;
    private volatile HostRouter.Selection usableRoute;

    public ConnectionMonitor(Context context) { router = new HostRouter(context, this::selected); }
    public void start() { router.start(); router.initializeRoute(); }
    public HostRouter.Selection selectedRoute() { return selectedRoute; }
    /** A failed or in-progress HTTP diagnostic never gates business requests. */
    public HostRouter.Selection routeForRequests() { return usableRoute; }
    public State current() { return state; }
    public void addListener(Listener listener) { listeners.add(listener); listener.onState(state); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public void check() { router.refresh(); }
    public void refreshRouting() { router.initializeRoute(); }

    public void configurationChanged() {
        router.invalidate();
        selectedRoute = null;
        usableRoute = null;
        cancelProbe();
        publish(idle());
        router.initializeRoute();
    }

    private static State idle() {
        return new State("尚未检测", "保存访问地址后即可使用，手动检测仅用于诊断连接。", null, false, false, false);
    }

    private void selected(HostRouter.Selection route, boolean diagnostic) {
        cancelProbe();
        selectedRoute = route;
        int request = generation;
        if (route.baseUrl.isEmpty()) {
            usableRoute = null;
            publish(new State("尚未配置", "请先保存 Wi-Fi、Host 和端口配置。", route, false, false, false));
            return;
        }
        if (route.checking) {
            usableRoute = null;
            publish(new State("正在选择访问地址…", "正在根据当前 Wi-Fi 自动选择内网或公网地址，无需手动检测。", route, true, false, false));
            return;
        }
        usableRoute = route;
        if (!diagnostic) {
            publish(new State("地址已选择", "访问地址已自动选择，可直接使用功能；需要时可手动诊断连接。", route, false, false, false));
            return;
        }
        publish(new State("正在检测连接…", "正在诊断 HTTP 连接，业务功能可正常访问。", route, true, false, false));
        EndpointProbe next = new EndpointProbe();
        probe = next;
        task = worker.submit(() -> {
            if (request != generation) return;
            EndpointProbe.Result result = next.check(() -> route.openConnection(""));
            main.post(() -> {
                if (request != generation) return;
                publish(new State(result.title, result.detail + "\n检测时间："
                    + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date()),
                    route, false, result.success, result.reachable));
            });
        });
    }

    private void cancelProbe() {
        generation++;
        if (probe != null) probe.cancel();
        if (task != null) task.cancel(true);
        probe = null;
        task = null;
    }

    private void publish(State next) {
        state = next;
        for (Listener listener : new ArrayList<>(listeners)) listener.onState(next);
    }
}
