package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.connector.ContentStore;
import io.ancoris.mcp.connector.EmbeddingService;
import io.ancoris.mcp.connector.VectorSearchConnector;
import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import io.ancoris.mcp.model.DataFragment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hard unit tests for SemanticSearchTool: boundary values, field mapping correctness,
 * audit parameter content, and exact trust-marker placement.
 *
 * Complements SemanticSearchToolTest which covers the happy-path and classification
 * filtering. These tests target the cases that existing tests leave implicit or untested.
 */
@ExtendWith(MockitoExtension.class)
class SemanticSearchToolHardTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VectorSearchConnector vectorSearchConnector;

    @Mock
    private ContentStore contentStore;

    @Mock
    private AuditService auditService;

    private SemanticSearchTool tool;

    private static final float[] TEST_VECTOR = new float[768];

    @BeforeEach
    void setUp() {
        tool = new SemanticSearchTool(embeddingService, vectorSearchConnector, contentStore, auditService);
        lenient().when(embeddingService.embed(anyString())).thenReturn(TEST_VECTOR);
        lenient().when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    // null query → IllegalArgumentException (exercises the == null branch,
    // distinct from the blank-string test in SemanticSearchToolTest)
    // -----------------------------------------------------------------------

    @Test
    void nullQuery_throwsIllegalArgumentException() {
        authenticateAs(AccessRole.READ_ONLY);

        assertThatThrownBy(() -> tool.semanticSearchDocuments(null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    // -----------------------------------------------------------------------
    // 500-char query (exactly at the limit) must NOT throw.
    // The guard is `length > 500`, so 500 is the inclusive boundary.
    // -----------------------------------------------------------------------

    @Test
    void exactlyMaxLengthQuery_succeeds() {
        authenticateAs(AccessRole.READ_ONLY);
        String exactly500 = "x".repeat(500);

        assertThatCode(() -> tool.semanticSearchDocuments(exactly500, 5))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // null maxResults → tool defaults to 5 (not 0, not 10)
    // -----------------------------------------------------------------------

    @Test
    void maxResultsNull_defaultsFive_passesFiveToConnector() {
        authenticateAs(AccessRole.READ_ONLY);

        tool.semanticSearchDocuments("query", null);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(vectorSearchConnector).search(any(), any(), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(5);
    }

    // -----------------------------------------------------------------------
    // maxResults=1 passes 1 to VectorSearchConnector (boundary, not clamped)
    // -----------------------------------------------------------------------

    @Test
    void maxResultsOne_passesOneToConnector() {
        authenticateAs(AccessRole.READ_ONLY);

        tool.semanticSearchDocuments("query", 1);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(vectorSearchConnector).search(any(), any(), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // DataFragment field mapping: all four source fields (sourceId, docName,
    // classification, chunkIndex) must be mapped from the connector row.
    // Existing tests only assert on fragmentText, never on field identity.
    // -----------------------------------------------------------------------

    @Test
    void dataFragmentFields_allMappedCorrectly() {
        authenticateAs(AccessRole.READ_ONLY);
        UUID chunkId = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "id",             chunkId,
                "doc_name",       "specific-doc.pdf",
                "classification", "INTERNAL",
                "chunk_index",    7
        );
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of(row));
        when(contentStore.fetchChunk(chunkId)).thenReturn("raw text content");

        List<DataFragment> fragments = tool.semanticSearchDocuments("query", 5);

        assertThat(fragments).hasSize(1);
        DataFragment f = fragments.get(0);
        assertThat(f.sourceId()).isEqualTo(chunkId.toString());
        assertThat(f.docName()).isEqualTo("specific-doc.pdf");
        assertThat(f.classification()).isEqualTo("INTERNAL");
        assertThat(f.chunkIndex()).isEqualTo(7);
    }

    // -----------------------------------------------------------------------
    // Audit log: paramsJson must contain the actual query text, not just
    // a placeholder. The existing test uses any() for the params map argument.
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void auditLog_paramsJsonContainsQueryText() {
        authenticateAs(AccessRole.ADMIN);
        String specificQuery = "specific query text for audit";

        tool.semanticSearchDocuments(specificQuery, 3);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(
                eq("semantic_search_documents"),
                any(UUID.class),
                paramsCaptor.capture(),
                anyString()
        );
        assertThat(paramsCaptor.getValue())
                .containsEntry("query", specificQuery);
    }

    // -----------------------------------------------------------------------
    // Trust boundary markers (SEC-017) must appear exactly once per fragment —
    // not zero times, and not accidentally duplicated by a future refactor.
    // -----------------------------------------------------------------------

    @Test
    void trustBoundaryMarkers_appearExactlyOnce_perFragment() {
        authenticateAs(AccessRole.READ_ONLY);
        UUID id = UUID.randomUUID();
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of(
                Map.of("id", id, "doc_name", "d.pdf", "classification", "PUBLIC", "chunk_index", 0)
        ));
        when(contentStore.fetchChunk(id)).thenReturn("some content");

        List<DataFragment> fragments = tool.semanticSearchDocuments("query", 5);

        assertThat(fragments).hasSize(1);
        String text = fragments.get(0).fragmentText();
        assertThat(countOccurrences(text, "[EXTERNAL_CONTENT_START]"))
                .as("START marker must appear exactly once")
                .isEqualTo(1);
        assertThat(countOccurrences(text, "[EXTERNAL_CONTENT_END]"))
                .as("END marker must appear exactly once")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // All fragments in a multi-result response must have trust markers —
    // the wrapping must not be conditional or only applied to the first result.
    // -----------------------------------------------------------------------

    @Test
    void multipleResults_allWrappedWithTrustMarkers() {
        authenticateAs(AccessRole.READ_ONLY);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            rows.add(Map.of("id", id, "doc_name", "doc" + i + ".pdf",
                    "classification", "PUBLIC", "chunk_index", i));
            when(contentStore.fetchChunk(id)).thenReturn("content of chunk " + i);
        }
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(rows);

        List<DataFragment> fragments = tool.semanticSearchDocuments("query", 5);

        assertThat(fragments).hasSize(3);
        assertThat(fragments).allSatisfy(f -> {
            assertThat(f.fragmentText())
                    .contains("[EXTERNAL_CONTENT_START]")
                    .contains("[EXTERNAL_CONTENT_END]");
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void authenticateAs(AccessRole role) {
        ApiKey apiKey = mock(ApiKey.class);
        lenient().when(apiKey.getRole()).thenReturn(role);
        lenient().when(apiKey.getId()).thenReturn(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(
                apiKey, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
