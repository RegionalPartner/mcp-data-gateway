package io.ancoris.mcp.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", columnDefinition = "jsonb")
    private Map<String, Object> paramsJson;

    @Column(name = "result_summary")
    private String resultSummary;

    @Column(nullable = false)
    private Instant timestamp;

    @PrePersist
    protected void onCreate() {
        this.timestamp = Instant.now();
    }

    public AuditLog() {}

    public AuditLog(String toolName, UUID apiKeyId, Map<String, Object> paramsJson, String resultSummary) {
        this.toolName = toolName;
        this.apiKeyId = apiKeyId;
        this.paramsJson = paramsJson;
        this.resultSummary = resultSummary;
    }

    public UUID getId() { return id; }
    public String getToolName() { return toolName; }
    public UUID getApiKeyId() { return apiKeyId; }
    public Map<String, Object> getParamsJson() { return paramsJson; }
    public String getResultSummary() { return resultSummary; }
    public Instant getTimestamp() { return timestamp; }
}
