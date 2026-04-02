package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditLogRepository;
import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.integration.TestSecurityHelper;
import io.ancoris.mcp.security.ApiKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DatabaseQueryToolIT extends AbstractIntegrationTest {

    @Autowired
    DatabaseQueryTool databaseQueryTool;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    TestSecurityHelper secHelper;

    @AfterEach
    void tearDown() {
        secHelper.clearAuthentication();
    }

    // -----------------------------------------------------------------------
    // READ_ONLY: salary column must be absent
    // -----------------------------------------------------------------------

    @Test
    void queryEmployees_asReadOnly_returnsRowsWithoutSalary() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<Map<String, Object>> rows = databaseQueryTool.queryDatabase("employees", null, null);

        assertThat(rows).isNotEmpty();
        rows.forEach(row -> assertThat(row).doesNotContainKey("salary"));
    }

    // -----------------------------------------------------------------------
    // ADMIN: salary column must be present
    // -----------------------------------------------------------------------

    @Test
    void queryEmployees_asAdmin_returnsSalaryColumn() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        List<Map<String, Object>> rows = databaseQueryTool.queryDatabase("employees", null, null);

        assertThat(rows).isNotEmpty();
        rows.forEach(row -> assertThat(row).containsKey("salary"));
    }

    // -----------------------------------------------------------------------
    // Department filter: only Bob and David (IT)
    // -----------------------------------------------------------------------

    @Test
    void queryEmployees_withDepartmentFilter_returnsFilteredRows() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<Map<String, Object>> rows = databaseQueryTool.queryDatabase(
                "employees", Map.of("department", "IT"), null);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r.get("name"))
                .containsExactlyInAnyOrder("Bob Dupont", "David Leroy");
    }

    // -----------------------------------------------------------------------
    // document_chunks table: expected columns present
    // -----------------------------------------------------------------------

    @Test
    void queryDocumentChunks_returnsExpectedColumns() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        List<Map<String, Object>> rows = databaseQueryTool.queryDatabase("document_chunks", null, null);

        assertThat(rows).isNotEmpty();
        Map<String, Object> first = rows.get(0);
        assertThat(first).containsKeys("id", "doc_name", "classification", "chunk_index", "text_preview", "created_at");
    }

    // -----------------------------------------------------------------------
    // Forbidden table: audit_logs not in allowlist → SecurityException
    // -----------------------------------------------------------------------

    @Test
    void queryForbiddenTable_throwsException() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        // SEC-012: message is generic "Access denied" — no table name leaked
        assertThatThrownBy(() -> databaseQueryTool.queryDatabase("audit_logs", null, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Access denied");
    }

    // -----------------------------------------------------------------------
    // SQL injection in table name → SecurityException (table not in allowlist)
    // -----------------------------------------------------------------------

    @Test
    void sqlInjectionInTableName_throwsException() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        assertThatThrownBy(() ->
                databaseQueryTool.queryDatabase("employees; DROP TABLE employees;--", null, null))
                .isInstanceOf(SecurityException.class);
    }

    // -----------------------------------------------------------------------
    // SQL injection in filter value: parameterized query protects the table
    // -----------------------------------------------------------------------

    @Test
    void sqlInjectionInFilterValue_doesNotDropTable() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        // The injected value is treated as a literal string — no rows match,
        // but the employees table survives intact.
        List<Map<String, Object>> injectionResult = databaseQueryTool.queryDatabase(
                "employees",
                Map.of("name", "'; DROP TABLE employees; --"), null);

        assertThat(injectionResult).isEmpty();

        // Verify the table is still queryable
        List<Map<String, Object>> rows = databaseQueryTool.queryDatabase("employees", null, null);
        assertThat(rows).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Audit log entry created after query
    // -----------------------------------------------------------------------

    @Test
    void queryDatabase_createsAuditLogEntry() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        long countBefore = auditLogRepository.count();
        databaseQueryTool.queryDatabase("employees", null, null);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            long countAfter = auditLogRepository.count();
            assertThat(countAfter).isGreaterThan(countBefore);

            boolean hasQueryDatabaseEntry = auditLogRepository.findAll().stream()
                    .anyMatch(entry -> "query_database".equals(entry.getToolName()));
            assertThat(hasQueryDatabaseEntry).isTrue();
        });
    }
}
