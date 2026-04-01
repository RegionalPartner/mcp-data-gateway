package io.ancoris.mcp.audit;

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

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Async
    public void log(String toolName, UUID apiKeyId, Map<String, Object> params, String resultSummary) {
        var entry = new AuditLog(toolName, apiKeyId, params, resultSummary);
        repository.save(entry);
        log.info("[AUDIT] tool={} apiKeyId={} summary={}", toolName, apiKeyId, resultSummary);
    }
}
