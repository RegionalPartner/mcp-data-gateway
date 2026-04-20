package io.ancoris.mcp;

import io.ancoris.mcp.connector.ContentEncryptor;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end test: exercises the full HTTP stack as a real LLM client would.
 *
 * Uses WebTestClient (Reactor Netty) to make real HTTP requests to the embedded
 * Tomcat server, so RouterFunction routes (registered by the Spring AI MCP
 * framework) are exercised end-to-end, including filters, security, and the
 * transport layer.
 *
 * MCP streamable-HTTP protocol (2025-03-26):
 *   1. POST /mcp — initialize request (no Mcp-Session-Id) → returns Mcp-Session-Id header
 *   2. POST /mcp + Mcp-Session-Id — notifications/initialized (confirms session)
 *   3. POST /mcp + Mcp-Session-Id — tool calls → server responds with text/event-stream SSE
 *
 * All POST requests require:
 *   - X-API-Key header (authentication)
 *   - Accept: application/json, text/event-stream (required by the transport)
 *   - Content-Type: application/json
 *
 * WebTestClient is used instead of TestRestTemplate because Spring Boot 3.2+
 * defaults to JdkClientHttpRequestFactory which fails to read chunked
 * text/event-stream responses (Spring WebMVC's DeferredResult-based SSE commits
 * the response from a Reactor thread, and the JDK HTTP client's async receiver
 * closes the stream prematurely). Reactor Netty handles chunked SSE correctly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// NOTE: @TestPropertySource establishes the same Spring context key as McpRemoteEndToEndIT,
// isolating both from RateLimiterFilterIT which overrides max-requests=60. The value 300
// is redundant with application-test.yaml — this annotation exists for context-key isolation.
@TestPropertySource(properties = "mcp.security.rate-limit.max-requests=300")
class McpEndToEndIT extends AbstractIntegrationTest {

    // Must match McpServerStreamableHttpProperties default (spring.ai.mcp.server.protocol: STREAMABLE)
    private static final String MCP_PATH = "/mcp";

    private static final String READ_ONLY_KEY = "demo-readonly-key-001";
    private static final String ADMIN_KEY = "demo-admin-key-001";

    private static final float[] SEMANTIC_VECTOR = new float[768];

    static {
        Arrays.fill(SEMANTIC_VECTOR, 0.1f);
    }

    @LocalServerPort
    private int localServerPort;

    private WebTestClient webTestClient;

    @Autowired
    private ContentEncryptor contentEncryptor;

    @Autowired
    private JdbcTemplate jdbc;

    /** Shared MCP session established once for the class. */
    private String sessionId;

    private UUID semanticChunkId;
    private UUID confidentialSemanticChunkId;

    // -----------------------------------------------------------------------
    // Setup: encrypted DB fixtures + MCP session handshake
    // -----------------------------------------------------------------------

