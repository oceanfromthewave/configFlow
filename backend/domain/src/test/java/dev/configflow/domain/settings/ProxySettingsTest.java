package dev.configflow.domain.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProxySettingsTest {

    @Test
    void parsesHostPortAndBypassList() {
        ProxySettings proxy = ProxySettings.parse("http://proxy.corp:3128", "localhost, *.corp.com ");

        assertEquals("http", proxy.scheme());
        assertEquals("proxy.corp", proxy.host());
        assertEquals(3128, proxy.port());
        assertEquals(List.of("localhost", "*.corp.com"), proxy.bypass());
    }

    @Test
    void urlRoundTripsThroughParse() {
        ProxySettings proxy = ProxySettings.parse("http://proxy.corp:3128", "");

        assertEquals(proxy, ProxySettings.parse(proxy.url(), ""));
    }

    @Test
    void rejectsAnAddressWithNoPort() {
        // Guessing 80 turns a typo into a connection timeout minutes later.
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("http://proxy.corp", ""));
    }

    @Test
    void rejectsAnUnsupportedScheme() {
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("ftp://proxy.corp:21", ""));
    }

    @Test
    void rejectsAMalformedUrl() {
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.parse("http://proxy corp:3128", ""));
    }

    @Test
    void bypassMatchesAnExactHostCaseInsensitively() {
        ProxySettings proxy = ProxySettings.parse("http://proxy.corp:3128", "Localhost");

        assertTrue(proxy.bypasses("localhost"));
        assertFalse(proxy.bypasses("github.com"));
    }

    @Test
    void aWildcardMatchesTheDomainAndItsSubdomains() {
        ProxySettings proxy = ProxySettings.parse("http://proxy.corp:3128", "*.corp.com");

        assertTrue(proxy.bypasses("corp.com"));
        assertTrue(proxy.bypasses("git.corp.com"));
        // Suffix matching alone would let this through — it must not.
        assertFalse(proxy.bypasses("evilcorp.com"));
    }

    @Test
    void noBypassListMeansEverythingGoesThroughTheProxy() {
        ProxySettings proxy = ProxySettings.parse("http://proxy.corp:3128", null);

        assertEquals(List.of(), proxy.bypass());
        assertFalse(proxy.bypasses("localhost"));
    }
}
