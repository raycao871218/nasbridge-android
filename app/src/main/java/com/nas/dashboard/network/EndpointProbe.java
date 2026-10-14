package com.nas.dashboard.network;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Check the base URL with HEAD, retrying once with GET when HEAD is unsupported. */
public final class EndpointProbe {
    public interface ConnectionFactory { HttpURLConnection open() throws IOException; }

    public static final class Result {
        public final String title;
        public final String detail;
        public final boolean success;
        public final boolean reachable;

        private Result(String title, String detail, boolean success, boolean reachable) {
            this.title = title;
            this.detail = detail;
            this.success = success;
            this.reachable = reachable;
        }
    }

    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;
    private final int timeoutMillis;

    public EndpointProbe() { this(3000); }
    public EndpointProbe(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }

    public void cancel() {
        cancelled = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    private int request(ConnectionFactory factory, String method) throws IOException {
        if (cancelled) throw new IOException("检测已取消");
        connection = factory.open();
        if (cancelled) throw new IOException("检测已取消");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        // Only inspect response headers; do not read or buffer the response body.
        return connection.getResponseCode();
    }

    public Result check(ConnectionFactory factory) {
        long start = System.nanoTime();
        try {
            int status = request(factory, "HEAD");
            boolean usedGet = status == 405 || status == 501;
            if (usedGet) {
                connection.disconnect();
                connection = null;
                status = request(factory, "GET");
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            String detail = (usedGet ? "HEAD 不受支持，已改用 GET · " : "HEAD · ")
                + "HTTP " + status + " · " + elapsed + " ms";
            if (status >= 200 && status < 300) {
                return new Result("连接成功", detail + "，接口地址正常响应", true, true);
            }
            if (status >= 300 && status < 400) {
                return new Result("服务可达 · 地址有跳转", detail + "，请确认是否需要登录或调整地址", false, true);
            }
            if (status == 401 || status == 403) {
                return new Result("服务可达 · 需要认证", detail + "，请确认账号或访问权限", false, true);
            }
            if (status == 404) {
                return new Result("服务可达 · 路径不存在", detail + "，请检查接口地址及路径", false, true);
            }
            if (status == 405 || status == 501) {
                return new Result("服务可达 · 不支持 GET 检测", detail + "，请确认接口支持的请求方式", false, true);
            }
            if (status >= 400 && status <= 599) {
                return new Result("服务可达 · 返回错误", detail + "，请检查服务状态", false, true);
            }
            return new Result("连接失败", "没有收到有效的 HTTP 响应", false, false);
        } catch (SocketTimeoutException exception) {
            return new Result("连接失败", "连接或响应超时，请检查网络、访问端口及服务状态", false, false);
        } catch (UnknownHostException exception) {
            return new Result("连接失败", "域名解析失败，请检查 Host 和网络", false, false);
        } catch (ConnectException exception) {
            return new Result("连接失败", "无法连接访问端口，请检查端口、服务是否启动及防火墙", false, false);
        } catch (SSLException exception) {
            return new Result("连接失败", "HTTPS 握手或证书验证失败，请检查协议和服务器证书", false, false);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return new Result("连接失败", "未能完成 HTTP 检测，请检查协议、地址和网络", false, false);
        } finally {
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
            connection = null;
        }
    }
}
