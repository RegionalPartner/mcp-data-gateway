package io.ancoris.mcp.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfills missing embeddings for document_chunks rows added before RAG was introduced.
 *
 * Runs once after the application context is fully started (ApplicationReadyEvent).
 * Idempotent: only processes rows where embedding IS NULL.
 * If the embedding model returns null (e.g. Ollama not yet warmed up in tests),
 * the chunk is skipped silently — it can be retried on the next restart.
 */
@Component
public class EmbeddingInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingInitializer.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;

    public EmbeddingInitializer(JdbcTemplate jdbc, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
    }

    @Override
    @Transactional
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // Bypass RLS so all rows (including CONFIDENTIAL) are visible for backfill.
        // SET LOCAL scopes the role to this transaction only.
        jdbc.execute("SET LOCAL app.mcp_role = 'ADMIN'");
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT id, text_preview FROM document_chunks WHERE embedding IS NULL");

        if (pending.isEmpty()) {
            log.debug("EmbeddingInitializer: all chunks already have embeddings, nothing to do");
            return;
        }

        log.info("EmbeddingInitializer: backfilling embeddings for {} chunk(s)", pending.size());
        int succeeded = 0;
        for (Map<String, Object> row : pending) {
            UUID chunkId = (UUID) row.get("id");
            String preview = (String) row.get("text_preview");
            if (preview == null || preview.isBlank()) {
                log.debug("Chunk {} has no text_preview, skipping", chunkId);
                continue;
            }
            float[] vector = embeddingService.embed(preview);
            if (vector == null || vector.length == 0) {
                log.debug("Embedding model returned empty result for chunk {}, skipping", chunkId);
                continue;
            }
            jdbc.update("UPDATE document_chunks SET embedding = ?::vector WHERE id = ?",
                    formatVector(vector), chunkId);
            succeeded++;
        }
        log.info("EmbeddingInitializer: embedded {}/{} chunks", succeeded, pending.size());
    }

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
