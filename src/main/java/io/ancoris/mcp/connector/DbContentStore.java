package io.ancoris.mcp.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Reads encrypted document chunk content from PostgreSQL and decrypts it
 * using AES-256-GCM (SEC-ENC).
 *
 * The encrypted_content column is populated by the migration runner or a
 * one-time data migration from the previous MinIO-based store.
 */
@Component
public class DbContentStore implements ContentStore {

    private static final Logger log = LoggerFactory.getLogger(DbContentStore.class);
    private static final int MAX_FRAGMENT_CHARS = 500;

    private final JdbcTemplate jdbc;
    private final ContentEncryptor contentEncryptor;

    public DbContentStore(JdbcTemplate jdbc, ContentEncryptor contentEncryptor) {
        this.jdbc = jdbc;
        this.contentEncryptor = contentEncryptor;
    }

    @Override
    public String fetchChunk(UUID chunkId) {
        try {
            var row = jdbc.queryForMap(
                    "SELECT encrypted_content, text_preview FROM document_chunks WHERE id = ?", chunkId);
            byte[] encrypted = (byte[]) row.get("encrypted_content");
            if (encrypted == null) {
                // Fall back to text_preview for unencrypted demo/seed data
                log.debug("No encrypted_content for chunk id={}, falling back to text_preview", chunkId);
                String preview = (String) row.get("text_preview");
                return preview != null ? preview : "";
            }
            String text = contentEncryptor.decrypt(encrypted);
            return text.length() > MAX_FRAGMENT_CHARS ? text.substring(0, MAX_FRAGMENT_CHARS) : text;
        } catch (SecurityException e) {
            log.warn("Authentication tag mismatch for chunk id={} — content may be tampered", chunkId);
            return "";
        } catch (Exception e) {
            log.warn("Failed to fetch chunk id={}: {}", chunkId, e.getMessage());
            return "";
        }
    }
}
