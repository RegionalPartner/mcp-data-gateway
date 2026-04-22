-- Add workspace_id to document_chunks.
-- The DEFAULT back-fills all existing rows into the 'default' workspace
-- so existing data and tests remain green without a bulk UPDATE.

ALTER TABLE document_chunks
    ADD COLUMN workspace_id UUID NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES workspaces(id);

CREATE INDEX idx_chunk_workspace ON document_chunks (workspace_id);
