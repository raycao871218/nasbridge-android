import com.nas.dashboard.network.EndpointProbe;
import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;

public final class EndpointProbeTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            String method = exchange.getRequestMethod();
            if (!method.equals("HEAD") && !method.equals("GET")) throw new AssertionError("Unexpected method");
            int code = Integer.parseInt(exchange.getRequestURI().getPath().substring(5));
            if (code == 302) exchange.getResponseHeaders().set("Location", "/api/200");
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        java.util.concurrent.atomic.AtomicInteger headCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger getCount = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/fallback/", exchange -> {
            String[] codes = exchange.getRequestURI().getPath().substring(10).split("/");
            boolean head = exchange.getRequestMethod().equals("HEAD");
            if (head) headCount.incrementAndGet(); else getCount.incrementAndGet();
            exchange.sendResponseHeaders(Integer.parseInt(codes[head ? 0 : 1]), -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (int code : new int[]{200, 204, 302, 401, 403, 404, 405, 500, 501}) {
                EndpointProbe.Result result = new EndpointProbe().check(() ->
                    (HttpURLConnection) new URL(base + "/api/" + code).openConnection());
                check(result.reachable, "HTTP " + code + " should be reachable");
                check(result.success == (code < 300), "Do not mislabel HTTP " + code + " as success");
                check(result.detail.contains("HTTP " + code), "Display actual HTTP status");
            }
            for (int[] pair : new int[][]{{405, 200}, {501, 204}, {405, 401}, {501, 404}, {405, 405}, {501, 501}, {401, 200}}) {
                headCount.set(0);
                getCount.set(0);
                EndpointProbe.Result fallback = new EndpointProbe().check(() -> (HttpURLConnection)
                    new URL(base + "/fallback/" + pair[0] + "/" + pair[1]).openConnection());
                boolean retry = pair[0] == 405 || pair[0] == 501;
                int finalStatus = retry ? pair[1] : pair[0];
                check(headCount.get() == 1, "Exactly one HEAD attempt");
                check(getCount.get() == (retry ? 1 : 0), "Retry only unsupported HEAD, at most once");
                check(fallback.success == (finalStatus < 300), "Use GET result for success");
                check(fallback.reachable, "HTTP error still means service reachable");
                check(fallback.detail.contains("HTTP " + finalStatus), "Show final response code");
                check(!retry || fallback.detail.contains("已改用 GET"), "Explain fallback method");
            }
            EndpointProbe betweenAttempts = new EndpointProbe();
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            headCount.set(0);
            getCount.set(0);
            EndpointProbe.Result interrupted = betweenAttempts.check(() -> {
                if (attempts.incrementAndGet() == 2) betweenAttempts.cancel();
                return (HttpURLConnection) new URL(base + "/fallback/405/200").openConnection();
            });
            check(!interrupted.reachable && getCount.get() == 0, "Cancellation prevents GET request");
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
            EndpointProbe.Result refused = new EndpointProbe().check(() ->
                (HttpURLConnection) new URL("http://127.0.0.1:" + closedPort).openConnection());
            check(!refused.reachable && refused.detail.contains("端口"), "Closed port should explain failure");
            EndpointProbe.Result timeout = new EndpointProbe(100).check(() ->
                (HttpURLConnection) new URL(base + "/slow").openConnection());
            check(!timeout.reachable && timeout.detail.contains("超时"), "Slow response should time out");
            EndpointProbe cancelled = new EndpointProbe();
            cancelled.cancel();
            EndpointProbe.Result result = cancelled.check(() -> { throw new AssertionError("Cancelled probe opened connection"); });
            check(!result.reachable, "Cancelled probes must not connect");
            System.out.println("PASS: " + checks + " HTTP probe checks");
        } finally {
            server.stop(0);
        }
    }
}
