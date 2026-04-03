package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the per-IP sliding-window rate limiter (SEC-002).
 * Each test method uses a dedicated IP address to avoid cross-test interference
 * (the RateLimiterFilter bean is shared across the Spring context).
 */
@AutoConfigureMockMvc
class RateLimiterFilterIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String MCP_PAYLOAD = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;

    // -----------------------------------------------------------------------
    // 61st request from the same IP must be rejected with HTTP 429
    // -----------------------------------------------------------------------

    @Test
    void sixtyFirstRequest_returns429() throws Exception {
        String ip = "10.99.1.1";

        // Requests 1–60: let through by rate limiter (auth filter may reject with 401 — that is fine)
        for (int i = 0; i < 60; i++) {
            sendMcpPost(ip).andExpect(status().is(org.hamcrest.Matchers.not(429)));
        }

        // 61st request: rate limiter rejects before even reaching the auth filter
        sendMcpPost(ip).andExpect(status().isTooManyRequests());
    }

    // -----------------------------------------------------------------------
    // /actuator/health must never be throttled regardless of request volume
    // -----------------------------------------------------------------------

    @Test
    void actuatorHealth_immuneToRateLimiting() throws Exception {
        String ip = "10.99.1.2";

        for (int i = 0; i < 70; i++) {
            mockMvc.perform(get("/actuator/health")
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            }))
                    .andExpect(status().isOk());
        }
    }

    // -----------------------------------------------------------------------
    // Exhausting the quota for one IP must not affect a different IP
    // -----------------------------------------------------------------------

    @Test
    void rateLimitIsPerIp_differentIpUnaffected() throws Exception {
        String ipA = "10.99.1.3";
        String ipB = "10.99.1.4";

        // Exhaust quota for IP A
        for (int i = 0; i <= 60; i++) {
            sendMcpPost(ipA);
        }

        // IP B must still be allowed through (auth filter returns 401, NOT 429)
        ResultActions result = sendMcpPost(ipB);
        result.andExpect(status().isUnauthorized()); // 401 from auth, not 429 from rate limiter
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private ResultActions sendMcpPost(String remoteAddr) throws Exception {
        return mockMvc.perform(post("/mcp/message")
                .with(req -> {
                    req.setRemoteAddr(remoteAddr);
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(MCP_PAYLOAD));
    }
}
