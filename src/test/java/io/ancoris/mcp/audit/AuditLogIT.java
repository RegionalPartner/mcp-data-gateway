package io.ancoris.mcp.audit;

import io.ancoris.mcp.integration.AbstractIntegrationTest;
import io.ancoris.mcp.integration.TestSecurityHelper;
import io.ancoris.mcp.security.ApiKeyRepository;
import io.ancoris.mcp.tools.DatabaseQueryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for audit log correctness and immutability (SEC-020, SEC-022).
 */
class AuditLogIT extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private DatabaseQueryTool databaseQueryTool;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private TestSecurityHelper secHelper;

    @AfterEach
    void tearDown() {
        secHelper.clearAuthentication();
    }

    // -----------------------------------------------------------------------
    // A tool call must create an audit entry with the correct api_key_id
    // -----------------------------------------------------------------------

    @Test
    void toolCall_createsAuditEntryWithCorrectApiKeyId() {
        secHelper.authenticateAs("demo-readonly-key-001", apiKeyRepository);

        // Retrieve the API key ID that was set in the security context
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        io.ancoris.mcp.model.ApiKey key = (io.ancoris.mcp.model.ApiKey) auth.getPrincipal();
        UUID expectedKeyId = key.getId();

        long countBefore = auditLogRepository.count();
        databaseQueryTool.queryDatabase("employees", null, null);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            List<AuditLog> newEntries = auditLogRepository.findAll().stream()
                    .filter(e -> "query_database".equals(e.getToolName()))
                    .filter(e -> expectedKeyId.equals(e.getApiKeyId()))
                    .toList();
            assertThat(newEntries).hasSizeGreaterThan(0);
        });
        assertThat(auditLogRepository.count()).isGreaterThan(countBefore);
    }

    // -----------------------------------------------------------------------
    // SEC-020: the append-only trigger must prevent DELETE on audit_logs
    // -----------------------------------------------------------------------

    @Test
    void trigger_preventsDeleteOnAuditLogs() {
        // Insert a legitimate entry to get a real row id
        AuditLog entry = new AuditLog("test_tool", null, Map.of("test", "trigger"), "test");
        AuditLog saved = auditLogRepository.save(entry);

        await().atMost(Duration.ofSeconds(2)).until(() -> auditLogRepository.existsById(saved.getId()));

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM audit_logs WHERE id = ?", saved.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    // -----------------------------------------------------------------------
    // SEC-020: the append-only trigger must prevent UPDATE on audit_logs
    // -----------------------------------------------------------------------

    @Test
    void trigger_preventsUpdateOnAuditLogs() {
        AuditLog entry = new AuditLog("test_tool", null, Map.of("test", "update-trigger"), "original");
        AuditLog saved = auditLogRepository.save(entry);

        await().atMost(Duration.ofSeconds(2)).until(() -> auditLogRepository.existsById(saved.getId()));

        assertThatThrownBy(() ->
                jdbc.update("UPDATE audit_logs SET result_summary = 'tampered' WHERE id = ?",
                        saved.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    // -----------------------------------------------------------------------
    // SEC-AUDIT2: the file-based audit sink must receive events
    // -----------------------------------------------------------------------

    @Test
    void auditFileSink_receivesEventForToolCall() throws IOException {
        String uniqueSummary = "file-sink-test-" + UUID.randomUUID();

        auditService.log("list_sources", null, Map.of(), uniqueSummary);

        // The file appender writes to ${java.io.tmpdir}/mcp-audit-test.json (test profile)
        Path auditFile = Path.of(System.getProperty("java.io.tmpdir"), "mcp-audit-test.json");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(auditFile).exists();
            String content = Files.readString(auditFile);
            // Each JSON line contains a "mdc" object with our fields
            assertThat(content).contains(uniqueSummary);
            assertThat(content).contains("\"tool_name\":\"list_sources\"");
        });
    }

    // -----------------------------------------------------------------------
    // Authentication failure must also be audited
    // -----------------------------------------------------------------------

    @Test
    void authenticationFailure_isAudited() {
        long countBefore = auditLogRepository.count();

        auditService.log("authentication_failure", null,
                Map.of("reason", "missing_key", "ip", "127.0.0.1"), "rejected");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            boolean hasEntry = auditLogRepository.findAll().stream()
                    .anyMatch(e -> "authentication_failure".equals(e.getToolName()));
            assertThat(hasEntry).isTrue();
            assertThat(auditLogRepository.count()).isGreaterThan(countBefore);
        });
    }
}
