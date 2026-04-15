package io.ancoris.mcp.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * SEC-AUDIT2: second immutable audit sink, independent of PostgreSQL.
     * Routes to AUDIT_FILE appender (logback-spring.xml) which writes structured
     * NDJSON to /var/log/mcp/audit.json. Post-deployment hardening:
     *   sudo chattr +a /var/log/mcp/audit.json
     * A PostgreSQL superuser can DISABLE TRIGGER ALL on audit_logs; they cannot
     * remove entries already written to an append-only file.
     */
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    private final AuditLogRepository repository;
    private final MeterRegistry meterRegistry;

    public AuditService(AuditLogRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * SEC-022: synchronous write — audit entry is flushed to DB before the calling
     * method returns, eliminating the JVM-crash data-loss window of the previous
     * @Async approach. The write participates in the caller's transaction (managed
     * by RlsContextAspect) and commits with it.
     * SEC-014: increments mcp.tool.calls counter per tool invocation.
     */
    public void log(String toolName, UUID apiKeyId, Map<String, Object> params, String resultSummary) {
        meterRegistry.counter("mcp.tool.calls", "tool", toolName).increment();
        var entry = new AuditLog(toolName, apiKeyId, params, resultSummary);
        repository.save(entry);
        log.info("[AUDIT] tool={} apiKeyId={} summary={}", toolName, apiKeyId, resultSummary);

        // Write to the file-based sink via MDC so fields appear in the JSON "mdc" object.
        // Queryable with: jq .mdc.tool_name /var/log/mcp/audit.json
        MDC.put("tool_name", toolName);
        MDC.put("api_key_id", apiKeyId != null ? apiKeyId.toString() : "");
        MDC.put("result_summary", resultSummary != null ? resultSummary : "");
        try {
            auditLog.info("audit_event");
        } finally {
            MDC.clear();
        }
    }
}
