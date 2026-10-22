package com.nas.dashboard.network;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class OrderedHostsTest {
    @Test public void defaultsInputProtocolsAndPreservesExplicitOverrides() {
        HostConfig implicit = new HostConfig("Home", "10.10.0.66:18080\n[fd00::66]:8080", "", "nas.example.test:18099", "");
        assertEquals(Arrays.asList("http://10.10.0.66:18080/", "http://[fd00::66]:8080/"), implicit.localBaseUrls);
        assertEquals("https://nas.example.test:18099/", implicit.publicBaseUrl);
        HostConfig explicit = new HostConfig("Home", "https://nas.local/path", "8443", "http://public.test", "8080");
        assertEquals("https://nas.local:8443/path/", explicit.localBaseUrl);
        assertEquals("http://public.test:8080/", explicit.publicBaseUrl);
        for (String invalid : Arrays.asList("", "ftp://nas.test", "user:secret@nas.test", "nas.test?token=x")) {
            try { new HostConfig("Home", "nas.local", "", invalid, ""); fail(invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }
    private final HostConfig config = new HostConfig("Home",
        "http://10.10.0.66:18080\nhttp://192.168.1.10:8080\nhttp://[fd00::66]:18080",
        "https://nas.example.test:18081");

    @Test public void preservesPriorityPortsAndStorageRoundTrip() {
        HostConfig restored = new HostConfig(config.ssid, config.localAddressesText(), config.publicBaseUrl);
        assertEquals(config.localBaseUrls, restored.localBaseUrls);
        assertEquals("http://10.10.0.66:18080/", restored.localBaseUrl);
        assertEquals("fd00::66", HostConfig.pingHost(restored.localBaseUrls.get(2)));
        HostConfig sharedPort = new HostConfig("Home", config.localAddressesText(), "1234", config.publicBaseUrl, "");
        for (String address : sharedPort.localBaseUrls) assertEquals("1234", HostConfig.portOf(address));
    }

    @Test public void stopsAtFirstAvailableAddress() {
        for (int available = 0; available < config.localBaseUrls.size(); available++) {
            List<String> tried = new ArrayList<>();
            String target = config.localBaseUrls.get(available);
            assertEquals(target, HostPolicy.selectOrdered(config, address -> {
                tried.add(address); return target.equals(address);
            }, () -> false));
            assertEquals(config.localBaseUrls.subList(0, available + 1), tried);
        }
    }

    @Test public void selectsPublicOnlyAfterEveryLocalFails() {
        List<String> tried = new ArrayList<>();
        assertEquals(config.publicBaseUrl, HostPolicy.selectOrdered(config, address -> {
            tried.add(address); return false;
        }, () -> false));
        assertEquals(config.localBaseUrls, tried);
    }

    @Test public void cancelledNetworkChangeNeverFallsBackOrTriesNextAddress() {
        List<String> tried = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        assertNull(HostPolicy.selectOrdered(config, address -> {
            tried.add(address); cancelled.set(true); return true;
        }, cancelled::get));
        assertEquals(Arrays.asList(config.localBaseUrl), tried);
        assertNull(HostPolicy.selectOrdered(config, address -> {
            fail("Cancelled selection probed an address"); return false;
        }, () -> true));
    }

    @Test public void rejectsEmptyInvalidAndOversizedListsAndDeduplicates() {
        for (String input : Arrays.asList("\n ; ", "http://nas/\ninvalid", "http://nas:0/",
                String.join("\n", java.util.stream.IntStream.range(0, 9)
                    .mapToObj(i -> "http://nas" + i + "/").toArray(String[]::new)))) {
            try { new HostConfig("Home", input, config.publicBaseUrl); fail(input); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals(Arrays.asList("http://nas/", "http://backup/"),
            new HostConfig("Home", "http://nas,http://nas/；http://backup", config.publicBaseUrl).localBaseUrls);
    }
}
