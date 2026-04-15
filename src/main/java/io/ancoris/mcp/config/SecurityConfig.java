package io.ancoris.mcp.config;

import io.ancoris.mcp.security.ApiKeyFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyFilter apiKeyFilter;

    public SecurityConfig(ApiKeyFilter apiKeyFilter) {
        this.apiKeyFilter = apiKeyFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC dispatches are internal DeferredResult completions (SSE finalisation).
                        // The SecurityContext from the original REQUEST thread is not propagated to
                        // the async dispatch thread under stateless session policy, so we permit all
                        // ASYNC dispatches here. The original REQUEST was already authenticated.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        // OAuth 2.0 endpoints — ApiKeyFilter skips these; auth is the OAuth flow itself
                        .requestMatchers("/.well-known/oauth-authorization-server",
                                "/.well-known/oauth-protected-resource",
                                "/oauth/authorize", "/oauth/token", "/oauth/register").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")  // SEC-015
                        .anyRequest().authenticated()
                )
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

}
