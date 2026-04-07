package io.ancoris.mcp.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterFilterTest {

    private RateLimiterFilter filter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new RateLimiterFilter(meterRegistry);
    }

    // -----------------------------------------------------------------------
    // 60 requests from the same IP must all be allowed
    // -----------------------------------------------------------------------

    @Test
    void sixtyRequests_allAllowed() throws Exception {
        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest req = request("10.1.0.1", "/mcp/message");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
    }

    // -----------------------------------------------------------------------
    // 61st request from the same IP must be rejected with 429
    // -----------------------------------------------------------------------

    @Test
    void sixtyFirstRequest_returns429() throws Exception {
        for (int i = 0; i < 60; i++) {
            filter.doFilter(request("10.1.0.2", "/mcp/message"),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse last = new MockHttpServletResponse();
        filter.doFilter(request("10.1.0.2", "/mcp/message"), last, new MockFilterChain());
        assertThat(last.getStatus()).isEqualTo(429);
    }

    // -----------------------------------------------------------------------
    // Rate limit buckets are per-IP — exhausting IP A must not block IP B
    // -----------------------------------------------------------------------

    @Test
    void perIpIsolation_separateCounters() throws Exception {
        for (int i = 0; i < 60; i++) {
            filter.doFilter(request("10.1.0.3", "/mcp/message"),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse otherIp = new MockHttpServletResponse();
        filter.doFilter(request("10.1.0.4", "/mcp/message"), otherIp, new MockFilterChain());
        assertThat(otherIp.getStatus()).isNotEqualTo(429);
    }

    // -----------------------------------------------------------------------
    // /actuator/health must bypass the rate limiter entirely
    // -----------------------------------------------------------------------

    @Test
    void actuatorHealth_notFiltered() {
        assertThat(filter.shouldNotFilter(request("10.1.0.5", "/actuator/health"))).isTrue();
    }

    @Test
    void regularEndpoint_isFiltered() {
        assertThat(filter.shouldNotFilter(request("10.1.0.5", "/mcp/message"))).isFalse();
    }

    // -----------------------------------------------------------------------
    // Exceeding the limit must increment the mcp.rate.limit.exceeded counter
    // -----------------------------------------------------------------------

    @Test
    void rateLimitExceeded_incrementsCounter() throws Exception {
        for (int i = 0; i <= 60; i++) {
            filter.doFilter(request("10.1.0.6", "/mcp/message"),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        assertThat(meterRegistry.counter("mcp.rate.limit.exceeded").count()).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // 429 response body must contain the error JSON
    // -----------------------------------------------------------------------

    @Test
    void rateLimitResponse_containsErrorBody() throws Exception {
        for (int i = 0; i < 60; i++) {
            filter.doFilter(request("10.1.0.7", "/mcp/message"),
                    new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request("10.1.0.7", "/mcp/message"), res, new MockFilterChain());
        assertThat(res.getContentAsString()).contains("Too many requests");
        assertThat(res.getContentType()).contains("application/json");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static MockHttpServletRequest request(String ip, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        req.setRequestURI(uri);
        return req;
    }
}
