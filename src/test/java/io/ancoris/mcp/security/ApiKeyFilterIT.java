package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.oauth.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyFilterIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    ApiKeyService apiKeyService;

    private static final String MCP_TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;

    @Test
    void missingApiKeyHeader_returns401() throws Exception {
        mockMvc.perform(post("/mcp")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MCP_TOOLS_LIST))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidApiKey_returns401() throws Exception {
        mockMvc.perform(post("/mcp")
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

    // -------------------------------------------------------------------------
    // OAuth 2.0 Bearer JWT auth
    // -------------------------------------------------------------------------

    @Test
    void missingCredentials_401HasWwwAuthenticateHeader() throws Exception {
        mockMvc.perform(post("/mcp")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MCP_TOOLS_LIST))
               .andExpect(status().isUnauthorized())
               .andExpect(header().string("WWW-Authenticate",
                       containsString("resource_metadata=")));
    }

    @Test
    void validBearerJwt_adminKey_actuatorInfoReturns200() throws Exception {
        // The demo-admin-key-001 hash is seeded via Flyway V4 in test DB.
        // Look up the actual hash via ApiKeyService, then issue a JWT for it.
        var found = apiKeyService.authenticate("demo-admin-key-001");
        org.assertj.core.api.Assertions.assertThat(found).isPresent();

        String jwt = jwtTokenService.issue(found.get().getKeyHash(), AccessRole.ADMIN);

        mockMvc.perform(get("/actuator/info")
                       .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isOk());
    }

    @Test
    void invalidBearerJwt_returns401() throws Exception {
        mockMvc.perform(post("/mcp")
                       .header("Authorization", "Bearer this.is.not.valid")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(MCP_TOOLS_LIST))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void oauthMetadataEndpoint_noAuthRequired() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
               .andExpect(status().isOk());
    }
}
