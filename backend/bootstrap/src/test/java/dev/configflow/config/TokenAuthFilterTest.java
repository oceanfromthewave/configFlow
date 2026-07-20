package dev.configflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the token filter's transport rules: the session token belongs in a
 * header, and only the SSE stream may fall back to the {@code token} query parameter
 * (the browser {@code EventSource} API cannot set headers).
 */
class TokenAuthFilterTest {

    private static final String TOKEN = "s3cret-token";

    private final TokenAuthFilter filter = new TokenAuthFilter(TOKEN);
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void headerTokenIsAcceptedOnRegularEndpoint() throws Exception {
        MockHttpServletRequest request = get("/api/v1/health");
        request.addHeader(TokenAuthFilter.TOKEN_HEADER, TOKEN);

        filter.doFilter(request, response, chain);

        assertPassedThrough();
    }

    @Test
    void queryParamTokenIsRejectedOnRegularEndpoint() throws Exception {
        MockHttpServletRequest request = get("/api/v1/health");
        request.setParameter(TokenAuthFilter.TOKEN_QUERY_PARAM, TOKEN);

        filter.doFilter(request, response, chain);

        assertRejected();
    }

    @Test
    void queryParamTokenIsAcceptedOnSseStream() throws Exception {
        MockHttpServletRequest request = get(TokenAuthFilter.SSE_PATH);
        request.setParameter(TokenAuthFilter.TOKEN_QUERY_PARAM, TOKEN);

        filter.doFilter(request, response, chain);

        assertPassedThrough();
    }

    @Test
    void wrongQueryParamTokenIsRejectedOnSseStream() throws Exception {
        MockHttpServletRequest request = get(TokenAuthFilter.SSE_PATH);
        request.setParameter(TokenAuthFilter.TOKEN_QUERY_PARAM, "wrong");

        filter.doFilter(request, response, chain);

        assertRejected();
    }

    @Test
    void requestWithoutAnyTokenIsRejected() throws Exception {
        filter.doFilter(get("/api/v1/health"), response, chain);

        assertRejected();
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    /** The chain records the request it was given, so a null request means it never ran. */
    private void assertPassedThrough() {
        assertNotNull(chain.getRequest(), "the filter chain should have been invoked");
        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }

    private void assertRejected() throws Exception {
        assertNull(chain.getRequest(), "the filter chain must not be invoked");
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_TOKEN_INVALID"));
    }
}
