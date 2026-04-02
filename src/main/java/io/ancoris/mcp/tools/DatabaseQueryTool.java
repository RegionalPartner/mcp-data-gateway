package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.connector.PostgresConnector;
import io.ancoris.mcp.model.ApiKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseQueryTool {

    private static final int DEFAULT_MAX_ROWS = 100;
    private static final int HARD_MAX_ROWS = 500;

    private final PostgresConnector postgresConnector;
    private final AuditService auditService;
    private final Counter authzDenialCounter;

    public DatabaseQueryTool(PostgresConnector postgresConnector, AuditService auditService,
                             MeterRegistry meterRegistry) {
        this.postgresConnector = postgresConnector;
        this.auditService = auditService;
        // SEC-014: counter for authorisation denial events
        this.authzDenialCounter = Counter.builder("mcp.authz.denials")
                .description("Count of authorisation denial events in query_database")
                .tag("tool", "query_database")
                .register(meterRegistry);
    }

    @Tool(description = "Query structured data from internal tables with role-based column filtering. "
            + "Available tables: employees, document_chunks. "
            + "Default limit is 100 rows; maximum is 500.")
    public List<Map<String, Object>> queryDatabase(
            @ToolParam(description = "Table name: 'employees' or 'document_chunks'", required = true)
                    String table,
            @ToolParam(description = "Optional filters as key-value pairs, e.g. department=IT")
                    Map<String, String> filters,
            @ToolParam(description = "Maximum number of rows to return (1-500, default 100)")
                    Integer maxRows) {

        ApiKey apiKey = currentApiKey();
        int limit = maxRows != null
                ? Math.min(Math.max(maxRows, 1), HARD_MAX_ROWS)
                : DEFAULT_MAX_ROWS;

        List<Map<String, Object>> results;
        try {
            results = postgresConnector.query(table, filters, apiKey.getRole(), limit);
        } catch (SecurityException ex) {
            // SEC-008: log every authorisation denial to the audit trail
            authzDenialCounter.increment();
            auditService.log("authz_denial", apiKey.getId(),
                    Map.of("table", table, "filters", filters != null ? filters : Map.of()),
                    "DENIED: " + table);
            throw ex;
        }

        auditService.log("query_database", apiKey.getId(),
                Map.of("table", table, "filters", filters != null ? filters : Map.of()),
                "returned " + results.size() + " rows from " + table);

        return results;
    }

    private ApiKey currentApiKey() {
        return (ApiKey) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
