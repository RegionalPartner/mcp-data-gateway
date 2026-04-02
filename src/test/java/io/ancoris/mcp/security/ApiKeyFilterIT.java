package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApiKeyFilterIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    private static final String MCP_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;

    @Test
    void missingApiKeyHeader_returns401() throws Exception {
        mockMvc.perform(post("/mcp/message")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MCP_TOOLS_LIST))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidApiKey_returns401() throws Exception {
        mockMvc.perform(post("/mcp/message")
                       .header("X-API-Key", "not-a-real-key")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MCP_TOOLS_LIST))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void validReadOnlyKey_actuatorInfoRequiresAdminRole() throws Exception {
        // SEC-015: /actuator/** requires ADMIN — a valid READ_ONLY key is authenticated (not 401)
        // but rejected with 403 (not 200). This verifies both that the filter passes the key
        // and that the role restriction is enforced.
        mockMvc.perform(get("/actuator/info")
                       .header("X-API-Key", "demo-readonly-key-001"))
               .andExpect(status().isForbidden());
    }

    @Test
    void validAdminKey_actuatorInfoReturns200() throws Exception {
        // SEC-015: ADMIN keys can still access /actuator/**
        mockMvc.perform(get("/actuator/info")
                       .header("X-API-Key", "demo-admin-key-001"))
               .andExpect(status().isOk());
    }

    @Test
    void actuatorHealthEndpoint_bypassesFilter() throws Exception {
        mockMvc.perform(get("/actuator/health"))
               .andExpect(status().isOk());
    }
}
