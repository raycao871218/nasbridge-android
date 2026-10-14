package com.nas.dashboard.network;

import android.media.MediaDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/** Bounded HTTP range cache; retains the selected Wi-Fi binding just like other NAS APIs. */
public final class VideoDataSource extends MediaDataSource {
    private final NasBridgeClient.ConnectionFactory factory;
    private final String path;
    private final long length;
    private final byte[] cache = new byte[512 * 1024];
    private long start = -1;
    private int count;
    private volatile boolean closed;
    private volatile HttpURLConnection active;

    public VideoDataSource(NasBridgeClient.ConnectionFactory factory, String path, long length) {
        this.factory = factory; this.path = path; this.length = length;
    }
    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed) throw new IOException("播放已关闭");
        if (position < 0) throw new IOException("无效播放位置");
        if (size == 0) return 0;
        if (position >= length) return -1;
        if (start < 0 || position < start || position >= start + count) {
            long end = Math.min(length - 1, position + cache.length - 1);
            HttpURLConnection connection = factory.open(path);
            active = connection;
            try {
                if (closed) throw new IOException("播放已关闭");
                connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Range", "bytes=" + position + "-" + end);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code != 206 || !(("bytes " + position + "-" + end + "/" + length).equals(connection.getHeaderField("Content-Range")))) {
                    throw new IOException("视频接口未返回正确的分段数据（HTTP " + code + "）");
                }
                int wanted = (int) (end - position + 1);
                int loaded = 0;
                try (InputStream input = connection.getInputStream()) {
                    while (loaded < wanted) {
                        if (closed) throw new IOException("播放已关闭");
                        int n = input.read(cache, loaded, wanted - loaded);
                        if (n < 0) throw new IOException("视频数据不完整");
                        loaded += n;
                    }
                }
                start = position; count = loaded;
            } finally {
                connection.disconnect();
                if (active == connection) active = null;
            }
        }
        int n = Math.min(size, count - (int) (position - start));
        System.arraycopy(cache, (int) (position - start), buffer, offset, n);
        return n;
    }
    @Override public long getSize() { return length; }
    @Override public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
}
