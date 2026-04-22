package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the workspace segmentation schema produced by V11–V13 migrations.
 *
 * Note: the Testcontainers user is a PostgreSQL superuser and therefore bypasses
 * FORCE ROW LEVEL SECURITY, so row-level filtering cannot be verified here.
 * Runtime enforcement is covered by the production non-superuser role (mcpuser).
 * These tests verify the schema structure (tables, columns, policy presence) and
 * that the V11 back-fill seeded all existing keys into the default workspace.
 */
@Transactional
class WorkspaceSegmentationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    // -----------------------------------------------------------------------
    // V11: workspaces table exists and contains the default workspace seed
    // -----------------------------------------------------------------------

    @Test
    void v11Migration_workspacesTableExists() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_name = 'workspaces'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v11Migration_defaultWorkspaceSeedPresent() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM workspaces
                WHERE id = '00000000-0000-0000-0000-000000000001'
                  AND name = 'default'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v11Migration_apiKeyWorkspacesTableExists() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_name = 'api_key_workspaces'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v11Migration_allExistingKeysGrantedDefaultWorkspace() {
        // Every api_key seeded by V2 should have a row in api_key_workspaces.
        Integer keyCount = jdbc.queryForObject("SELECT count(*) FROM api_keys", Integer.class);
        Integer grantCount = jdbc.queryForObject(
                """
                SELECT count(*) FROM api_key_workspaces
                WHERE workspace_id = '00000000-0000-0000-0000-000000000001'
                """,
                Integer.class);
        assertThat(keyCount).isGreaterThan(0);
        assertThat(grantCount).isEqualTo(keyCount);
    }

    // -----------------------------------------------------------------------
    // V12: workspace_id column exists on document_chunks and is back-filled
    // -----------------------------------------------------------------------

    @Test
    void v12Migration_workspaceIdColumnExistsOnDocumentChunks() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name  = 'document_chunks'
                  AND column_name = 'workspace_id'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v12Migration_allExistingChunksInDefaultWorkspace() {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM document_chunks", Integer.class);
        Integer inDefault = jdbc.queryForObject(
                """
                SELECT count(*) FROM document_chunks
                WHERE workspace_id = '00000000-0000-0000-0000-000000000001'
                """,
                Integer.class);
        assertThat(total).isGreaterThan(0);
        assertThat(inDefault).isEqualTo(total);
    }

    @Test
    void v12Migration_workspaceIndexExists() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'document_chunks'
                  AND indexname  = 'idx_chunk_workspace'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // V13: segmentation policy exists; old classification policy is gone
    // -----------------------------------------------------------------------

    @Test
    void v13Migration_segmentationPolicyExists() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_policies
                WHERE tablename  = 'document_chunks'
                  AND policyname = 'doc_chunks_segmentation_policy'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void v13Migration_oldClassificationPolicyDropped() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_policies
                WHERE tablename  = 'document_chunks'
                  AND policyname = 'doc_chunks_classification_policy'
                """,
                Integer.class);
        assertThat(count).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // workspace_id visible in allowed column list (PostgresConnector step 5)
    // -----------------------------------------------------------------------

    @Test
    void v12Migration_workspaceIdSelectableViaAllowedColumns() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT workspace_id FROM document_chunks LIMIT 1");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).containsKey("workspace_id");
    }
}
