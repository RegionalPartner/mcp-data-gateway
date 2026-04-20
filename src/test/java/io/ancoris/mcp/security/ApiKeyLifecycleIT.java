package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for API key lifecycle: expiry and revocation (SEC-001).
 *
 * Test keys are inserted with HMAC-SHA256 hashes and cleaned up in @AfterEach.
 * The ApiKeyService cache is invalidated before each test to ensure fresh key loading.
 */
class ApiKeyLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private HmacApiKeyHasher hasher;

    private static final String EXPIRED_KEY_RAW = "test-lifecycle-expired-key";
    private static final String REVOKED_KEY_RAW = "test-lifecycle-revoked-key";
    private static final String FUTURE_KEY_RAW = "test-lifecycle-future-key";

    private static final String MCP_PAYLOAD = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @BeforeEach
    void insertTestKeys() {
        jdbc.update(
                "INSERT INTO api_keys (key_hash, label, role, expires_at, revoked) VALUES (?, ?, ?, ?, ?)",
                hasher.hash(EXPIRED_KEY_RAW), "lifecycle-test-expired", "READ_ONLY",
                Timestamp.from(Instant.now().minusSeconds(3600)), false);

        jdbc.update(
                "INSERT INTO api_keys (key_hash, label, role, expires_at, revoked) VALUES (?, ?, ?, ?, ?)",
                hasher.hash(REVOKED_KEY_RAW), "lifecycle-test-revoked", "READ_ONLY",
                null, true);

        jdbc.update(
                "INSERT INTO api_keys (key_hash, label, role, expires_at, revoked) VALUES (?, ?, ?, ?, ?)",
                hasher.hash(FUTURE_KEY_RAW), "lifecycle-test-future", "READ_ONLY",
                Timestamp.from(Instant.now().plusSeconds(3600)), false);

        // Force cache reload so the newly inserted keys are visible to ApiKeyService
        apiKeyService.invalidateCache();
    }

    @AfterEach
    void removeTestKeys() {
        jdbc.update("DELETE FROM api_keys WHERE label LIKE 'lifecycle-test-%'");
        apiKeyService.invalidateCache();
    }

    // -----------------------------------------------------------------------
    // Expired key (expiresAt in the past) must be rejected with 401
    // -----------------------------------------------------------------------

    @Test
    void expiredKey_returns401() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", EXPIRED_KEY_RAW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Revoked key must be rejected with 401
    // -----------------------------------------------------------------------

    @Test
    void revokedKey_returns401() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", REVOKED_KEY_RAW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Key with expiry in the future must authenticate (not 401)
    // -----------------------------------------------------------------------

    @Test
    void validKeyWithFutureExpiry_isAuthenticated() throws Exception {
        // The MCP endpoint requires auth; a valid key should pass the auth filter.
        // The response may be any non-401 status (e.g. 200 or 400 from MCP protocol).
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", FUTURE_KEY_RAW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_PAYLOAD))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }
}
