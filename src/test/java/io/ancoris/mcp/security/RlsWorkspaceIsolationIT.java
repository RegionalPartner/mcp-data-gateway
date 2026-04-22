package io.ancoris.mcp.security;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies V14 RLS correctness: PERMISSIVE doc_chunks_classification +
 * RESTRICTIVE doc_chunks_workspace_isolation enforce workspace isolation on
 * both reads (SELECT) and writes (INSERT WITH CHECK).
 *
 * <p>Strategy: the Testcontainers user is a superuser and bypasses FORCE RLS.
 * To exercise actual policy enforcement, this test creates a restricted role
 * ({@code rls_test_role}) that is NOT a superuser and NOT the table owner, so
 * PostgreSQL applies FORCE ROW LEVEL SECURITY to it.  All enforcement assertions
 * run under that restricted role.
 *
 * <p>Schema assertions (policy presence, restrictive flag, WITH CHECK) run as
 * superuser via pg_policies.
 */
class RlsWorkspaceIsolationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    // Two workspaces seeded per test — workspace A and workspace B
    private UUID workspaceA;
    private UUID workspaceB;

    // One API key per workspace
    private UUID apiKeyIdA;
    private UUID apiKeyIdB;

    @BeforeEach
    void seedWorkspacesAndKeys() {
        workspaceA = UUID.randomUUID();
        workspaceB = UUID.randomUUID();
        apiKeyIdA  = UUID.randomUUID();
        apiKeyIdB  = UUID.randomUUID();

        jdbc.update("INSERT INTO workspaces (id, name) VALUES (?, ?)", workspaceA, "rls-test-ws-a");
        jdbc.update("INSERT INTO workspaces (id, name) VALUES (?, ?)", workspaceB, "rls-test-ws-b");

        jdbc.update(
                "INSERT INTO api_keys (id, key_hash, label, role) VALUES (?, ?, ?, ?)",
                apiKeyIdA, "fake-hash-rls-a", "rls-test-key-a", "READ_ONLY");
        jdbc.update(
                "INSERT INTO api_keys (id, key_hash, label, role) VALUES (?, ?, ?, ?)",
                apiKeyIdB, "fake-hash-rls-b", "rls-test-key-b", "READ_ONLY");

        jdbc.update("INSERT INTO api_key_workspaces (api_key_id, workspace_id) VALUES (?, ?)",
                apiKeyIdA, workspaceA);
        jdbc.update("INSERT INTO api_key_workspaces (api_key_id, workspace_id) VALUES (?, ?)",
                apiKeyIdB, workspaceB);

        // Ensure rls_test_role exists and has BYPASSRLS disabled
        jdbc.execute(
                "DO $$ BEGIN "
                + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rls_test_role') THEN "
                + "    CREATE ROLE rls_test_role NOINHERIT LOGIN PASSWORD 'rls_test_password'; "
                + "  END IF; "
                + "END $$");
        // Grant minimal table access — enough to exercise RLS, no bypass
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON document_chunks TO rls_test_role");
        jdbc.execute("GRANT SELECT ON api_key_workspaces TO rls_test_role");
        jdbc.execute("GRANT EXECUTE ON FUNCTION app_setting(text) TO rls_test_role");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM document_chunks WHERE doc_name LIKE 'rls-test-%'");
        jdbc.update("DELETE FROM api_key_workspaces WHERE api_key_id IN (?, ?)", apiKeyIdA, apiKeyIdB);
        jdbc.update("DELETE FROM api_keys WHERE id IN (?, ?)", apiKeyIdA, apiKeyIdB);
        jdbc.update("DELETE FROM workspaces WHERE id IN (?, ?)", workspaceA, workspaceB);
    }

    // -----------------------------------------------------------------------
    // Schema assertions: V14 policies are present with correct attributes
    // -----------------------------------------------------------------------

    @Test
    void v14_workspaceIsolationPolicyIsRestrictive() {
        String permissive = jdbc.queryForObject(
                """
                SELECT permissive FROM pg_policies
                WHERE tablename = 'document_chunks'
                  AND policyname = 'doc_chunks_workspace_isolation'
                """,
                String.class);
        assertThat(permissive).isEqualTo("RESTRICTIVE");
    }

    @Test
    void v14_workspaceIsolationPolicyHasWithCheck() {
        String withCheck = jdbc.queryForObject(
                """
                SELECT with_check FROM pg_policies
                WHERE tablename = 'document_chunks'
                  AND policyname = 'doc_chunks_workspace_isolation'
                """,
                String.class);
        // WITH CHECK expression should be non-null (workspace_id IN subquery)
        assertThat(withCheck).isNotNull().isNotEmpty();
    }

    @Test
    void v14_classificationPolicyIsPermissive() {
        String permissive = jdbc.queryForObject(
                """
                SELECT permissive FROM pg_policies
                WHERE tablename = 'document_chunks'
                  AND policyname = 'doc_chunks_classification'
                """,
                String.class);
        assertThat(permissive).isEqualTo("PERMISSIVE");
    }

    @Test
    void v14_segmentationPolicyDropped() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_policies
                WHERE tablename = 'document_chunks'
                  AND policyname = 'doc_chunks_segmentation_policy'
                """,
                Integer.class);
        assertThat(count).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // app_setting() function correctness
    // -----------------------------------------------------------------------

    @Test
    void appSettingFunction_returnsNullForUnsetKey() {
        // In a fresh connection, app.mcp_client_id is unset → NULLIF → NULL
        String result = jdbc.queryForObject(
                "SELECT app_setting('app.mcp_client_id')", String.class);
        assertThat(result).isNull();
    }

    @Test
    void appSettingFunction_returnsValueForSetKey() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        String result = tx.execute(status -> {
            setConfig("app.mcp_client_id", apiKeyIdA.toString());
            return jdbc.queryForObject("SELECT app_setting('app.mcp_client_id')", String.class);
        });

        assertThat(result).isEqualTo(apiKeyIdA.toString());
    }

    @Test
    void appSettingFunction_emptyStringReturnsNull() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        String result = tx.execute(status -> {
            setConfig("app.mcp_client_id", "");
            return jdbc.queryForObject("SELECT app_setting('app.mcp_client_id')", String.class);
        });

        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Row-level enforcement via restricted role (non-superuser, subject to RLS)
    // -----------------------------------------------------------------------

    /**
     * Seeds a chunk in workspace A as superuser (bypasses RLS), then verifies
     * that a restricted role with app.mcp_client_id = keyA sees only workspace-A rows.
     */
    @Test
    void rlsEnforcement_workspaceAKeySeesOnlyWorkspaceARows() {
        // Seed one chunk in workspace A and one in workspace B (superuser bypass)
        seedChunk("rls-test-doc-a", "INTERNAL", workspaceA);
        seedChunk("rls-test-doc-b", "INTERNAL", workspaceB);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Integer count = tx.execute(status -> {
            // Impersonate restricted role with workspace A context
            jdbc.execute("SET LOCAL ROLE rls_test_role");
            setConfig("app.mcp_client_id", apiKeyIdA.toString());
            setConfig("app.mcp_role", "READ_ONLY");

            return jdbc.queryForObject(
                    "SELECT count(*) FROM document_chunks WHERE doc_name LIKE 'rls-test-doc-%'",
                    Integer.class);
        });

        // Only workspace A's chunk should be visible
        assertThat(count).isEqualTo(1);
    }

    /**
     * Verifies that an empty/nil app.mcp_client_id causes RESTRICTIVE policy to
     * resolve to the nil UUID, which has no workspace rows → 0 rows visible.
     */
    @Test
    void rlsEnforcement_noClientIdMeansZeroRows() {
        seedChunk("rls-test-doc-a", "INTERNAL", workspaceA);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Integer count = tx.execute(status -> {
            jdbc.execute("SET LOCAL ROLE rls_test_role");
            // Empty string → app_setting() returns NULL → COALESCE → nil UUID → 0 rows
            setConfig("app.mcp_client_id", "");
            setConfig("app.mcp_role", "READ_ONLY");

            return jdbc.queryForObject(
                    "SELECT count(*) FROM document_chunks WHERE doc_name = 'rls-test-doc-a'",
                    Integer.class);
        });

        assertThat(count).isEqualTo(0);
    }

    /**
     * Verifies RESTRICTIVE WITH CHECK: attempting to INSERT a chunk with workspace B's
     * ID while app.mcp_client_id is workspace A's key must fail.
     */
    @Test
    void rlsEnforcement_insertIntoWrongWorkspaceFails() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        assertThatThrownBy(() ->
                tx.execute(status -> {
                    jdbc.execute("SET LOCAL ROLE rls_test_role");
                    setConfig("app.mcp_client_id", apiKeyIdA.toString());
                    setConfig("app.mcp_role", "ADMIN");

                    // Try to INSERT a chunk into workspace B while authenticated as key A
                    jdbc.update(
                            """
                            INSERT INTO document_chunks
                                (doc_name, classification, minio_key, chunk_index, workspace_id)
                            VALUES (?, 'INTERNAL', 'rls-test/cross-workspace', 0, ?)
                            """,
                            "rls-test-cross-workspace-attempt", workspaceB);
                    return null;
                })
        ).isInstanceOf(DataAccessException.class);
    }

    /**
     * Workspace B key should see workspace B's rows but not workspace A's rows.
     */
    @Test
    void rlsEnforcement_workspaceBKeySeesOnlyWorkspaceBRows() {
        seedChunk("rls-test-doc-a", "INTERNAL", workspaceA);
        seedChunk("rls-test-doc-b", "INTERNAL", workspaceB);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Integer count = tx.execute(status -> {
            jdbc.execute("SET LOCAL ROLE rls_test_role");
            setConfig("app.mcp_client_id", apiKeyIdB.toString());
            setConfig("app.mcp_role", "READ_ONLY");

            return jdbc.queryForObject(
                    "SELECT count(*) FROM document_chunks WHERE doc_name LIKE 'rls-test-doc-%'",
                    Integer.class);
        });

        assertThat(count).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Calls {@code set_config(key, value, true)} via PreparedStatementCallback.
     * {@code set_config} is a SELECT-like function; {@code JdbcTemplate.update()} would
     * throw "A result was returned when none was expected."
     */
    private void setConfig(String key, String value) {
        jdbc.execute(
                "SELECT set_config(?, ?, true)",
                (PreparedStatementCallback<Void>) ps -> {
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.execute();
                    return null;
                });
    }

    private UUID seedChunk(String docName, String classification, UUID workspaceId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO document_chunks
                    (id, doc_name, classification, minio_key, chunk_index, workspace_id)
                VALUES (?, ?, ?, ?, 0, ?)
                """,
                id, docName, classification, "rls-test/" + id, workspaceId);
        return id;
    }
}
