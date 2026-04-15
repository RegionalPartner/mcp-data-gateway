package io.ancoris.mcp;

import io.ancoris.mcp.connector.ContentEncryptor;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.security.RateLimiterFilter;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Remote-first end-to-end test: targets OVH if reachable, falls back to the
 * local embedded server automatically.
 *
 * <h2>Target selection at runtime</h2>
 * <ol>
 *   <li>{@code @BeforeAll} calls {@code isOvhHealthy()} — an HTTPS GET to
 *       {@code /actuator/health} with a 3-second timeout.</li>
 *   <li>If OVH responds with {@code {"status":"UP"}}, all requests go to
 *       {@code https://mcp.37.59.24.118.nip.io} via a Reactor Netty client
 *       configured to trust the Let's Encrypt <em>staging</em> certificate.</li>
 *   <li>Otherwise the auto-configured {@link WebTestClient} pointing at the
 *       embedded Tomcat (random port) is used, and local fixtures are inserted.</li>
 * </ol>
 *
 * <h2>Capability detection</h2>
 * After session initialisation, {@code tools/list} is called to detect what the
 * target actually supports. OVH currently runs a pre-V7 image (no pgvector /
 * no Ollama), so {@code semantic_search_documents} is absent there. Any test
 * that requires semantic search calls {@code assumeTrue(semanticSearchAvailable)}
 * and is marked <em>ABORTED</em> (not FAILED) when the tool is missing.
 *
 * <h2>Seed data</h2>
 * The V2 seed (employees + document chunks) is present on both local and OVH,
 * so all non-semantic tests assert against that shared data. Locally-inserted
 * fixtures (semantic chunks, encrypted content) are only created — and cleaned
 * up — when running against the local server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "mcp.security.rate-limit.max-requests=300")
class McpRemoteEndToEndIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(McpRemoteEndToEndIT.class);

    private static final String OVH_BASE      = "https://mcp.37.59.24.118.nip.io";
    private static final String MCP_PATH      = "/mcp";
    private static final String READ_ONLY_KEY = "demo-readonly-key-001";
    private static final String ADMIN_KEY     = "demo-admin-key-001";

    private static final float[] SEMANTIC_VECTOR = new float[768];

    static {
        Arrays.fill(SEMANTIC_VECTOR, 0.1f);
    }

    /** Trust-all manager for the LE staging certificate on OVH. */
    private static final TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    // Spring-injected; reassigned in @BeforeAll when targeting OVH
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ContentEncryptor contentEncryptor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RateLimiterFilter rateLimiterFilter;

    /**
     * No persistent sessions.
     *
     * On OVH (older MCP image) the server ties every session to the API key
     * that created it AND appears to invalidate older sessions when a new one is
     * opened. Two long-lived sessions (one per key) therefore cause intermittent
     * failures because the second openSession() call silently kills the first.
     *
     * The fix: {@link #call} opens a fresh per-call session using exactly the
     * right API key, makes the single tool-call, and abandons the session. Cost:
     * 2 extra HTTP round-trips per test; benefit: no shared mutable session state.
     */
    private boolean targetingOvh;
    private boolean semanticSearchAvailable;

    // Local-only fixtures (not inserted when targeting OVH)
    private UUID semanticChunkId;
    private UUID confidentialSemanticChunkId;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeAll
    void setUpAll() throws SSLException {
        // McpEndToEndIT and McpRemoteEndToEndIT share one Spring context. The
        // per-IP sliding window accumulates across both classes. Reset it here
        // so this class starts with a clean slate.
        // Also raise the in-memory limit to 300 directly: @TestPropertySource and
        // application-test.yaml overrides are not picked up at @Value injection time
        // in this shared context, so the field stays at its default of 60 without this.
        rateLimiterFilter.clearRequestLog();
        rateLimiterFilter.maxRequestsPerWindow = 300;

        targetingOvh = isOvhHealthy();

        if (targetingOvh) {
            log.info("[McpRemoteEndToEndIT] OVH reachable — targeting {}", OVH_BASE);
            webTestClient = buildOvhClient();
        } else {
            log.info("[McpRemoteEndToEndIT] OVH unreachable — falling back to local embedded server");
            webTestClient = webTestClient.mutate()
                    .responseTimeout(Duration.ofSeconds(60))
                    .build();
            populateEncryptedContent();
            insertSemanticChunks();
        }

        // Detect whether the target has semantic_search_documents.
        // OVH currently runs a pre-V7 image (no pgvector / no Ollama), so the
        // tool is absent there. Semantic tests will be ABORTED, not FAILED.
        String toolsBody = call(ADMIN_KEY, toolsList()).getBody();
        semanticSearchAvailable = toolsBody != null
                && toolsBody.contains("semantic_search_documents");

        if (!semanticSearchAvailable) {
            log.warn("[McpRemoteEndToEndIT] semantic_search_documents not advertised by target "
                    + "(OVH is on pre-V7 schema — deploy latest image to enable)");
        }
    }

    @AfterAll
    void tearDownAll() {
        if (!targetingOvh) {
            if (semanticChunkId != null) {
                jdbc.update("DELETE FROM document_chunks WHERE id = ?", semanticChunkId);
            }
            if (confidentialSemanticChunkId != null) {
                jdbc.update("DELETE FROM document_chunks WHERE id = ?", confidentialSemanticChunkId);
            }
        }
    }

    @BeforeEach
    void maybeConfigureEmbeddingMock() {
        if (!targetingOvh) {
            // Local: EmbeddingModel is a @MockBean — return a deterministic vector
            when(embeddingModel.embed(anyString())).thenReturn(SEMANTIC_VECTOR);
        }
        // OVH: calls go over HTTP to real Ollama — mock is irrelevant
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
    // search_documents — classification filtering (keyword FTS, no Ollama)
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
    // semantic_search_documents
    // Skipped (ABORTED) when the target does not advertise the tool —
    // currently the case on OVH which runs a pre-V7 image without pgvector.
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_toolAdvertised() {
        assumeTrue(semanticSearchAvailable,
                "Target does not advertise semantic_search_documents — deploy V7 image to OVH");

        String body = call(ADMIN_KEY, toolsList()).getBody();
        assertThat(body).contains("semantic_search_documents");
    }

    @Test
    void semanticSearch_queryTooLong_returnsErrorNotHttp500() {
        assumeTrue(semanticSearchAvailable,
                "Target does not advertise semantic_search_documents — deploy V7 image to OVH");

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
    void semanticSearch_returnsPublicContent() {
        assumeTrue(semanticSearchAvailable,
                "Target does not advertise semantic_search_documents — deploy V7 image to OVH");

        String body = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"semantic search\",\"maxResults\":10}"))
                .getBody();

        if (targetingOvh) {
            // OVH has real Ollama + seed data embeddings — assert on SEC-017 markers
            // (content depends on which seed chunk Ollama ranks closest)
            assertThat(body).contains("[EXTERNAL_CONTENT_START]");
        } else {
            // Local fixture inserted in insertSemanticChunks()
            assertThat(body).contains("e2e semantic search public content");
        }
    }

    @Test
    void semanticSearch_asAdmin_confidentialVisible() {
        assumeTrue(semanticSearchAvailable,
                "Target does not advertise semantic_search_documents — deploy V7 image to OVH");

        String body = call(ADMIN_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"confidential test\",\"maxResults\":10}"))
                .getBody();

        if (targetingOvh) {
            // OVH seed: politique-rh-v3.txt is CONFIDENTIAL
            assertThat(body).contains("CONFIDENTIAL");
        } else {
            assertThat(body).contains("e2e confidential semantic unique marker text");
        }
    }

    @Test
    void semanticSearch_trustBoundaryMarkersPresent() {
        assumeTrue(semanticSearchAvailable,
                "Target does not advertise semantic_search_documents — deploy V7 image to OVH");

        String body = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"semantic search\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).contains("[EXTERNAL_CONTENT_START]");
    }

    // -----------------------------------------------------------------------
    // Local fixtures (only populated when not targeting OVH)
    // -----------------------------------------------------------------------

    private void populateEncryptedContent() {
        encryptAndStore("rapport-annuel-2024-chunk-00.json",
                "Le rapport annuel 2024 présente les résultats consolidés de l'Agence de Développement de Normandie.");
        encryptAndStore("rapport-annuel-2024-chunk-01.json",
                "Les investissements en infrastructure numérique ont augmenté de 23% par rapport à l'exercice précédent.");
        encryptAndStore("rapport-annuel-2024-chunk-02.json",
                "Le bilan énergétique des datacenters normands montre une réduction de 15% de la consommation électrique.");
        encryptAndStore("politique-rh-v3-chunk-00.json",
                "La politique RH version 3 définit les procédures de recrutement et d'évaluation des compétences.");
        encryptAndStore("note-technique-securite-chunk-00.json",
                "Cette note technique décrit les bonnes pratiques de sécurité informatique applicables à tous les agents.");
    }

    private void insertSemanticChunks() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SEMANTIC_VECTOR.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(SEMANTIC_VECTOR[i]);
        }
        sb.append(']');
        String vectorStr = sb.toString();

        semanticChunkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, 'remote-e2e-semantic-doc', 'PUBLIC', 0,
                        'e2e semantic search public content', ?::vector)
                """, semanticChunkId, vectorStr);

        confidentialSemanticChunkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, 'remote-e2e-confidential-doc', 'CONFIDENTIAL', 0,
                        'e2e confidential semantic unique marker text', ?::vector)
                """, confidentialSemanticChunkId, vectorStr);
    }

    private void encryptAndStore(String minioKeyFragment, String text) {
        byte[] encrypted = contentEncryptor.encrypt(text);
        jdbc.update("UPDATE document_chunks SET encrypted_content = ? WHERE minio_key LIKE ?",
                encrypted, "%" + minioKeyFragment);
    }

    // -----------------------------------------------------------------------
    // OVH detection and client construction
    // -----------------------------------------------------------------------

    /**
     * Returns true when OVH responds to /actuator/health within 3 seconds.
     * Uses a trust-all SSL context because OVH has a Let's Encrypt staging cert.
     */
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

    /**
     * Builds a WebTestClient pointing at OVH with a trust-all Netty SSL context
     * (required because OVH uses a Let's Encrypt staging certificate).
     */
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
    // MCP session handshake — one fresh session per call
    // -----------------------------------------------------------------------

    /**
     * Opens a fresh MCP session with {@code apiKey}, sends the tool/meta call in
     * {@code body} on that session, and returns the raw HTTP response.
     *
     * <p>Creating a new session for every call is the only approach that works
     * reliably against OVH's older MCP image: that server binds each session to
     * the API key used during {@code initialize} and, worse, invalidates older
     * sessions when a new one is created. Using one long-lived session per key
     * therefore causes race-condition failures.
     */
    private ResponseEntity<String> call(String apiKey, String body) {
        // Step 1 — initialize: obtain a fresh Mcp-Session-Id tied to apiKey
        ResponseEntity<String> initResp = restCall(apiKey, null, initializeRequest());
        assertThat(initResp.getStatusCode().value())
                .as("initialize must return 200")
                .isEqualTo(200);
        String sid = initResp.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sid)
                .as("server must return Mcp-Session-Id header after initialize")
                .isNotNull();

        // Step 2 — notifications/initialized (fire-and-forget, result not inspected)
        restCall(apiKey, sid, initializedNotification());

        // Step 3 — actual tool/meta call on the freshly bound session
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
