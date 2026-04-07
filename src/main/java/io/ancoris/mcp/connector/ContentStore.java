package io.ancoris.mcp.connector;

import java.util.UUID;

/**
 * Abstraction over the encrypted document chunk store (SEC-ENC).
 * The default implementation ({@link DbContentStore}) reads from the
 * {@code document_chunks.encrypted_content} column.
 */
public interface ContentStore {

    /**
     * Returns the decrypted text for the given chunk, or an empty string if
     * the chunk has no encrypted content or cannot be found.
     */
    String fetchChunk(UUID chunkId);
}
