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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticSearchToolTest {

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
        // lenient: some tests throw before embed() is called, so the stub may go unused
        lenient().when(embeddingService.embed(anyString())).thenReturn(TEST_VECTOR);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    // query > 500 characters → IllegalArgumentException (SEC-018)
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_queryTooLong_throwsIllegalArgumentException() {
        authenticateAs(AccessRole.READ_ONLY);
        String longQuery = "x".repeat(501);

        assertThatThrownBy(() -> tool.semanticSearchDocuments(longQuery, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    // -----------------------------------------------------------------------
    // blank query → IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_blankQuery_throwsIllegalArgumentException() {
        authenticateAs(AccessRole.READ_ONLY);

        assertThatThrownBy(() -> tool.semanticSearchDocuments("   ", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // READ_ONLY: CONFIDENTIAL excluded from allowed classifications
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_readOnly_doesNotPassConfidentialClassification() {
        authenticateAs(AccessRole.READ_ONLY);
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of());

        tool.semanticSearchDocuments("query", 5);

        ArgumentCaptor<List<String>> classCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorSearchConnector).search(any(), classCaptor.capture(), anyInt());
        assertThat(classCaptor.getValue()).doesNotContain("'CONFIDENTIAL'");
        assertThat(classCaptor.getValue()).containsExactlyInAnyOrder("'PUBLIC'", "'INTERNAL'");
    }

    // -----------------------------------------------------------------------
    // ADMIN: all three classifications passed
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_admin_passesAllClassifications() {
        authenticateAs(AccessRole.ADMIN);
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of());

        tool.semanticSearchDocuments("query", 5);

        ArgumentCaptor<List<String>> classCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorSearchConnector).search(any(), classCaptor.capture(), anyInt());
        assertThat(classCaptor.getValue())
                .containsExactlyInAnyOrder("'PUBLIC'", "'INTERNAL'", "'CONFIDENTIAL'");
    }

    // -----------------------------------------------------------------------
    // maxResults is clamped at 10
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_maxResultsClampedAt10() {
        authenticateAs(AccessRole.ADMIN);
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of());

        tool.semanticSearchDocuments("query", 999);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(vectorSearchConnector).search(any(), any(), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Results are wrapped with SEC-017 trust boundary markers
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_fragmentsWrappedWithTrustBoundaryMarkers() {
        authenticateAs(AccessRole.READ_ONLY);
        UUID chunkId = UUID.randomUUID();
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of(
                Map.of("id", chunkId, "doc_name", "test.pdf", "classification", "PUBLIC", "chunk_index", 0)
        ));
        when(contentStore.fetchChunk(chunkId)).thenReturn("sensitive text");

        List<DataFragment> fragments = tool.semanticSearchDocuments("query", 5);

        assertThat(fragments).hasSize(1);
        assertThat(fragments.get(0).fragmentText())
                .contains("[EXTERNAL_CONTENT_START]")
                .contains("[EXTERNAL_CONTENT_END]")
                .contains("sensitive text");
    }

    // -----------------------------------------------------------------------
    // Audit log is written with correct tool name
    // -----------------------------------------------------------------------

    @Test
    void semanticSearch_auditLogWritten() {
        authenticateAs(AccessRole.ADMIN);
        when(vectorSearchConnector.search(any(), any(), anyInt())).thenReturn(List.of());

        tool.semanticSearchDocuments("test query", 3);

        verify(auditService).log(eq("semantic_search_documents"), any(), any(), anyString());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void authenticateAs(AccessRole role) {
        ApiKey apiKey = mock(ApiKey.class);
        // lenient: exception-throwing tests may not reach getRole()/getId()
        lenient().when(apiKey.getRole()).thenReturn(role);
        lenient().when(apiKey.getId()).thenReturn(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(
                apiKey, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
