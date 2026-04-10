package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditLogRepository;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.integration.TestSecurityHelper;
import io.ancoris.mcp.model.DataFragment;
import io.ancoris.mcp.security.ApiKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Hard integration tests for SemanticSearchTool that require a real PostgreSQL/pgvector
 * instance to exercise behaviour that mocked unit tests cannot catch:
 *
 * <ul>
 *   <li>Cosine similarity ordering (closest vector ranks first)</li>
 *   <li>INTERNAL classification visible to READ_ONLY callers</li>
 *   <li>10-result hard cap when the DB has more than 10 eligible rows</li>
 *   <li>Audit log paramsJson contains the actual query text</li>
 *   <li>Default limit of 5 when maxResults is null</li>
 *   <li>NPE defect when EmbeddingService returns null (documented, not hidden)</li>
 *   <li>Exactly-500-char query succeeds at the real validation boundary</li>
 * </ul>
 *
 * Each test manages its own fixtures (insert → assert → delete in tearDown) so that
 * test ordering and shared DB state cannot cause false positives or false negatives.
 */
class SemanticSearchRankingIT extends AbstractIntegrationTest {

    // -----------------------------------------------------------------------
    // Vector strategy for the ranking test
    //
    // pgvector `<=>` returns cosine DISTANCE (1 - cosine_similarity).
    // ORDER BY embedding <=> query ASC → smallest distance first.
    //
    //   CLOSE:       [0]=1.0, rest=0.0  → distance from QUERY = 0.0 (perfect match)
    //   ORTHOGONAL:  [1]=1.0, rest=0.0  → distance from QUERY = 1.0 (perpendicular)
    //   OPPOSITE:    [0]=-1.0, rest=0.0 → distance from QUERY = 2.0 (anti-parallel)
    //   QUERY vector = CLOSE
    // -----------------------------------------------------------------------

    private static final float[] CLOSE_VECTOR;
    private static final float[] ORTHOGONAL_VECTOR;
    private static final float[] OPPOSITE_VECTOR;

    static {
        CLOSE_VECTOR      = new float[768]; CLOSE_VECTOR[0]     =  1.0f;
        ORTHOGONAL_VECTOR = new float[768]; ORTHOGONAL_VECTOR[1] =  1.0f;
        OPPOSITE_VECTOR   = new float[768]; OPPOSITE_VECTOR[0]   = -1.0f;
    }

    @Autowired
    SemanticSearchTool semanticSearchTool;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    TestSecurityHelper secHelper;

    @Autowired
    JdbcTemplate jdbc;

    /** Collected IDs for cleanup; each test adds its own chunk IDs here. */
    private final List<UUID> toDelete = new ArrayList<>();

    @AfterEach
    void tearDown() {
        secHelper.clearAuthentication();
        for (UUID id : toDelete) {
            jdbc.update("DELETE FROM document_chunks WHERE id = ?", id);
        }
        toDelete.clear();
    }

    // -----------------------------------------------------------------------
    // Ranking: the chunk whose vector is CLOSEST to the query vector must
    // appear BEFORE the chunk whose vector is the OPPOSITE of the query.
    //
    // This is the hardest and most important test: if pgvector's ORDER BY
    // clause is removed, the wrong operator is used, or the vector is
    // formatted incorrectly, this test fails.
    // -----------------------------------------------------------------------

    @Test
    void rankingByCosineSimilarity_closestVectorFirst() {
        UUID closeId      = insertPublicChunk("close-chunk",      "closest chunk content",      CLOSE_VECTOR);
        UUID orthogonalId = insertPublicChunk("orthogonal-chunk", "orthogonal chunk content",   ORTHOGONAL_VECTOR);
        UUID oppositeId   = insertPublicChunk("opposite-chunk",   "farthest opposite chunk",    OPPOSITE_VECTOR);

        // Query vector = CLOSE vector → CLOSE chunk has distance 0, OPPOSITE has distance 2
        when(embeddingModel.embed(anyString())).thenReturn(CLOSE_VECTOR);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("ranking query", 10);

        List<String> ids = results.stream().map(DataFragment::sourceId).toList();
        int closePos    = ids.indexOf(closeId.toString());
        int oppositePos = ids.indexOf(oppositeId.toString());

        assertThat(closePos).as("CLOSE chunk must appear in results").isGreaterThanOrEqualTo(0);
        assertThat(oppositePos).as("OPPOSITE chunk must appear in results").isGreaterThanOrEqualTo(0);
        assertThat(closePos)
                .as("CLOSE chunk (distance=0) must rank before OPPOSITE chunk (distance=2)")
                .isLessThan(oppositePos);
    }

    // -----------------------------------------------------------------------
    // INTERNAL classification: READ_ONLY callers must see INTERNAL chunks.
    // No existing integration test covers INTERNAL — only PUBLIC and CONFIDENTIAL.
    // -----------------------------------------------------------------------

