-- RAG-003: Add write policies to document_chunks.
--
-- V5 enabled FORCE ROW LEVEL SECURITY with a FOR SELECT policy only.
-- PostgreSQL FORCE RLS applies to table owners too, so any INSERT or UPDATE
-- issued by mcpuser (including EmbeddingInitializer and the ingestion pipeline)
-- silently affects 0 rows because no matching write policy exists.
--
-- Fix: add PERMISSIVE INSERT and UPDATE policies that allow all rows.
-- The classification filter in the existing SELECT policy is unaffected —
-- PERMISSIVE policies for different commands are evaluated independently.

CREATE POLICY doc_chunks_insert_policy ON document_chunks
    AS PERMISSIVE
    FOR INSERT
    TO PUBLIC
    WITH CHECK (true);

CREATE POLICY doc_chunks_update_policy ON document_chunks
    AS PERMISSIVE
    FOR UPDATE
    TO PUBLIC
    USING (true)
    WITH CHECK (true);
