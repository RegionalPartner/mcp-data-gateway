package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditLogRepository;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.integration.TestSecurityHelper;
import io.ancoris.mcp.model.DataFragment;
import io.ancoris.mcp.security.ApiKeyRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticSearchToolIT extends AbstractIntegrationTest {

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

    // 768-dim test vector — same value returned by the mocked EmbeddingModel,
    // so pgvector cosine similarity = 1.0 (perfect match) against inserted chunk.
    private static final float[] TEST_VECTOR = new float[768];

    static {
        Arrays.fill(TEST_VECTOR, 0.1f);
    }

    private UUID publicChunkId;
    private UUID confidentialChunkId;

    @BeforeAll
    void setUpSemanticFixtures() {
        String vectorStr = formatVector(TEST_VECTOR);

        // PUBLIC chunk — visible to READ_ONLY and ADMIN
        publicChunkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, 'semantic-test-doc', 'PUBLIC', 0,
                        'semantic search integration test public content', ?::vector)
                """, publicChunkId, vectorStr);

        // CONFIDENTIAL chunk — visible to ADMIN only
        confidentialChunkId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_chunks
                    (id, doc_name, classification, chunk_index, text_preview, embedding)
                VALUES (?, 'semantic-test-doc-conf', 'CONFIDENTIAL', 0,
                        'semantic search integration test confidential content', ?::vector)
                """, confidentialChunkId, vectorStr);
    }

    @AfterAll
    void tearDownSemanticFixtures() {
        jdbc.update("DELETE FROM document_chunks WHERE id IN (?, ?)", publicChunkId, confidentialChunkId);
    }

    @BeforeEach
    void setUpMock() {
        // @MockBean is reset after each test — reconfigure the stub each time
        when(embeddingModel.embed(anyString())).thenReturn(TEST_VECTOR);
    }

    @AfterEach
    void clearAuth() {
        secHelper.clearAuthentication();
    }

    // -----------------------------------------------------------------------
    // Basic happy-path: PUBLIC chunk is returned for READ_ONLY role
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_returnsPublicChunkForReadOnlyRole() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("anything", 10);

        assertThat(results).anyMatch(f -> publicChunkId.toString().equals(f.sourceId()));
    }

    // -----------------------------------------------------------------------
    // READ_ONLY: CONFIDENTIAL chunk must not appear in results
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_readOnly_excludesConfidentialChunk() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("anything", 10);

        assertThat(results).noneMatch(f -> "CONFIDENTIAL".equals(f.classification()));
    }

    // -----------------------------------------------------------------------
    // ADMIN: CONFIDENTIAL chunk must appear in results
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_admin_includesConfidentialChunk() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("anything", 10);

        assertThat(results).anyMatch(f -> confidentialChunkId.toString().equals(f.sourceId()));
    }

    // -----------------------------------------------------------------------
    // maxResults clamped at 10
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_maxResultsClampedAt10() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("anything", 999);

        assertThat(results).hasSizeLessThanOrEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Trust boundary markers are present in all fragments (SEC-017)
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_fragmentsHaveTrustBoundaryMarkers() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<DataFragment> results = semanticSearchTool.semanticSearchDocuments("anything", 10);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(f ->
                f.fragmentText().contains("[EXTERNAL_CONTENT_START]")
                        && f.fragmentText().contains("[EXTERNAL_CONTENT_END]"));
    }

    // -----------------------------------------------------------------------
    // Audit log is created after each invocation
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_createsAuditLogEntry() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        long countBefore = auditLogRepository.count();
        semanticSearchTool.semanticSearchDocuments("anything", 5);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(auditLogRepository.count()).isGreaterThan(countBefore);
            boolean hasSemanticEntry = auditLogRepository.findAll().stream()
                    .anyMatch(entry -> "semantic_search_documents".equals(entry.getToolName()));
            assertThat(hasSemanticEntry).isTrue();
        });
    }

    // -----------------------------------------------------------------------
    // V7 migration: embedding column exists with vector type
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void v7Migration_embeddingColumnExists() {
        Integer colCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'document_chunks'
                  AND column_name = 'embedding'
                """,
                Integer.class);

        assertThat(colCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // V7 migration: HNSW index was created
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void v7Migration_hnswIndexExists() {
        Integer idxCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'document_chunks'
                  AND indexname = 'idx_chunk_embedding_hnsw'
                """,
                Integer.class);

        assertThat(idxCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

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
