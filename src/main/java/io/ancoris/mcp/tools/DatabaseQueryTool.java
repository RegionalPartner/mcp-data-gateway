package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.connector.PostgresConnector;
import io.ancoris.mcp.model.ApiKey;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseQueryTool {

    private final PostgresConnector postgresConnector;
    private final AuditService auditService;

    public DatabaseQueryTool(PostgresConnector postgresConnector, AuditService auditService) {
        this.postgresConnector = postgresConnector;
        this.auditService = auditService;
    }

    @Tool(description = "Query structured data from internal tables with role-based column filtering. Available tables: employees, document_chunks.")
    public List<Map<String, Object>> queryDatabase(
            @ToolParam(description = "Table name: 'employees' or 'document_chunks'", required = true) String table,
            @ToolParam(description = "Optional filters as key-value pairs, e.g. department=IT") Map<String, String> filters) {

        ApiKey apiKey = currentApiKey();
        List<Map<String, Object>> results = postgresConnector.query(table, filters, apiKey.getRole());

        auditService.log("query_database", apiKey.getId(),
                Map.of("table", table, "filters", filters != null ? filters : Map.of()),
                "returned " + results.size() + " rows from " + table);

        return results;
    }

    private ApiKey currentApiKey() {
        return (ApiKey) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