    @BeforeAll
    void setUpAll() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + localServerPort)
                .responseTimeout(Duration.ofSeconds(60))
                .build();

        populateEncryptedContent();
        insertSemanticChunk();
        initializeMcpSession();
    }

    @AfterAll
    void tearDownAll() {
        if (semanticChunkId != null) {
            jdbc.update("DELETE FROM document_chunks WHERE id = ?", semanticChunkId);
        }
        if (confidentialSemanticChunkId != null) {
            jdbc.update("DELETE FROM document_chunks WHERE id = ?", confidentialSemanticChunkId);
        }
    }

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

    private void insertSemanticChunk() {
        semanticChunkId = UUID.randomUUID();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SEMANTIC_VECTOR.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(SEMANTIC_VECTOR[i]);
        }
        sb.append(']');
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, 'e2e-semantic-test-doc', 'PUBLIC', 0,
                        'e2e semantic search public content', ?::vector)
                """, semanticChunkId, sb.toString());

        // CONFIDENTIAL chunk — needed by semanticSearch_asAdmin_confidentialChunkVisible()
        confidentialSemanticChunkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, 'e2e-confidential-semantic-doc', 'CONFIDENTIAL', 0,
                        'e2e confidential semantic unique marker text', ?::vector)
                """, confidentialSemanticChunkId, sb.toString());
    }

    private void encryptAndStore(String minioKeyFragment, String text) {
        byte[] encrypted = contentEncryptor.encrypt(text);
        jdbc.update(
                "UPDATE document_chunks SET encrypted_content = ? WHERE minio_key LIKE ?",
                encrypted, "%" + minioKeyFragment);
    }

    private void initializeMcpSession() {
        // Step 1: initialize — server creates a session and returns its ID
        ResponseEntity<String> initResp = restCall(
                ADMIN_KEY, null, initializeRequest());

        assertThat(initResp.getStatusCode().value())
                .as("initialize must return 200")
                .isEqualTo(200);

        sessionId = initResp.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId)
                .as("server must return Mcp-Session-Id header after initialize")
                .isNotNull();

        // Step 2: send the required notifications/initialized (no response expected)
        restCall(ADMIN_KEY, sessionId, initializedNotification());
    }

    // -----------------------------------------------------------------------
    // Tool discovery: tools/list must advertise all three tools
    // -----------------------------------------------------------------------

    @Test
    void toolsList_validKey_returnsAllFourTools() {
        String body = call(READ_ONLY_KEY, toolsList()).getBody();

        assertThat(body).contains("query_database");
        assertThat(body).contains("search_documents");
        assertThat(body).contains("list_sources");
        assertThat(body).contains("semantic_search_documents");
    }

    // -----------------------------------------------------------------------
    // Semantic search (RAG): public chunk returned via full HTTP stack
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_e2e_returnsPublicChunk() {
        when(embeddingModel.embed(anyString())).thenReturn(SEMANTIC_VECTOR);

        String body = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents", "{\"query\":\"semantic search\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).contains("e2e semantic search public content");
    }

    // -----------------------------------------------------------------------
    // Security: missing X-API-Key must be rejected before reaching the MCP layer
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
    // Health check must always be accessible without authentication
    // -----------------------------------------------------------------------

    @Test
    void actuatorHealth_noAuthRequired() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // -----------------------------------------------------------------------
    // Role filtering — columns:
    // READ_ONLY must NOT see "salary" in the HTTP response
    // -----------------------------------------------------------------------

    @Test
    void queryDatabase_asReadOnly_salaryAbsent() {
        String body = call(READ_ONLY_KEY,
                toolCall("query_database", "{\"table\":\"employees\",\"maxRows\":10}"))
                .getBody();

        assertThat(body).contains("Alice Martin"); // proves the query ran
        assertThat(body).doesNotContain("\"salary\"");
    }

    // -----------------------------------------------------------------------
    // ADMIN must see "salary" in the HTTP response
    // -----------------------------------------------------------------------

    @Test
    void queryDatabase_asAdmin_salaryPresent() {
        String body = call(ADMIN_KEY,
                toolCall("query_database", "{\"table\":\"employees\",\"maxRows\":10}"))
                .getBody();

        assertThat(body).contains("salary");
    }

    // -----------------------------------------------------------------------
    // Department filter must work end-to-end through the HTTP layer
    // -----------------------------------------------------------------------

    @Test
    void queryDatabase_asReadOnly_filterByDepartment() {
        String body = call(READ_ONLY_KEY,
                toolCall("query_database",
                        "{\"table\":\"employees\",\"filters\":{\"department\":\"IT\"}}"))
                .getBody();

        assertThat(body).contains("Bob Dupont");
        assertThat(body).contains("David Leroy");
        assertThat(body).doesNotContain("Alice Martin"); // RH, not IT
    }

    // -----------------------------------------------------------------------
    // Role filtering — classifications:
    // READ_ONLY must NOT see CONFIDENTIAL documents in search results
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_asReadOnly_noConfidential() {
        // "recrutement" appears only in the CONFIDENTIAL politique-rh chunk
        String body = call(READ_ONLY_KEY,
                toolCall("search_documents",
                        "{\"query\":\"recrutement\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).doesNotContain("CONFIDENTIAL");
    }

    // -----------------------------------------------------------------------
    // ADMIN must see CONFIDENTIAL documents in search results
    // -----------------------------------------------------------------------

    @Test
    void searchDocuments_asAdmin_confidentialPresent() {
        String body = call(ADMIN_KEY,
                toolCall("search_documents",
                        "{\"query\":\"recrutement\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).contains("CONFIDENTIAL");
    }

    // -----------------------------------------------------------------------
    // list_sources for READ_ONLY must not advertise salary or CONFIDENTIAL
    // -----------------------------------------------------------------------

    @Test
    void listSources_asReadOnly_noSalaryOrConfidential() {
        String body = call(READ_ONLY_KEY, toolCall("list_sources", "{}")).getBody();

        assertThat(body).doesNotContain("CONFIDENTIAL");
        assertThat(body).doesNotContain("salary");
    }

    // -----------------------------------------------------------------------
    // list_sources for ADMIN must advertise CONFIDENTIAL
    // -----------------------------------------------------------------------

    @Test
    void listSources_asAdmin_confidentialAdvertised() {
        String body = call(ADMIN_KEY, toolCall("list_sources", "{}")).getBody();

        assertThat(body).contains("CONFIDENTIAL");
    }

    // -----------------------------------------------------------------------
    // SQL injection in table name must NOT produce an unhandled HTTP 500
    // -----------------------------------------------------------------------

    @Test
    void sqlInjectionInTableName_noHttp500() {
        ResponseEntity<String> resp = call(READ_ONLY_KEY,
                toolCall("query_database",
                        "{\"table\":\"employees; DROP TABLE employees;--\"}"));
        assertThat(resp.getStatusCode().value()).isNotEqualTo(500);
    }

    // -----------------------------------------------------------------------
    // Error responses must not leak class names or stack traces
    // -----------------------------------------------------------------------

    @Test
    void errorResponse_doesNotLeakStackTrace() {
        String body = call(READ_ONLY_KEY,
                toolCall("query_database", "{\"table\":\"audit_logs\"}"))
                .getBody();

        assertThat(body).doesNotContain("at io.ancoris");
        assertThat(body).doesNotContain("Exception");
    }

    // -----------------------------------------------------------------------
    // Semantic search: 501-char query must produce an error response, not
    // an unhandled HTTP 500 or a silent success. The MCP framework wraps
    // IllegalArgumentException into a JSON-RPC error object (HTTP 200 body).
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_queryTooLong_returnsErrorNotHttp500() {
        String tooLong = "q".repeat(501);
        ResponseEntity<String> resp = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"" + tooLong + "\",\"maxResults\":5}"));

        assertThat(resp.getStatusCode().value())
                .as("oversized query must not produce HTTP 500")
                .isNotEqualTo(500);
        assertThat(resp.getBody())
                .as("error response must not leak stack traces")
                .doesNotContain("Exception")
                .doesNotContain("at io.ancoris");
    }

    // -----------------------------------------------------------------------
    // Semantic search: ADMIN must see CONFIDENTIAL chunks in results.
    // Only READ_ONLY was tested in the existing E2E test.
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_asAdmin_confidentialChunkVisible() {
        when(embeddingModel.embed(anyString())).thenReturn(SEMANTIC_VECTOR);

        String body = call(ADMIN_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"confidential semantic test\",\"maxResults\":10}"))
                .getBody();

        // text_preview is returned as-is (no encrypted_content set for test fixtures)
        assertThat(body).contains("e2e confidential semantic unique marker text");
    }

    // -----------------------------------------------------------------------
    // Semantic search: SEC-017 trust boundary markers must appear in the raw
    // SSE response that reaches the LLM. Verifies the E2E transport does not
    // strip or modify the DataFragment.fragmentText content.
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_trustBoundaryMarkersPresent() {
        when(embeddingModel.embed(anyString())).thenReturn(SEMANTIC_VECTOR);

        String body = call(READ_ONLY_KEY,
                toolCall("semantic_search_documents",
                        "{\"query\":\"semantic search\",\"maxResults\":10}"))
                .getBody();

        assertThat(body).contains("[EXTERNAL_CONTENT_START]");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Makes an authenticated tool call using the shared session. */
    private ResponseEntity<String> call(String apiKey, String body) {
        return restCall(apiKey, sessionId, body);
    }

    /**
     * Low-level REST call to the MCP endpoint.
     *
     * Uses WebTestClient (Reactor Netty) which correctly handles both plain JSON
     * responses (initialize, 401) and chunked text/event-stream SSE responses
     * (tool calls). The raw body bytes are accumulated and returned as a String,
     * so assertions work uniformly regardless of content type.
     *
     * For SSE tool-call responses the body contains the full SSE wire format:
     * {@code id:...\nevent:message\ndata:{...}\n\n}. All content assertions
     * check substrings, so they work equally well against the JSON embedded
     * inside the {@code data:} field.
     *
     * @param apiKey    X-API-Key header value (null to omit)
     * @param mcpSession Mcp-Session-Id header value (null to omit)
     * @param requestBody JSON-RPC request body
     */
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
                + "\"clientInfo\":{\"name\":\"e2e-test\",\"version\":\"1.0\"}}}";
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
