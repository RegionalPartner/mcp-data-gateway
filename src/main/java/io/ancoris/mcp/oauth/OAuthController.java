package io.ancoris.mcp.oauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.ancoris.mcp.model.ApiKey;
import io.ancoris.mcp.security.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OAuth 2.0 Authorization Server endpoints for the MCP Data Gateway.
 *
 * <p>Implements RFC 8414 (Authorization Server Metadata), RFC 9728 (Protected Resource Metadata),
 * and RFC 7636 (PKCE) to satisfy the MCP spec 2025-11-25 requirement for OAuth 2.0 on HTTP
 * MCP servers. No external OAuth provider is needed — the existing API key IS the identity.
 *
 * <p>Flow: Claude Code discovers {@code /.well-known/oauth-authorization-server}, opens a browser
 * to {@code /oauth/authorize} where the user enters their API key, then exchanges the one-time
 * code for a Bearer JWT at {@code /oauth/token}.
 */
@Controller
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    /**
     * Short-lived cache of pending authorize params keyed by full client_id.
     *
     * Firefox (Linux) consistently sends a second GET /oauth/authorize request
     * 1-2 seconds after the form loads, but with the URL truncated mid client_id
     * (e.g. "d76f7121-1be0-" instead of the full UUID). This cache lets the
     * handler recover the original params and re-render the form so the user
     * can still enter their API key.
     */
    private final Cache<String, AuthorizeParams> pendingForms = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(50)
            .build();


    private record AuthorizeParams(String responseType, String clientId, String redirectUri,
                                    String codeChallenge, String codeChallengeMethod, String state) { }

    private final String issuer;
    private final ApiKeyService apiKeyService;
    private final AuthCodeStore authCodeStore;
    private final JwtTokenService jwtTokenService;

    public OAuthController(
            @Value("${mcp.oauth.issuer}") String issuer,
            ApiKeyService apiKeyService,
            AuthCodeStore authCodeStore,
            JwtTokenService jwtTokenService) {
        this.issuer = issuer;
        this.apiKeyService = apiKeyService;
        this.authCodeStore = authCodeStore;
        this.jwtTokenService = jwtTokenService;
    }

    /** RFC 8414: OAuth 2.0 Authorization Server Metadata — discovered by MCP clients. */
    @GetMapping("/.well-known/oauth-authorization-server")
    @ResponseBody
    public Map<String, Object> authServerMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("issuer", issuer);
        meta.put("authorization_endpoint", issuer + "/oauth/authorize");
        meta.put("token_endpoint", issuer + "/oauth/token");
        meta.put("registration_endpoint", issuer + "/oauth/register");
        meta.put("response_types_supported", List.of("code"));
        meta.put("grant_types_supported", List.of("authorization_code"));
        meta.put("code_challenge_methods_supported", List.of("S256"));
        meta.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_post"));
        return meta;
    }

    /**
     * RFC 7591: Dynamic Client Registration — required by the MCP TypeScript SDK.
     *
     * <p>Returns a {@code client_secret} so that confidential-client MCP hosts (e.g. Mistral
     * Le Chat) can store their OAuth session credentials and later perform a server-to-server
     * token exchange using {@code client_secret_post}. Public clients (e.g. Claude Code) that
     * use PKCE may ignore the secret.
     */
    @PostMapping(path = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request) {
        String clientId = UUID.randomUUID().toString();
        String clientSecret = jwtTokenService.deriveClientSecret(clientId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", Instant.now().getEpochSecond());
        response.put("client_secret", clientSecret);
        response.put("client_secret_expires_at", 0);
        response.put("redirect_uris", request.getOrDefault("redirect_uris", List.of()));
        response.put("grant_types", List.of("authorization_code"));
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "client_secret_post");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** RFC 9728: Protected Resource Metadata — referenced by WWW-Authenticate on 401 responses. */
    @GetMapping("/.well-known/oauth-protected-resource")
    @ResponseBody
    public Map<String, Object> protectedResourceMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resource", issuer);
        meta.put("authorization_servers", List.of(issuer));
        return meta;
    }

    /** Shows the API key entry form. Claude Code opens this in the system browser. */
    @GetMapping("/oauth/authorize")
    public ResponseEntity<String> authorizeForm(
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "state", required = false, defaultValue = "") String state) {

        // Happy path: all required params present
        if (present(clientId) && present(redirectUri) && present(codeChallenge) && present(codeChallengeMethod)) {
            if (!"code".equals(responseType) || !"S256".equals(codeChallengeMethod)) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("unsupported_response_type or code_challenge_method");
            }
            // Cache params so a subsequent truncated-URL request can recover the form
            pendingForms.put(clientId, new AuthorizeParams(
                    responseType, clientId, redirectUri, codeChallenge, codeChallengeMethod, state));
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                    .body(buildAuthorizeForm(clientId, redirectUri, codeChallenge, codeChallengeMethod, state));
        }

        // Recovery path: Firefox sends a truncated URL (e.g. client_id cut mid-UUID).
        // Find any cached entry whose full client_id starts with the partial value received.
        if (present(clientId)) {
            AuthorizeParams cached = pendingForms.asMap().values().stream()
                    .filter(p -> p.clientId().startsWith(clientId))
                    .findFirst()
                    .orElse(null);
            if (cached != null) {
                log.debug("OAuth form recovered for truncated client_id prefix '{}'",
                        clientId.replaceAll("[\r\n\t]", "_"));
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                        .body(buildAuthorizeForm(cached.clientId(), cached.redirectUri(),
                                cached.codeChallenge(), cached.codeChallengeMethod(), cached.state()));
            }
        }

        // No params, no cache match — session genuinely expired or direct navigation
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_HTML)
                .body(buildSessionExpiredPage());
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    /** Validates the API key, issues a one-time code, and redirects back to the client. */
    @PostMapping("/oauth/authorize")
    public ResponseEntity<Void> authorizeSubmit(
            @RequestParam("api_key") String rawKey,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam("code_challenge_method") String codeChallengeMethod,
            @RequestParam(value = "state", required = false, defaultValue = "") String state) {
        Optional<ApiKey> keyOpt = apiKeyService.authenticate(rawKey);
        if (keyOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ApiKey key = keyOpt.get();
        String code = authCodeStore.issue(
                key.getKeyHash(), key.getRole(), redirectUri, codeChallenge, codeChallengeMethod);
        String location = redirectUri + "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&iss=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
                + (state.isBlank() ? "" : "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    /**
     * Validates client credentials and issues a Bearer JWT.
     *
     * <p>Accepts two authentication modes:
     * <ul>
     *   <li><b>PKCE</b> ({@code code_verifier}) — used by public clients such as Claude Code.</li>
     *   <li><b>client_secret_post</b> ({@code client_id} + {@code client_secret}) — used by
     *       confidential-client MCP hosts such as Mistral Le Chat whose backend performs the
     *       token exchange server-to-server.</li>
     * </ul>
     * At least one mode must pass; both may be present simultaneously.
     */
    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("code") String code,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret) {
        if (!"authorization_code".equals(grantType)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unsupported_grant_type"));
        }
        Optional<AuthCodeStore.AuthCodeEntry> entryOpt = authCodeStore.consume(code);
        if (entryOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
        }
        AuthCodeStore.AuthCodeEntry entry = entryOpt.get();
        if (!entry.redirectUri().equals(redirectUri)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
        }
        boolean pkceOk = codeVerifier != null && !codeVerifier.isBlank()
                && verifyPkce(codeVerifier, entry.codeChallenge());
        boolean secretOk = clientId != null && clientSecret != null
                && clientSecret.equals(jwtTokenService.deriveClientSecret(clientId));
        if (!pkceOk && !secretOk) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
        }
        String jwt = jwtTokenService.issue(entry.keyHash(), entry.role());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", jwt);
        response.put("token_type", "Bearer");
        response.put("expires_in", 3600);
        return ResponseEntity.ok(response);
    }

    private boolean verifyPkce(String verifier, String challenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return computed.equals(challenge);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** All user-supplied values are HTML-escaped before insertion. */
    private String buildAuthorizeForm(String clientId, String redirectUri,
                                       String codeChallenge, String codeChallengeMethod,
                                       String state) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>MCP Data Gateway — Authenticate</title>
                  <style>
                    *{box-sizing:border-box;margin:0;padding:0}
                    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
                         background:#f0f2f5;display:flex;align-items:center;
                         justify-content:center;min-height:100vh}
                    .card{background:#fff;border-radius:10px;padding:2rem 2.5rem;
                          box-shadow:0 2px 12px rgba(0,0,0,.1);max-width:380px;width:90%%}
                    h1{font-size:1.15rem;color:#111;margin-bottom:.4rem}
                    p{font-size:.875rem;color:#555;margin-bottom:1.5rem;line-height:1.5}
                    label{display:block;font-size:.8rem;font-weight:600;
                          color:#333;margin-bottom:.35rem}
                    input[type=password]{width:100%%;padding:.55rem .75rem;border:1px solid #ccc;
                                         border-radius:6px;font-size:.875rem;margin-bottom:1.25rem}
                    input[type=password]:focus{outline:none;border-color:#1a56db;
                                               box-shadow:0 0 0 3px rgba(26,86,219,.15)}
                    button{width:100%%;padding:.6rem;background:#1a56db;color:#fff;border:none;
                           border-radius:6px;font-size:.9rem;cursor:pointer;font-weight:500}
                    button:hover{background:#1648c4}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>MCP Data Gateway</h1>
                    <p>Enter your API key to allow <strong>%s</strong> to access MCP tools.</p>
                    <form method="post" action="/oauth/authorize">
                      <input type="hidden" name="redirect_uri" value="%s">
                      <input type="hidden" name="code_challenge" value="%s">
                      <input type="hidden" name="code_challenge_method" value="%s">
                      <input type="hidden" name="state" value="%s">
                      <label for="api_key">API Key</label>
                      <input type="password" id="api_key" name="api_key"
                             placeholder="Enter your API key" autocomplete="current-password">
                      <button type="submit">Authorise</button>
                    </form>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(clientId),
                escapeHtml(redirectUri),
                escapeHtml(codeChallenge),
                escapeHtml(codeChallengeMethod),
                escapeHtml(state));
    }

    private String buildSessionExpiredPage() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>MCP Data Gateway — Session Expired</title>
                  <style>
                    *{box-sizing:border-box;margin:0;padding:0}
                    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
                         background:#f0f2f5;display:flex;align-items:center;
                         justify-content:center;min-height:100vh}
                    .card{background:#fff;border-radius:10px;padding:2rem 2.5rem;
                          box-shadow:0 2px 12px rgba(0,0,0,.1);max-width:420px;width:90%%}
                    h1{font-size:1.15rem;color:#c0392b;margin-bottom:.75rem}
                    p{font-size:.875rem;color:#555;line-height:1.6;margin-bottom:.75rem}
                    code{background:#f4f4f4;padding:.1rem .35rem;border-radius:4px;
                         font-size:.8rem;color:#333}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Authorization session expired</h1>
                    <p>This page was opened with incomplete or missing OAuth parameters —
                       the authorization session has expired or the URL was truncated.</p>
                    <p>Do not refresh or navigate to this page directly.
                       Return to your AI client (e.g. Claude Code) and reconnect the
                       MCP server to start a fresh authorization flow.</p>
                    <p>In Claude Code: remove and re-add the MCP server, or run
                       <code>/mcp</code> to manage connections.</p>
                  </div>
                </body>
                </html>
                """;
    }

    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
