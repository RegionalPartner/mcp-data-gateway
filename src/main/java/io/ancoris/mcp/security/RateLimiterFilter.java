package io.ancoris.mcp.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * SEC-002: per-IP sliding-window rate limiter.
 * Runs at order DEFAULT_FILTER_ORDER-1 (just before Spring Security) so it applies
 * to all requests including unauthenticated probes.
 *
 * Limit: {@code mcp.security.rate-limit.max-requests} requests per 60-second window per IP
 * (default 60; override in {@code application-test.yaml} for tests to prevent cross-class
 * context pollution). Exceeding the limit returns HTTP 429.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)
public class RateLimiterFilter extends OncePerRequestFilter {

    /** Overridable via {@code mcp.security.rate-limit.max-requests}. */
    @Value("${mcp.security.rate-limit.max-requests:60}")
    int maxRequestsPerWindow;

    private static final long WINDOW_MILLIS = 60_000L;

    private final Cache<String, ArrayDeque<Long>> requestLog;
    private final Counter rateLimitCounter;

    public RateLimiterFilter(MeterRegistry meterRegistry) {
        // SEC-014: Caffeine cache auto-evicts inactive IPs, bounding memory usage
        this.requestLog = Caffeine.newBuilder()
                .expireAfterAccess(2, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
        this.rateLimitCounter = Counter.builder("mcp.rate.limit.exceeded")
                .description("Count of requests rejected by the per-IP rate limiter")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        ArrayDeque<Long> times = requestLog.get(ip, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MILLIS;

        synchronized (times) {
            // evict timestamps outside the current window
            while (!times.isEmpty() && times.peekFirst() < cutoff) {
                times.pollFirst();
            }
            if (times.size() >= maxRequestsPerWindow) {
                rateLimitCounter.increment();
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"Too many requests — retry after 60 seconds\"}");
                return;
            }
            times.addLast(now);
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Kubernetes liveness/readiness probes must never be throttled
        return request.getRequestURI().startsWith("/actuator/health");
    }

    /** Resets all per-IP counters. Called by test setup to prevent cross-class pollution. */
    public void clearRequestLog() {
        requestLog.invalidateAll();
    }
}
