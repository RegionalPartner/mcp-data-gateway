-- INGEST-001: Persistence table for Microsoft Graph delta cursors.
--
-- The ingestion daemon (ingestion/) polls SharePoint via the Graph delta API and
-- stores the @odata.deltaLink URL here after each successful cycle.  On restart
-- it resumes from the saved cursor instead of re-ingesting the full drive.
--
-- Also adds source_item_id to document_chunks so the daemon can locate and
-- atomically replace all chunks for a modified or deleted SharePoint file.
--
-- RLS note: no RLS needed on ingestion_state (not exposed to the MCP query path).

CREATE TABLE ingestion_state (
    drive_id    VARCHAR(255) PRIMARY KEY,
    delta_token TEXT         NOT NULL,   -- full @odata.deltaLink URL returned by Graph
    last_synced TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Track which SharePoint item each chunk originated from.
-- Allows DELETE + re-INSERT when a file is modified, without a full-drive rescan.
ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS source_item_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_chunk_source_item_id
    ON document_chunks (source_item_id)
    WHERE source_item_id IS NOT NULL;

-- Allow the ingestion daemon (mcpuser) to delete chunks for modified/deleted items.
-- V5 set FORCE ROW LEVEL SECURITY; V9 added INSERT + UPDATE policies; DELETE was missing.
CREATE POLICY doc_chunks_delete_policy ON document_chunks
    AS PERMISSIVE
    FOR DELETE
    TO PUBLIC
    USING (true);
