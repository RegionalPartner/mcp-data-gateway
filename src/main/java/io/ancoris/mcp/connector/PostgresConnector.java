package io.ancoris.mcp.connector;

import io.ancoris.mcp.model.AccessRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PostgresConnector {

    private static final Set<String> ALLOWED_TABLES = Set.of("employees", "document_chunks");

    // Columns visible per table — defines the strict allowlist
    private static final Map<String, List<String>> ALL_COLUMNS = Map.of(
            "employees",       List.of("id", "name", "department", "email", "salary"),
            "document_chunks", List.of("id", "doc_name", "classification", "chunk_index", "text_preview", "created_at", "workspace_id")
    );

    // Columns hidden from READ_ONLY role
    private static final Map<String, Set<String>> ROLE_HIDDEN_COLUMNS = Map.of(
            "employees", Set.of("salary")
    );

    private final JdbcTemplate jdbc;

    public PostgresConnector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * SEC-009: maxRows bounds every query to prevent full-table exfiltration and
     * context-window exhaustion on the consuming AI model.
     */
    public List<Map<String, Object>> query(String table, Map<String, String> filters,
                                           AccessRole role, int maxRows) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new SecurityException("Access denied");  // SEC-012
        }

        List<String> allowedCols = buildColumnList(table, role);
        String cols = String.join(", ", allowedCols);

        if (filters == null || filters.isEmpty()) {
            return jdbc.queryForList("SELECT " + cols + " FROM " + table + " LIMIT ?", maxRows);
        }

        // Build parameterized WHERE clause — column names validated against allowlist
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String col = entry.getKey();
            if (!allowedCols.contains(col)) {
                throw new SecurityException("Access denied");  // SEC-012
            }
            conditions.add(col + " = ?");
            args.add(entry.getValue());
        }
        args.add(maxRows);

        String sql = "SELECT " + cols + " FROM " + table
                + " WHERE " + String.join(" AND ", conditions)
                + " LIMIT ?";
        return jdbc.queryForList(sql, args.toArray());
    }

    private List<String> buildColumnList(String table, AccessRole role) {
        List<String> cols = new ArrayList<>(ALL_COLUMNS.getOrDefault(table, List.of()));
        if (role == AccessRole.READ_ONLY) {
            Set<String> hidden = ROLE_HIDDEN_COLUMNS.getOrDefault(table, Set.of());
            cols.removeAll(hidden);
        }
        return cols;
    }

    public List<String> getVisibleColumns(String table, AccessRole role) {
        return buildColumnList(table, role);
    }
}