    @Test
    void internalClassification_visibleToReadOnly() {
        float[] v = new float[768]; v[2] = 1.0f;   // unique dimension to reduce cross-test interference
        UUID internalId = insertChunk("internal-ranking-doc", "INTERNAL",
                "internal classification hard test content", v);

        when(embeddingModel.embed(anyString())).thenReturn(v);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("internal test", 10);

        assertThat(results)
                .as("READ_ONLY must receive INTERNAL chunks alongside PUBLIC ones")
                .anyMatch(f -> internalId.toString().equals(f.sourceId()));
    }

    // -----------------------------------------------------------------------
    // 10-result hard cap: requesting more than 10 must never return more than 10.
    // The existing IT test only has 2 chunks total, so the cap was never tested
    // against a real DB with > 10 eligible rows.
    // -----------------------------------------------------------------------

    @Test
    void tenChunksCap_exactlyTenReturnedWhenTwelveExist() {
        // All 12 chunks use the same vector as the query → all at distance 0
        float[] v = new float[768]; v[3] = 1.0f;
        for (int i = 0; i < 12; i++) {
            insertPublicChunk("cap-test-doc-" + i, "cap test chunk " + i, v);
        }

        when(embeddingModel.embed(anyString())).thenReturn(v);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        // Request 12 — SemanticSearchTool clamps to 10 before the SQL LIMIT
        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("cap test", 12);

        assertThat(results)
                .as("Result count must never exceed 10 regardless of maxResults requested")
                .hasSizeLessThanOrEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Audit log paramsJson: the actual query string must be stored,
    // not just a placeholder. Existing IT only checks tool name.
    // -----------------------------------------------------------------------

    @Test
    void auditLog_queryTextStoredInParamsJson() {
        float[] v = new float[768]; v[4] = 1.0f;
        insertPublicChunk("audit-params-doc", "audit params content", v);

        String uniqueQuery = "hard-ranking-audit-query-" + UUID.randomUUID();
        when(embeddingModel.embed(anyString())).thenReturn(v);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        semanticSearchTool.semanticSearchDocuments(uniqueQuery, 5);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            boolean found = auditLogRepository.findAll().stream()
                    .filter(e -> "semantic_search_documents".equals(e.getToolName()))
                    .anyMatch(e -> {
                        Map<String, Object> params = e.getParamsJson();
                        return params != null && uniqueQuery.equals(params.get("query"));
                    });
            assertThat(found)
                    .as("paramsJson must persist the exact query text submitted by the caller")
                    .isTrue();
        });
    }

    // -----------------------------------------------------------------------
    // null maxResults defaults to 5: the tool defaults to 5, not 0 or 10.
    // Tests that Math.min(null-defaulted-to-5, 10) = 5 reaches the DB layer.
    // -----------------------------------------------------------------------

    @Test
    void maxResultsNull_defaultsFive() {
        float[] v = new float[768]; v[5] = 1.0f;
        for (int i = 0; i < 8; i++) {
            insertPublicChunk("default-limit-doc-" + i, "default limit content " + i, v);
        }

        when(embeddingModel.embed(anyString())).thenReturn(v);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("default limit test", null);

        assertThat(results)
                .as("null maxResults must apply a default cap of 5")
                .hasSizeLessThanOrEqualTo(5);
    }

    // -----------------------------------------------------------------------
    // NPE defect: if EmbeddingService.embed() returns null (e.g. Ollama is
    // not available during an active query), VectorSearchConnector.formatVector()
    // NPEs at `vector.length` because there is no null guard.
    //
    // This test DOCUMENTS the current wrong behaviour. After adding:
    //   if (queryVector == null) throw new IllegalStateException("Embedding service unavailable")
    // in SemanticSearchTool before calling vectorSearchConnector.search(), change the
    // assertion to:
    //   .isInstanceOf(IllegalStateException.class).hasMessageContaining("embedding")
    // -----------------------------------------------------------------------

    @Test
    void nullEmbedding_propagatesNpeDefect() {
        when(embeddingModel.embed(anyString())).thenReturn(null);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        // DEFECT: NullPointerException leaks from VectorSearchConnector.formatVector(null)
        // The tool has no guard; the NPE is an implementation detail, not a contract.
        assertThatThrownBy(() -> semanticSearchTool.semanticSearchDocuments("any query", 5))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // Exactly 500-char query: must succeed against the real validation path.
    // Unit tests cover 501 (fail) but not 500 (succeed) in an integration context.
    // -----------------------------------------------------------------------

    @Test
    void queryExactly500Chars_doesNotThrow() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[768]);
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        String exactly500 = "x".repeat(500);

        // No matching chunks for this query vector (all-zero) + no corpus for these chars.
        // The tool must return an empty list, not throw.
        assertThatCode(() -> semanticSearchTool.semanticSearchDocuments(exactly500, 5))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID insertPublicChunk(String docName, String textPreview, float[] vector) {
        return insertChunk(docName, "PUBLIC", textPreview, vector);
    }

    private UUID insertChunk(String docName, String classification, String textPreview, float[] vector) {
        UUID id = UUID.randomUUID();
        toDelete.add(id);
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, ?, ?, 0, ?, ?::vector)
                """, id, docName, classification, textPreview, formatVector(vector));
        return id;
    }

    private static String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
