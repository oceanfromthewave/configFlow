package dev.configflow.application.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.settings.ProxySettings;
import dev.configflow.domain.settings.SettingsStore;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The service installs a JVM-wide {@link ProxySelector}, so every test restores the
 * selector it found — leaking one would silently reroute the rest of the suite.
 */
class ProxyServiceTest {

    private static final class FakeSettingsStore implements SettingsStore {
        private final Map<String, String> data = new HashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(String key, String value) {
            data.put(key, value);
        }

        @Override
        public void remove(String key) {
            data.remove(key);
        }

        @Override
        public Map<String, String> findAll() {
            return Map.copyOf(data);
        }
    }

    private final ProxySelector original = ProxySelector.getDefault();
    private final FakeSettingsStore store = new FakeSettingsStore();
    private final ProxyService service = new ProxyService(store);

    @AfterEach
    void restoreSelector() {
        ProxySelector.setDefault(original);
    }

    @Test
    void noProxyIsStoredByDefault() {
        assertTrue(service.current().isEmpty());
    }

    @Test
    void updateStoresBothKeysAndReadsBack() {
        service.update("http://proxy.corp:3128", "localhost");

        assertEquals(
                Map.of(ProxyService.URL_KEY, "http://proxy.corp:3128", ProxyService.BYPASS_KEY, "localhost"),
                store.findAll());
        ProxySettings current = service.current().orElseThrow();
        assertEquals("proxy.corp", current.host());
        assertEquals(List.of("localhost"), current.bypass());
    }

    @Test
    void updateInstallsASelectorThatRoutesThroughTheProxy() {
        service.update("http://proxy.corp:3128", "localhost");

        List<Proxy> selected = ProxySelector.getDefault().select(URI.create("https://github.com/x.git"));

        assertEquals(1, selected.size());
        assertEquals(Proxy.Type.HTTP, selected.get(0).type());
        InetSocketAddress address = (InetSocketAddress) selected.get(0).address();
        assertEquals("proxy.corp", address.getHostString());
        assertEquals(3128, address.getPort());
    }

    @Test
    void aBypassedHostGoesDirect() {
        service.update("http://proxy.corp:3128", "*.corp.com");

        assertEquals(
                List.of(Proxy.NO_PROXY),
                ProxySelector.getDefault().select(URI.create("https://git.corp.com/x.git")));
    }

    @Test
    void aSocksUrlSelectsASocksProxy() {
        service.update("socks://proxy.corp:1080", "");

        List<Proxy> selected = ProxySelector.getDefault().select(URI.create("https://github.com/x.git"));

        assertEquals(Proxy.Type.SOCKS, selected.get(0).type());
    }

    @Test
    void clearRemovesTheSettingsAndStopsRoutingThroughTheProxy() {
        service.update("http://proxy.corp:3128", "localhost");
        ProxySelector installed = ProxySelector.getDefault();

        service.clear();

        assertTrue(store.findAll().isEmpty());
        assertTrue(service.current().isEmpty());
        assertTrue(ProxySelector.getDefault() != installed, "the proxy selector must be uninstalled");
    }

    @Test
    void applyStoredInstallsWhatWasSavedByAnEarlierRun() {
        store.put(ProxyService.URL_KEY, "http://proxy.corp:3128");
        store.put(ProxyService.BYPASS_KEY, "localhost");

        service.applyStored();

        assertEquals(
                Proxy.Type.HTTP,
                ProxySelector.getDefault().select(URI.create("https://github.com/x.git")).get(0).type());
    }

    @Test
    void aMalformedUrlIsRejectedBeforeAnythingIsStored() {
        assertThrows(IllegalArgumentException.class, () -> service.update("http://proxy.corp", ""));

        assertTrue(store.findAll().isEmpty());
    }
}
