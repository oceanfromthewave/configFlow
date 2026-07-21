package dev.configflow.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Registers the API filter chain.
 *
 * <p>Full Spring Security is intentionally not used: the backend is a localhost-only
 * companion process and its entire auth model is a single shared session token.</p>
 */
@Configuration
public class SecurityConfig
{

	/** Vite dev-server origins the browser UI is served from during development. */
	private static final List<String> DEV_ORIGINS = List.of("http://localhost:5173", "http://127.0.0.1:5173");

	/**
	 * CORS must run before {@link TokenAuthFilter}: a browser preflight (OPTIONS) carries no custom header, so the token filter would reject it before the real
	 * request is ever sent. This filter answers preflights itself and stops the chain there.
	 */
	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilter()
	{
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(DEV_ORIGINS);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of(TokenAuthFilter.TOKEN_HEADER, "Content-Type"));
		cors.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);

		FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	@Bean
	public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilter(@Value("${configflow.token:dev-token}") String token)
	{
		FilterRegistrationBean<TokenAuthFilter> registration = new FilterRegistrationBean<>(new TokenAuthFilter(token));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
		return registration;
	}
}
