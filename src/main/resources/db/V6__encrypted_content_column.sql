-- SEC-ENC: Add AES-256-GCM encrypted content column to document_chunks.
-- Replaces MinIO object storage (CVE-2023-28432 surface area eliminated).
--
-- Wire format stored in encrypted_content: [12B IV][ciphertext + 16B GCM tag]
-- Key source: MCP_CONTENT_KEY env var (64 hex chars = 32 bytes)
--
-- V7 (follow-up): DROP COLUMN minio_key after all rows migrated.

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS encrypted_content BYTEA;

ALTER TABLE document_chunks
    ALTER COLUMN minio_key DROP NOT NULL;

-- Partial index: quickly find rows still needing migration.
CREATE INDEX IF NOT EXISTS idx_chunk_needs_migration
    ON document_chunks (id)
    WHERE encrypted_content IS NULL AND minio_key IS NOT NULL;
