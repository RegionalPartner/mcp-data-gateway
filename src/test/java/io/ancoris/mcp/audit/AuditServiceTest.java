package io.ancoris.mcp.audit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository repository;

    // Use a real SimpleMeterRegistry so counters actually accumulate
    private MeterRegistry meterRegistry;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        auditService = new AuditService(repository, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // log() must persist an AuditLog entry via the repository
    // -----------------------------------------------------------------------

    @Test
    void log_savesEntryToRepository() {
        UUID keyId = UUID.randomUUID();
        Map<String, Object> params = Map.of("table", "employees");

        auditService.log("query_database", keyId, params, "5 rows");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getToolName()).isEqualTo("query_database");
        assertThat(saved.getApiKeyId()).isEqualTo(keyId);
        assertThat(saved.getResultSummary()).isEqualTo("5 rows");
    }

    // -----------------------------------------------------------------------
    // log() must increment the mcp.tool.calls counter with the tool tag
    // -----------------------------------------------------------------------

    @Test
    void log_incrementsToolCallCounter() {
        auditService.log("list_sources", UUID.randomUUID(), Map.of(), "ok");
        auditService.log("list_sources", UUID.randomUUID(), Map.of(), "ok");
        auditService.log("search_documents", UUID.randomUUID(), Map.of(), "3 fragments");

        assertThat(meterRegistry.counter("mcp.tool.calls", "tool", "list_sources").count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.counter("mcp.tool.calls", "tool", "search_documents").count())
                .isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // log() with null apiKeyId (auth-failure path) must still save
    // -----------------------------------------------------------------------

    @Test
    void log_nullApiKeyId_savesEntry() {
        auditService.log("authentication_failure", null, Map.of("reason", "missing_key"), "rejected");

        verify(repository).save(any(AuditLog.class));
    }
}
