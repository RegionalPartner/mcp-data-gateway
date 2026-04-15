package io.ancoris.mcp.oauth;

import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import io.ancoris.mcp.security.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthControllerTest {

    @Mock
    ApiKeyService apiKeyService;

    private AuthCodeStore authCodeStore;
    private JwtTokenService jwtTokenService;
    private OAuthController controller;

    private static final String ISSUER = "https://test.example.com";
    private static final String REDIRECT_URI = "https://client.example.com/cb";

    @BeforeEach
    void setUp() {
        authCodeStore = new AuthCodeStore();
        jwtTokenService = new JwtTokenService("test-jwt-secret-32-chars-minimum-00");
        controller = new OAuthController(ISSUER, apiKeyService, authCodeStore, jwtTokenService);
    }

    // -------------------------------------------------------------------------
    // RFC 8414 / RFC 9728 metadata
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void authServerMetadata_containsRequiredFields() {
        Map<String, Object> meta = controller.authServerMetadata();

        assertThat(meta.get("issuer")).isEqualTo(ISSUER);
        assertThat(meta.get("authorization_endpoint")).isEqualTo(ISSUER + "/oauth/authorize");
        assertThat(meta.get("token_endpoint")).isEqualTo(ISSUER + "/oauth/token");
        assertThat((List<String>) meta.get("code_challenge_methods_supported")).contains("S256");
        assertThat((List<String>) meta.get("grant_types_supported")).contains("authorization_code");
    }

    @Test
    @SuppressWarnings("unchecked")
    void protectedResourceMetadata_pointsToAuthorizationServer() {
        Map<String, Object> meta = controller.protectedResourceMetadata();

        assertThat(meta.get("resource")).isEqualTo(ISSUER);
        assertThat((List<String>) meta.get("authorization_servers")).contains(ISSUER);
    }

    // -------------------------------------------------------------------------
    // Authorize GET
    // -------------------------------------------------------------------------

    @Test
    void authorizeForm_validParams_returnsHtmlWithClientId() {
        var response = controller.authorizeForm(
                "code", "claude-code", REDIRECT_URI, "challenge", "S256", "state123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).contains("claude-code");
        assertThat(response.getBody()).contains("name=\"api_key\"");
    }

    @Test
    void authorizeForm_unsupportedMethod_returns400() {
        var response = controller.authorizeForm(
                "code", "client", REDIRECT_URI, "challenge", "plain", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authorizeForm_wrongResponseType_returns400() {
        var response = controller.authorizeForm(
                "token", "client", REDIRECT_URI, "challenge", "S256", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authorizeForm_missingRedirectUri_returns400WithSessionExpiredPage() {
        var response = controller.authorizeForm(
                "code", "client", null, "challenge", "S256", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).contains("Authorization session expired");
        assertThat(response.getBody()).contains("Return to your AI client");
    }

    @Test
    void authorizeForm_missingClientId_returns400WithSessionExpiredPage() {
        var response = controller.authorizeForm(
                "code", null, REDIRECT_URI, "challenge", "S256", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).contains("Authorization session expired");
    }

    @Test
    void authorizeForm_truncatedClientId_recoversFormFromCache() {
        // First request: full URL — caches the params
        controller.authorizeForm("code", "abcd1234-ef56-7890-abcd-ef1234567890",
                REDIRECT_URI, "challenge", "S256", "mystate");

        // Second request: truncated client_id (Firefox behaviour)
        var response = controller.authorizeForm(
                "code", "abcd1234-ef56-", null, null, null, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).contains("name=\"api_key\"");
        assertThat(response.getBody()).contains(REDIRECT_URI);
    }

    @Test
    void authorizeForm_truncatedClientId_noCache_returnsExpiredPage() {
        // Truncated request with no prior cached entry
        var response = controller.authorizeForm(
                "code", "abcd1234-ef56-", null, null, null, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Authorization session expired");
    }

    @Test
    void authorizeForm_xssInClientId_escapedInOutput() {
        var response = controller.authorizeForm(
                "code", "<script>alert(1)</script>", REDIRECT_URI, "challenge", "S256", "");

        assertThat(response.getBody()).contains("&lt;script&gt;");
        assertThat(response.getBody()).doesNotContain("<script>");
    }

    // -------------------------------------------------------------------------
    // Authorize POST
    // -------------------------------------------------------------------------

    @Test
    void authorizeSubmit_validKey_redirectsWithCodeAndState() {
        ApiKey key = buildAdminKey("hash1");
        when(apiKeyService.authenticate("real-key")).thenReturn(Optional.of(key));

        var response = controller.authorizeSubmit(
                "real-key", REDIRECT_URI, "challenge", "S256", "mystate");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = response.getHeaders().getFirst("Location");
        assertThat(location).startsWith(REDIRECT_URI + "?code=").contains("state=mystate");
    }

    @Test
    void authorizeSubmit_invalidKey_returns401() {
        when(apiKeyService.authenticate("wrong")).thenReturn(Optional.empty());

        var response = controller.authorizeSubmit(
                "wrong", REDIRECT_URI, "challenge", "S256", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authorizeSubmit_emptyState_redirectWithoutStateParam() {
        ApiKey key = buildAdminKey("hash2");
        when(apiKeyService.authenticate("key")).thenReturn(Optional.of(key));

        var response = controller.authorizeSubmit("key", REDIRECT_URI, "challenge", "S256", "");

        String location = response.getHeaders().getFirst("Location");
        assertThat(location).isNotNull().doesNotContain("state=");
    }

    // -------------------------------------------------------------------------
    // Token endpoint
    // -------------------------------------------------------------------------

    @Test
    void token_validPkce_returnsAccessToken() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String challenge = computeS256(verifier);
        ApiKey key = buildAdminKey("myhash");
        when(apiKeyService.authenticate("key")).thenReturn(Optional.of(key));

        // Seed a real code via the authorize submit path
        controller.authorizeSubmit("key", REDIRECT_URI, challenge, "S256", "");
        // Retrieve the code from the store directly to avoid parsing the redirect header
        String code = authCodeStore.issue("myhash", AccessRole.ADMIN, REDIRECT_URI, challenge, "S256");

        var response = controller.token(
                "authorization_code", code, REDIRECT_URI, verifier, "client", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("access_token");
        assertThat(response.getBody().get("token_type")).isEqualTo("Bearer");
        assertThat(response.getBody().get("expires_in")).isEqualTo(3600);
    }

    @Test
    void token_wrongVerifier_returns400WithInvalidGrant() throws Exception {
        String challenge = computeS256("correct-verifier");
        String code = authCodeStore.issue("hash", AccessRole.READ_ONLY, REDIRECT_URI, challenge, "S256");

        var response = controller.token(
                "authorization_code", code, REDIRECT_URI, "wrong-verifier", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_grant");
    }

    @Test
    void token_unknownCode_returns400WithInvalidGrant() {
        var response = controller.token(
                "authorization_code", "nonexistent", REDIRECT_URI, "verifier", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_grant");
    }

    @Test
    void token_redirectUriMismatch_returns400WithInvalidGrant() throws Exception {
        String challenge = computeS256("v");
        String code = authCodeStore.issue("h", AccessRole.READ_ONLY, REDIRECT_URI, challenge, "S256");

        var response = controller.token(
                "authorization_code", code, "https://other.example.com/cb", "v", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_grant");
    }

    @Test
    void token_unsupportedGrantType_returns400() {
        var response = controller.token(
                "client_credentials", "code", REDIRECT_URI, "verifier", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("unsupported_grant_type");
    }

    @Test
    void token_codeConsumedOnce_secondAttemptFails() throws Exception {
        String verifier = "verifierstring1234567890abcdefghij";
        String challenge = computeS256(verifier);
        String code = authCodeStore.issue("h", AccessRole.ADMIN, REDIRECT_URI, challenge, "S256");

        controller.token("authorization_code", code, REDIRECT_URI, verifier, null, null);
        var second = controller.token("authorization_code", code, REDIRECT_URI, verifier, null, null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody().get("error")).isEqualTo("invalid_grant");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ApiKey buildAdminKey(String keyHash) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(key, "keyHash", keyHash);
        ReflectionTestUtils.setField(key, "label", "test-admin");
        ReflectionTestUtils.setField(key, "role", AccessRole.ADMIN);
        ReflectionTestUtils.setField(key, "revoked", false);
        return key;
    }

    private static String computeS256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
