package io.ancoris.mcp.security;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.model.ApiKey;
import io.ancoris.mcp.oauth.JwtTokenService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String issuer;
    private final ApiKeyService apiKeyService;
    private final JwtTokenService jwtTokenService;
    private final AuditService auditService;
    private final Counter authFailureCounter;

    public ApiKeyFilter(
            @Value("${mcp.oauth.issuer}") String issuer,
            ApiKeyService apiKeyService,
            JwtTokenService jwtTokenService,
            AuditService auditService,
            MeterRegistry meterRegistry) {
        this.issuer = issuer;
        this.apiKeyService = apiKeyService;
        this.jwtTokenService = jwtTokenService;
        this.auditService = auditService;
        // SEC-014: counter for all failed authentication attempts
        this.authFailureCounter = Counter.builder("mcp.auth.failures")
                .description("Count of failed API key authentication attempts")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Path 1: X-API-Key header or apiKey query param (direct key auth)
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            rawKey = request.getParameter("apiKey");
        }
        if (rawKey != null && !rawKey.isBlank()) {
            Optional<ApiKey> found = apiKeyService.authenticate(rawKey);
            if (found.isEmpty()) {
                recordFailure(request, "invalid_key");
                sendUnauthorized(response, "Invalid API key");
                return;
            }
            try {
                setAuthentication(found.get());
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }

        // Path 2: Authorization: Bearer <jwt> (OAuth 2.0 flow)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            Optional<JwtTokenService.JwtClaims> claims = jwtTokenService.validate(token);
            if (claims.isPresent()) {
                Optional<ApiKey> found = apiKeyService.authenticateByHash(claims.get().keyHash());
                if (found.isPresent()) {
                    try {
                        setAuthentication(found.get());
                        chain.doFilter(request, response);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                    return;
                }
            }
            recordFailure(request, "invalid_bearer");
            sendUnauthorized(response, "Invalid Bearer token");
            return;
        }

        // No credentials supplied — signal OAuth discovery via WWW-Authenticate
        recordFailure(request, "missing_credentials");
        sendUnauthorized(response, "Authentication required");
    }

    /** SEC-007 + SEC-014: write audit entry and increment counter for every auth failure. */
    private void recordFailure(HttpServletRequest request, String reason) {
        authFailureCounter.increment();
        auditService.log("authentication_failure", null,
                Map.of("reason", reason, "ip", request.getRemoteAddr()), "rejected");
    }

    private void setAuthentication(ApiKey key) {
        var authority = new SimpleGrantedAuthority("ROLE_" + key.getRole().name());
        var auth = new UsernamePasswordAuthenticationToken(key, null, List.of(authority));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Returns 401 with a WWW-Authenticate header pointing to OAuth protected-resource
     * metadata so MCP clients (e.g. Claude Code) can initiate the OAuth 2.0 PKCE flow.
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("WWW-Authenticate",
                "Bearer realm=\"mcp-data-gateway\","
                + " resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health")
                || uri.startsWith("/.well-known/")
                || uri.startsWith("/oauth/");
    }
}
