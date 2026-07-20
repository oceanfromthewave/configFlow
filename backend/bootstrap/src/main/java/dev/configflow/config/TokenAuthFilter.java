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
 * <p>The token must be presented in the {@code X-ConfigFlow-Token} header. The only
 * exception is the SSE stream ({@value #SSE_PATH}): the browser {@code EventSource} API cannot set headers, so there the {@code token} query parameter is
 * accepted as well. Requests without a matching token get a 401 Problem Details response.</p>
 */

public final class TokenAuthFilter extends OncePerRequestFilter
{

	/** Header carrying the session token. */
	public static final String TOKEN_HEADER = "X-ConfigFlow-Token";
	/** Query-parameter fallback for SSE (EventSource cannot set headers). */
	public static final String TOKEN_QUERY_PARAM = "token";

	/** The SSE stream: the only endpoint that may authenticate via query parameter. */
	public static final String SSE_PATH = "/api/v1/events";

	private final byte[] expectedToken;

	public TokenAuthFilter(String expectedToken)
	{
		Objects.requireNonNull(expectedToken, "expectedToken must not be null");
		this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException
	{
		String presented = request.getHeader(TOKEN_HEADER);
		if(presented == null && isSseRequest(request))
		{
			presented = request.getParameter(TOKEN_QUERY_PARAM);
		}
		if(presented != null && matches(presented))
		{
			filterChain.doFilter(request, response);
			return;
		}
		reject(response);
	}

	/**
	 * True only for the SSE stream. {@code EventSource} cannot send headers, so that one endpoint may fall back to the query parameter; anywhere else a token
	 * in the URL would leak through access logs, referrer headers and browser history.
	 */
	private static boolean isSseRequest(HttpServletRequest request)
	{
		String path = request.getRequestURI();
		String contextPath = request.getContextPath();
		if(contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath))
		{
			path = path.substring(contextPath.length());
		}
		return SSE_PATH.equals(path);
	}

	private boolean matches(String presented)
	{
		// Constant-time comparison to avoid token timing oracles.
		return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedToken);
	}

	private static void reject(HttpServletResponse response) throws IOException
	{
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
