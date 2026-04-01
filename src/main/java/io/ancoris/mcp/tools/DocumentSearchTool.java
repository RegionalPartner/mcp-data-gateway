package io.ancoris.mcp.tools;

import io.ancoris.mcp.audit.AuditService;
import io.ancoris.mcp.connector.MinioConnector;
import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import io.ancoris.mcp.model.DataFragment;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DocumentSearchTool {

    private final JdbcTemplate jdbc;
    private final MinioConnector minioConnector;
    private final AuditService auditService;

    public DocumentSearchTool(JdbcTemplate jdbc, MinioConnector minioConnector, AuditService auditService) {
        this.jdbc = jdbc;
        this.minioConnector = minioConnector;
        this.auditService = auditService;
    }

    @Tool(description = "Search internal documents. Returns text fragments only — raw documents are never exposed.")
    public List<DataFragment> searchDocuments(
            @ToolParam(description = "Natural language search query", required = true) String query,
            @ToolParam(description = "Maximum number of fragments to return (1-10)") Integer maxResults) {

        ApiKey apiKey = currentApiKey();
        AccessRole role = apiKey.getRole();
        int limit = Math.min(maxResults != null ? maxResults : 5, 10);

        List<String> allowedClassifications = role.canAccessConfidential()
                ? List.of("'PUBLIC'", "'INTERNAL'", "'CONFIDENTIAL'")
                : List.of("'PUBLIC'", "'INTERNAL'");

        String inClause = String.join(", ", allowedClassifications);
        String sql = """
                SELECT id, doc_name, classification, minio_key, chunk_index
                FROM document_chunks
                WHERE classification IN (%s)
                  AND to_tsvector('french', coalesce(text_preview, '')) @@ plainto_tsquery('french', ?)
                LIMIT ?
                """.formatted(inClause);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, query, limit);
        List<DataFragment> fragments = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String minioKey = (String) row.get("minio_key");
            String text = minioConnector.fetchChunk(minioKey);
            fragments.add(new DataFragment(
                    row.get("id").toString(),
                    (String) row.get("doc_name"),
                    (String) row.get("classification"),
                    text,
                    (int) row.get("chunk_index")
            ));
        }

        auditService.log("search_documents", apiKey.getId(),
                Map.of("query", query, "maxResults", limit),
                "returned " + fragments.size() + " fragments");

        return fragments;
    }

    private ApiKey currentApiKey() {
        return (ApiKey) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
