import com.nas.dashboard.network.HostConfig;
import com.nas.dashboard.network.HostPolicy;

public final class HostPolicyTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void invalid(String ssid, String local, String remote) {
        checks++;
        try {
            new HostConfig(ssid, local, remote);
            throw new AssertionError("Invalid settings accepted: " + local);
        } catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        HostConfig config = new HostConfig("Home", " http://192.168.1.2:5000/api ", "https://nas.example.com");
        equal("http://192.168.1.2:5000/api/", config.localBaseUrl);
        equal("https://nas.example.com/", config.publicBaseUrl);
        equal("192.168.1.2", config.pingHost());
        equal(config.localBaseUrl, HostPolicy.select(config, true, "Home", true));
        equal(config.localBaseUrl, HostPolicy.select(config, true, "\"Home\"", true));
        equal(config.publicBaseUrl, HostPolicy.select(config, true, "Home", false));
        equal(config.publicBaseUrl, HostPolicy.select(config, true, "Cafe", true));
        equal(config.publicBaseUrl, HostPolicy.select(config, false, "Home", true));
        equal(config.publicBaseUrl, HostPolicy.select(config, true, "<unknown ssid>", true));
        equal(config.publicBaseUrl, HostPolicy.select(config, true, "home", true));
        equal(config.publicBaseUrl, HostPolicy.select(config, true, null, true));
        equal("2001:db8::1", new HostConfig("家里的 Wi-Fi", "http://[2001:db8::1]:8080/", "https://example.com/").pingHost());
        invalid("", "http://192.168.1.2", "https://example.com");
        invalid("Home", "192.168.1.2:5000", "https://example.com");
        invalid("Home", "file:///etc/passwd", "https://example.com");
        invalid("Home", "http://admin:secret@nas.local", "https://example.com");
        invalid("Home", "http://nas.local?key=secret", "https://example.com");
        invalid("Home", "http://nas.local#fragment", "https://example.com");
        invalid("Home", "http://nas.local:65536", "https://example.com");
        invalid("Home", "http://nas.local:0", "https://example.com");
        invalid("Home", "http://nas.local", "ftp://example.com");
        HostConfig ports = new HostConfig("Home", "http://nas.local/api/", "5000",
            "https://example.com/prefix%2Fpart/", "8443");
        equal("http://nas.local:5000/api/", ports.localBaseUrl);
        equal("https://example.com:8443/prefix%2Fpart/", ports.publicBaseUrl);
        equal("nas.local", ports.pingHost());
        equal("http://nas.local/api/", HostConfig.hostWithoutPort(ports.localBaseUrl));
        equal("5000", HostConfig.portOf(ports.localBaseUrl));
        equal("", HostConfig.portOf("https://example.com/"));
        equal("http://nas.local:5000/", new HostConfig("Home", "http://nas.local:80", "5000", "https://example.com", "").localBaseUrl);
        equal("http://nas.local:1234/", new HostConfig("Home", "http://nas.local:1234", "", "https://example.com", "").localBaseUrl);
        equal("http://[2001:db8::1]:65535/", new HostConfig("Home", "http://[2001:db8::1]/", "65535", "https://example.com", "1").localBaseUrl);
        for (String port : new String[]{"0", "65536", "-1", "abc", "1.2", "999999999999"}) {
            checks++;
            try {
                new HostConfig("Home", "http://nas.local/", port, "https://example.com/", "");
                throw new AssertionError("Invalid port accepted: " + port);
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS: " + checks + " routing and validation checks");
    }
}
