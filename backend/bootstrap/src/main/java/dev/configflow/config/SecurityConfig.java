package dev.configflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the {@link TokenAuthFilter} for all API paths.
 *
 * <p>Full Spring Security is intentionally not used: the backend is a localhost-only
 * companion process and its entire auth model is a single shared session token.</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilter(
            @Value("${configflow.token:dev-token}") String token) {
        FilterRegistrationBean<TokenAuthFilter> registration =
                new FilterRegistrationBean<>(new TokenAuthFilter(token));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
