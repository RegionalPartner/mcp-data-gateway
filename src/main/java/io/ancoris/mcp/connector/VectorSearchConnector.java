package io.ancoris.mcp.connector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Executes pgvector cosine-similarity queries against document_chunks.
 *
 * The query vector is formatted as a PostgreSQL array literal "[f1,f2,...]" and
 * cast to the vector type with ::vector — no extra JDBC type library required.
 * Classification values are controlled constants (not user input) so embedding
 * them in the IN clause is safe.
 */
@Component
public class VectorSearchConnector {

    private final JdbcTemplate jdbc;

    public VectorSearchConnector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns rows from document_chunks ordered by cosine distance to {@code queryVector}.
     *
     * @param queryVector           768-dim embedding of the search query.
     * @param allowedClassifications pre-quoted SQL literals, e.g. {@code "'PUBLIC'", "'INTERNAL'"}.
     * @param limit                 maximum number of rows to return.
     */
    public List<Map<String, Object>> search(
            float[] queryVector,
            List<String> allowedClassifications,
            int limit) {

        String inClause = String.join(", ", allowedClassifications);
        String sql = """
                SELECT id, doc_name, classification, chunk_index
                FROM document_chunks
                WHERE classification IN (%s)
                  AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(inClause);

        return jdbc.queryForList(sql, new Object[]{formatVector(queryVector), limit});
    }

    /**
     * Formats a float array as a pgvector literal: {@code [f1,f2,...]}.
     */
    private static String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
