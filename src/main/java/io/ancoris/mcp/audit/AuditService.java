package io.ancoris.mcp.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final MeterRegistry meterRegistry;

    public AuditService(AuditLogRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * SEC-022: named executor with CallerRunsPolicy — entries are never silently dropped.
     * SEC-014: increments mcp.tool.calls counter per tool invocation.
     */
    @Async("auditExecutor")
    public void log(String toolName, UUID apiKeyId, Map<String, Object> params, String resultSummary) {
        meterRegistry.counter("mcp.tool.calls", "tool", toolName).increment();
        var entry = new AuditLog(toolName, apiKeyId, params, resultSummary);
        repository.save(entry);
        log.info("[AUDIT] tool={} apiKeyId={} summary={}", toolName, apiKeyId, resultSummary);
    }
}
