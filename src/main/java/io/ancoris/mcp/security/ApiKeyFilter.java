package io.ancoris.mcp.security;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.model.ApiKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final ApiKeyService apiKeyService;
    private final AuditService auditService;
    private final Counter authFailureCounter;

    public ApiKeyFilter(ApiKeyService apiKeyService, AuditService auditService,
                        MeterRegistry meterRegistry) {
        this.apiKeyService = apiKeyService;
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
        // Accept key from header (primary) or query parameter (fallback for clients
        // that cannot reliably forward custom headers on every request, e.g. Claude Code).
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            rawKey = request.getParameter("apiKey");
        }
        if (rawKey == null || rawKey.isBlank()) {
            recordFailure(request, "missing_key");
            sendUnauthorized(response, "Missing X-API-Key header");
            return;
        }

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

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }
}
