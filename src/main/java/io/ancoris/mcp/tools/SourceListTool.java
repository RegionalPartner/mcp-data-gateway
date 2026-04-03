package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.connector.PostgresConnector;
import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class SourceListTool {

    private final PostgresConnector postgresConnector;
    private final AuditService auditService;

    public SourceListTool(PostgresConnector postgresConnector, AuditService auditService) {
        this.postgresConnector = postgresConnector;
        this.auditService = auditService;
    }

    @Tool(name = "list_sources", description = "List data sources and tables accessible with the current API key, including visible columns and document classifications.")
    public Map<String, Object> listSources() {
        ApiKey apiKey = currentApiKey();
        AccessRole role = apiKey.getRole();

        List<Map<String, Object>> sources = List.of(
                Map.of(
                        "name", "employees",
                        "type", "structured",
                        "columns", postgresConnector.getVisibleColumns("employees", role)
                ),
                Map.of(
                        "name", "document_chunks",
                        "type", "structured",
                        "columns", postgresConnector.getVisibleColumns("document_chunks", role)
                ),
                Map.of(
                        "name", "documents (MinIO)",
                        "type", "object-storage",
                        "accessible_classifications", role.canAccessConfidential()
                                ? List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL")
                                : List.of("PUBLIC", "INTERNAL")
                )
        );

        auditService.log("list_sources", apiKey.getId(), Map.of(),
                "role=" + role.name() + ", listed " + sources.size() + " sources");

        return Map.of("role", role.name(), "sources", sources);
    }

    private ApiKey currentApiKey() {
        return (ApiKey) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
