package io.ancoris.mcp;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Remote smoke test against the live OVH cluster.
 *
 * <p>Entirely skipped (ABORTED) in CI (env var {@code CI=true}) and when OVH
 * is unreachable. Local coverage for the same features is provided by
 * {@link McpEndToEndIT}, which is always green in CI. Run this class manually
 * when you need to verify a deployment to the cluster.
 *
 * <p><b>Session strategy</b>: fresh per-call session on every {@link #call}.
 * OVH's older MCP image ties sessions to the creating API key and invalidates
 * earlier sessions when a new one is opened for the same key, making a
 * long-lived shared session unreliable.
 *
 * <p><b>Capability detection</b>: {@code tools/list} is called once in
 * {@code @BeforeAll}. Tests that require {@code semantic_search_documents}
 * call {@code assumeTrue(semanticSearchAvailable)} and are marked
 * <em>ABORTED</em> (not FAILED) when the tool is absent (OVH pre-V7 image).
 *
 * <p><b>Spring context</b>: shares context with {@link McpEndToEndIT} via the
 * identical {@code @TestPropertySource} — this keeps them in the same Spring
 * context cache slot and away from {@link io.ancoris.mcp.security.RateLimiterFilterIT}
 * which overrides {@code max-requests=60}. The value 300 is redundant with
 * {@code application-test.yaml} — the annotation exists only for context-key
 * isolation, not value override.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "mcp.security.rate-limit.max-requests=300")
class McpRemoteEndToEndIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(McpRemoteEndToEndIT.class);

    private static final String OVH_BASE      = "https://mcp.37.59.24.118.nip.io";
    private static final String MCP_PATH      = "/mcp";
    private static final String READ_ONLY_KEY = "demo-readonly-key-001";
    private static final String ADMIN_KEY     = "demo-admin-key-001";

    /** Trust-all manager for the LE staging certificate on OVH. */
    private static final TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private WebTestClient webTestClient;

    private boolean semanticSearchAvailable;

    // -----------------------------------------------------------------------
    // Setup — abort entire class when OVH is not reachable
    // -----------------------------------------------------------------------

    @BeforeAll
    void setUpAll() throws SSLException {
        assumeTrue(!"true".equalsIgnoreCase(System.getenv("CI")),
                "Remote smoke tests are disabled in CI — run locally against OVH.");
        assumeTrue(isOvhHealthy(),
                "OVH is unreachable — remote smoke tests skipped. "
                + "Local coverage is provided by McpEndToEndIT.");

        log.info("[McpRemoteEndToEndIT] OVH reachable — targeting {}", OVH_BASE);
        webTestClient = buildOvhClient();

        String toolsBody = call(ADMIN_KEY, toolsList()).getBody();
        semanticSearchAvailable = toolsBody != null
                && toolsBody.contains("semantic_search_documents");

        if (!semanticSearchAvailable) {
            log.warn("[McpRemoteEndToEndIT] semantic_search_documents absent on OVH "
                    + "(pre-V7 image — deploy latest to enable semantic tests)");
        }
    }

    // -----------------------------------------------------------------------
    // Tool discovery
    // -----------------------------------------------------------------------

    @Test
    void toolsList_validKey_returnsCoreThroughTools() {
        String body = call(READ_ONLY_KEY, toolsList()).getBody();

        assertThat(body).contains("query_database");
        assertThat(body).contains("search_documents");
        assertThat(body).contains("list_sources");
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    @Test
    void missingApiKey_returns401() {
        ResponseEntity<String> resp = restCall(null, null, toolsList());
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void invalidApiKey_returns401() {
        ResponseEntity<String> resp = restCall("not-a-real-key", null, toolsList());
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // Health check — no auth required
    // -----------------------------------------------------------------------

    @Test
    void actuatorHealth_noAuthRequired() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // -----------------------------------------------------------------------
    // query_database — role-based column filtering
    // -----------------------------------------------------------------------

    @Test
    void queryDatabase_asReadOnly_salaryAbsent() {
        String body = call(READ_ONLY_KEY,
                toolCall("query_database", "{\"table\":\"employees\",\"maxRows\":10}"))
                .getBody();

        assertThat(body).contains("Alice Martin");
        assertThat(body).doesNotContain("\"salary\"");
    }

    @Test
    void queryDatabase_asAdmin_salaryPresent() {
        String body = call(ADMIN_KEY,
                toolCall("query_database", "{\"table\":\"employees\",\"maxRows\":10}"))
                .getBody();

        assertThat(body).contains("salary");
    }

    @Test
    void queryDatabase_asReadOnly_filterByDepartment() {
        String body = call(READ_ONLY_KEY,
                toolCall("query_database",
                        "{\"table\":\"employees\",\"filters\":{\"department\":\"IT\"}}"))
                .getBody();

        assertThat(body).contains("Bob Dupont");
        assertThat(body).contains("David Leroy");
        assertThat(body).doesNotContain("Alice Martin");
    }

    // -----------------------------------------------------------------------
    // search_documents — classification filtering
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_asReadOnly_noConfidential() {
        String body = call(READ_ONLY_KEY,
                toolCall("search_documents",
                        "{\"query\":\"recrutement\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).doesNotContain("CONFIDENTIAL");
    }

    @Test
    void searchDocuments_asAdmin_confidentialPresent() {
        String body = call(ADMIN_KEY,
                toolCall("search_documents",
                        "{\"query\":\"recrutement\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).contains("CONFIDENTIAL");
    }

    // -----------------------------------------------------------------------
    // list_sources — role-based source visibility
    // -----------------------------------------------------------------------

    @Test
    void listSources_asReadOnly_noSalaryOrConfidential() {
        String body = call(READ_ONLY_KEY, toolCall("list_sources", "{}")).getBody();

        assertThat(body).doesNotContain("CONFIDENTIAL");
        assertThat(body).doesNotContain("salary");
    }

    @Test
    void listSources_asAdmin_confidentialAdvertised() {
        String body = call(ADMIN_KEY, toolCall("list_sources", "{}")).getBody();

        assertThat(body).contains("CONFIDENTIAL");
    }

    // -----------------------------------------------------------------------
    // Security hardening
    // -----------------------------------------------------------------------

    @Test
    void sqlInjectionInTableName_noHttp500() {
        ResponseEntity<String> resp = call(READ_ONLY_KEY,
                toolCall("query_database",
                        "{\"table\":\"employees; DROP TABLE employees;--\"}"));

        assertThat(resp.getStatusCode().value()).isNotEqualTo(500);
    }

    @Test
    void errorResponse_doesNotLeakStackTrace() {
        String body = call(READ_ONLY_KEY,
                toolCall("query_database", "{\"table\":\"audit_logs\"}"))
                .getBody();

        assertThat(body).doesNotContain("at io.ancoris");
        assertThat(body).doesNotContain("Exception");
    }

    // -----------------------------------------------------------------------
    // semantic_search_documents — OVH V7+ image required (real Ollama + pgvector)
    // Tests are ABORTED (not FAILED) when the tool is absent.
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_toolAdvertised() {
        assumeTrue(semanticSearchAvailable,
                "semantic_search_documents absent — deploy V7 image to OVH to enable");

        String body = call(ADMIN_KEY, toolsList()).getBody();
        assertThat(body).contains("semantic_search_documents");
    }

    @Test
    void semanticSearch_queryTooLong_returnsErrorNotHttp500() {
        assumeTrue(semanticSearchAvailable,
                "semantic_search_documents absent — deploy V7 image to OVH to enable");

        String tooLong = "q".repeat(501);
        ResponseEntity<String> resp = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"" + tooLong + "\",\"maxResults\":5}"));

        assertThat(resp.getStatusCode().value())
                .as("oversized query must not produce HTTP 500")
                .isNotEqualTo(500);
        assertThat(resp.getBody())
                .doesNotContain("Exception")
                .doesNotContain("at io.ancoris");
    }

    @Test
    void semanticSearch_returnsContentWithTrustBoundaryMarkers() {
        assumeTrue(semanticSearchAvailable,
                "semantic_search_documents absent — deploy V7 image to OVH to enable");

        String body = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"semantic search\",\"maxResults\":10}"))
                .getBody();

        // SEC-017: OVH uses real Ollama embeddings — assert on trust-boundary markers
        assertThat(body).contains("[EXTERNAL_CONTENT_START]");
    }

    @Test
    void semanticSearch_asAdmin_confidentialVisible() {
        assumeTrue(semanticSearchAvailable,
                "semantic_search_documents absent — deploy V7 image to OVH to enable");

        String body = call(ADMIN_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"confidential\",\"maxResults\":10}"))
                .getBody();

        // OVH seed: politique-rh-v3.txt is CONFIDENTIAL
        assertThat(body).contains("CONFIDENTIAL");
    }

    // -----------------------------------------------------------------------
    // MCP session handshake — fresh session per call (OVH compatibility)
    // -----------------------------------------------------------------------

    /**
     * Opens a fresh MCP session with {@code apiKey}, makes the call, and returns
     * the response. A fresh session per call is required because OVH's older MCP
     * image invalidates existing sessions for a key when a new one is opened.
     */
    private ResponseEntity<String> call(String apiKey, String body) {
        ResponseEntity<String> initResp = restCall(apiKey, null, initializeRequest());
        assertThat(initResp.getStatusCode().value())
                .as("initialize must return 200")
                .isEqualTo(200);
        String sid = initResp.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sid)
                .as("server must return Mcp-Session-Id after initialize")
                .isNotNull();
        restCall(apiKey, sid, initializedNotification());
        return restCall(apiKey, sid, body);
    }

    private ResponseEntity<String> restCall(String apiKey, String mcpSession, String requestBody) {
        WebTestClient.RequestBodySpec spec = webTestClient.post()
                .uri(MCP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
        if (apiKey != null) {
            spec = spec.header("X-API-Key", apiKey);
        }
        if (mcpSession != null) {
            spec = spec.header("Mcp-Session-Id", mcpSession);
        }

        EntityExchangeResult<byte[]> result = spec
                .bodyValue(requestBody)
                .exchange()
                .expectBody()
                .returnResult();

        byte[] bytes = result.getResponseBody();
        String body = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
        HttpHeaders headers = result.getResponseHeaders();
        return ResponseEntity.status(result.getStatus()).headers(headers).body(body);
    }

    // -----------------------------------------------------------------------
    // OVH detection and client construction
    // -----------------------------------------------------------------------

    private static boolean isOvhHealthy() {
        try {
            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            sslCtx.init(null, new TrustManager[]{TRUST_ALL}, null);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .sslContext(sslCtx)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(OVH_BASE + "/actuator/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200 && response.body().contains("UP");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.security.GeneralSecurityException | java.io.IOException e) {
            return false;
        }
    }

    private static WebTestClient buildOvhClient() throws SSLException {
        SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        HttpClient httpClient = HttpClient.create()
                .secure(spec -> spec.sslContext(sslContext));
        return WebTestClient
                .bindToServer(new ReactorClientHttpConnector(httpClient))
                .baseUrl(OVH_BASE)
                .responseTimeout(Duration.ofSeconds(60))
                .build();
    }

    // -----------------------------------------------------------------------
    // JSON-RPC helpers
    // -----------------------------------------------------------------------

    private static String initializeRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\","
                + "\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"remote-e2e-test\",\"version\":\"1.0\"}}}";
    }

    private static String initializedNotification() {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}";
    }

    private static String toolsList() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
    }

    private static String toolCall(String toolName, String argumentsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\","
                + "\"arguments\":" + argumentsJson + "}}";
    }
}
