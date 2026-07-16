package dev.configflow.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Session-token authentication for every {@code /api/**} request.
 *
 * <p>The token is accepted from the {@code X-ConfigFlow-Token} header or, because the
 * browser {@code EventSource} API cannot set headers, from the {@code token} query
 * parameter. Requests without a matching token get a 401 Problem Details response.</p>
 */
public final class TokenAuthFilter extends OncePerRequestFilter {

    /** Header carrying the session token. */
    public static final String TOKEN_HEADER = "X-ConfigFlow-Token";
    /** Query-parameter fallback for SSE (EventSource cannot set headers). */
    public static final String TOKEN_QUERY_PARAM = "token";

    private final byte[] expectedToken;

    public TokenAuthFilter(String expectedToken) {
        Objects.requireNonNull(expectedToken, "expectedToken must not be null");
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(TOKEN_HEADER);
        if (presented == null) {
            presented = request.getParameter(TOKEN_QUERY_PARAM);
        }
        if (presented != null && matches(presented)) {
            filterChain.doFilter(request, response);
            return;
        }
        reject(response);
    }

    private boolean matches(String presented) {
        // Constant-time comparison to avoid token timing oracles.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedToken);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"urn:configflow:error:unauthorized",\
                "title":"Missing or invalid session token",\
                "status":401,\
                "code":"AUTH_TOKEN_INVALID"}""");
    }
}
