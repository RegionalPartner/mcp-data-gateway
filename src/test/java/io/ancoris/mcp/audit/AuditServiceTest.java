package io.ancoris.mcp.audit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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

    // Logback ListAppender attached to the "AUDIT" logger for unit test assertions
    private ListAppender<ILoggingEvent> listAppender;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        auditService = new AuditService(repository, meterRegistry);

        // Attach a ListAppender to the "AUDIT" logger so tests can inspect events.
        // Note: @Async is not active in unit tests (no Spring context), so log() is synchronous.
        auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
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

    // -----------------------------------------------------------------------
    // SEC-AUDIT2: log() must emit an event to the AUDIT logger
    // -----------------------------------------------------------------------

    @Test
    void log_writesEventToAuditLogger() {
        UUID keyId = UUID.randomUUID();

        auditService.log("search_documents", keyId, Map.of("query", "budget"), "2 fragments");

        assertThat(listAppender.list).hasSize(1);
        ILoggingEvent event = listAppender.list.get(0);
        assertThat(event.getMessage()).isEqualTo("audit_event");
        assertThat(event.getMDCPropertyMap())
                .containsEntry("tool_name", "search_documents")
                .containsEntry("result_summary", "2 fragments")
                .containsEntry("api_key_id", keyId.toString());
    }

    // -----------------------------------------------------------------------
    // SEC-AUDIT2: null apiKeyId must write empty string, not "null", to MDC
    // -----------------------------------------------------------------------

    @Test
    void log_nullApiKeyId_writesEmptyStringToMdc() {
        auditService.log("authentication_failure", null, Map.of(), "rejected");

        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getMDCPropertyMap())
                .containsEntry("api_key_id", "");
    }
}
