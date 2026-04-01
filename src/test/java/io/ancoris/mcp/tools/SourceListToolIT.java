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
import static org.awaitility.Awaitility.await;

class SourceListToolIT extends AbstractIntegrationTest {

    @Autowired
    SourceListTool sourceListTool;

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
    // READ_ONLY: accessible_classifications must not include CONFIDENTIAL
    // -----------------------------------------------------------------------

    @Test
    void listSources_asReadOnly_doesNotExposeConfidentialClassification() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        Map<String, Object> result = sourceListTool.listSources();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");

        sources.stream()
                .filter(s -> "object-storage".equals(s.get("type")))
                .forEach(s -> {
                    @SuppressWarnings("unchecked")
                    List<String> classifications =
                            (List<String>) s.get("accessible_classifications");
                    assertThat(classifications).doesNotContain("CONFIDENTIAL");
                });
    }

    // -----------------------------------------------------------------------
    // ADMIN: accessible_classifications must include CONFIDENTIAL
    // -----------------------------------------------------------------------

    @Test
    void listSources_asAdmin_includesConfidentialClassification() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        Map<String, Object> result = sourceListTool.listSources();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");

        sources.stream()
                .filter(s -> "object-storage".equals(s.get("type")))
                .forEach(s -> {
                    @SuppressWarnings("unchecked")
                    List<String> classifications =
                            (List<String>) s.get("accessible_classifications");
                    assertThat(classifications).contains("CONFIDENTIAL");
                });
    }

    // -----------------------------------------------------------------------
    // READ_ONLY: employees columns must not include salary
    // -----------------------------------------------------------------------

    @Test
    void listSources_asReadOnly_employeesColumnsExcludeSalary() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        Map<String, Object> result = sourceListTool.listSources();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");

        sources.stream()
                .filter(s -> "employees".equals(s.get("name")))
                .forEach(s -> {
                    @SuppressWarnings("unchecked")
                    List<String> columns = (List<String>) s.get("columns");
                    assertThat(columns).doesNotContain("salary");
                });
    }

    // -----------------------------------------------------------------------
    // ADMIN: employees columns must include salary
    // -----------------------------------------------------------------------

    @Test
    void listSources_asAdmin_employeesColumnsIncludeSalary() {
        secHelper.authenticateAs("demo-admin-key-001", apiKeyRepository);

        Map<String, Object> result = sourceListTool.listSources();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");

        sources.stream()
                .filter(s -> "employees".equals(s.get("name")))
                .forEach(s -> {
                    @SuppressWarnings("unchecked")
                    List<String> columns = (List<String>) s.get("columns");
                    assertThat(columns).contains("salary");
                });
    }

    // -----------------------------------------------------------------------
    // Audit log entry created after list_sources call
    // -----------------------------------------------------------------------

    @Test
    void listSources_createsAuditLogEntry() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        long countBefore = auditLogRepository.count();
        sourceListTool.listSources();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            long countAfter = auditLogRepository.count();
            assertThat(countAfter).isGreaterThan(countBefore);

            boolean hasListSourcesEntry = auditLogRepository.findAll().stream()
                    .anyMatch(entry -> "list_sources".equals(entry.getToolName()));
            assertThat(hasListSourcesEntry).isTrue();
        });
    }
}
