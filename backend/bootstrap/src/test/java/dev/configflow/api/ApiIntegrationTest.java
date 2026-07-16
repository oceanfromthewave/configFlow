package dev.configflow.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end API test over a real HTTP port and a real temp-file SQLite database:
 * proves token auth, the health contract, Problem Details mapping and the full
 * settings layer stack (API → application → domain port → SQLite).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "configflow.token=test-token")
class ApiIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dbPath(DynamicPropertyRegistry registry) {
        registry.add("configflow.db.path", () -> tempDir.resolve("api-test.db").toString());
    }

    @Autowired
    private TestRestTemplate rest;

    private static HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ConfigFlow-Token", "test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void healthWithoutTokenIs401ProblemDetails() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/health", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("AUTH_TOKEN_INVALID"));
    }

    @Test
    void healthWithWrongTokenIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-ConfigFlow-Token", "wrong");
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthWithHeaderTokenIsUp() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/health", HttpMethod.GET, new HttpEntity<>(authHeaders()), Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("0.1.0", response.getBody().get("version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthWithQueryParamTokenIsAccepted() {
        // EventSource cannot set headers, so ?token= must work as well.
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/health?token=test-token", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void settingsRoundTripThroughAllLayers() {
        HttpEntity<Map<String, String>> put = new HttpEntity<>(Map.of("value", "dark"), authHeaders());
        ResponseEntity<Map> putResponse = rest.exchange(
                "/api/v1/settings/theme", HttpMethod.PUT, put, Map.class);
        assertEquals(HttpStatus.OK, putResponse.getStatusCode());
        assertEquals("dark", putResponse.getBody().get("value"));

        ResponseEntity<Map> getResponse = rest.exchange(
                "/api/v1/settings/theme", HttpMethod.GET, new HttpEntity<>(authHeaders()), Map.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals("dark", getResponse.getBody().get("value"));

        ResponseEntity<Map> allResponse = rest.exchange(
                "/api/v1/settings", HttpMethod.GET, new HttpEntity<>(authHeaders()), Map.class);
        assertEquals(HttpStatus.OK, allResponse.getStatusCode());
        assertEquals("dark", allResponse.getBody().get("theme"));
    }

    @Test
    void unknownSettingIs404ProblemDetailsWithCode() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/settings/does-not-exist", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"NOT_FOUND\""));
        assertTrue(response.getBody().contains("urn:configflow:error:not-found"));
    }
}
