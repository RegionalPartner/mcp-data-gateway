-- V14: RLS correctness fix — split V13's compound PERMISSIVE policy into
-- PERMISSIVE (classification gate) + RESTRICTIVE (workspace isolation gate).
--
-- Problem: V13's single PERMISSIVE policy combined both classification and
-- workspace gates in one USING clause.  V9's write policies used WITH CHECK true,
-- meaning INSERT/UPDATE were NOT workspace-isolated at the database layer.
--
-- Fix: PostgreSQL RESTRICTIVE policies form an AND layer *above* all PERMISSIVE
-- policies.  By placing the workspace_id check in a RESTRICTIVE policy and
-- applying it FOR ALL (SELECT + INSERT + UPDATE + DELETE), every write is now
-- subject to the same workspace enforcement as reads.
--
-- Security invariants preserved:
--   - Unauthenticated / unset app.mcp_client_id → nil UUID → 0 workspace rows
--     → RESTRICTIVE fails → 0 rows visible/writable (fail-closed)
--   - ADMIN role bypass remains only in the PERMISSIVE classification gate;
--     the RESTRICTIVE workspace gate applies to ALL including ADMIN
--     (intended — even ADMIN cannot cross workspace boundaries)

-- -------------------------------------------------------------------------
-- 1. app_setting() STABLE helper — avoids repeated current_setting() calls
--    and normalises empty-string to NULL (so the nil-UUID fallback activates).
--    LEAKPROOF is attempted opportunistically; skipped if not superuser.
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_setting(key text) RETURNS text
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$
SELECT NULLIF(current_setting(key, true), '')
$$;

DO
$$
    BEGIN
        BEGIN
            EXECUTE 'ALTER FUNCTION app_setting(text) LEAKPROOF';
        EXCEPTION
            WHEN insufficient_privilege THEN
                RAISE NOTICE 'skipping LEAKPROOF on app_setting — insufficient privilege';
        END;
    END
$$;

-- -------------------------------------------------------------------------
-- 2. Replace V13 compound PERMISSIVE with PERMISSIVE (classification) only
-- -------------------------------------------------------------------------
DROP POLICY IF EXISTS doc_chunks_segmentation_policy ON document_chunks;

-- PERMISSIVE: classification gate.
-- ADMIN sees everything; non-ADMIN skips CONFIDENTIAL rows.
CREATE POLICY doc_chunks_classification ON document_chunks
    AS PERMISSIVE
    FOR SELECT
    TO PUBLIC
    USING (
        COALESCE(app_setting('app.mcp_role'), 'READ_ONLY') = 'ADMIN'
            OR classification != 'CONFIDENTIAL'
        );

-- RESTRICTIVE: workspace isolation gate.
-- Applies FOR ALL (SELECT, INSERT, UPDATE, DELETE).
-- Must ALSO pass for any row to be visible or writable.
-- Fail-closed: nil UUID has no api_key_workspaces rows → empty set → 0 rows.
CREATE POLICY doc_chunks_workspace_isolation ON document_chunks
    AS RESTRICTIVE
    FOR ALL
    TO PUBLIC
    USING (
        workspace_id IN (
            SELECT akw.workspace_id
            FROM api_key_workspaces akw
            WHERE akw.api_key_id =
                  COALESCE(app_setting('app.mcp_client_id'),
                           '00000000-0000-0000-0000-000000000000')::uuid
        )
    )
    WITH CHECK (
        workspace_id IN (
            SELECT akw.workspace_id
            FROM api_key_workspaces akw
            WHERE akw.api_key_id =
                  COALESCE(app_setting('app.mcp_client_id'),
                           '00000000-0000-0000-0000-000000000000')::uuid
        )
    );

-- -------------------------------------------------------------------------
-- 3. Tighten write policies (was WITH CHECK true — no workspace enforcement)
--    The RESTRICTIVE policy above now covers the workspace gate;
--    these PERMISSIVE policies handle the classification gate on writes.
-- -------------------------------------------------------------------------
DROP POLICY IF EXISTS doc_chunks_insert_policy ON document_chunks;
DROP POLICY IF EXISTS doc_chunks_update_policy ON document_chunks;

CREATE POLICY doc_chunks_insert_policy ON document_chunks
    AS PERMISSIVE
    FOR INSERT
    TO PUBLIC
    WITH CHECK (
        classification != 'CONFIDENTIAL'
            OR COALESCE(app_setting('app.mcp_role'), 'READ_ONLY') = 'ADMIN'
        );

CREATE POLICY doc_chunks_update_policy ON document_chunks
    AS PERMISSIVE
    FOR UPDATE
    TO PUBLIC
    USING (
        classification != 'CONFIDENTIAL'
            OR COALESCE(app_setting('app.mcp_role'), 'READ_ONLY') = 'ADMIN'
        );

-- -------------------------------------------------------------------------
-- 4. ingestion_state schema evolution for multi-source / multi-workspace
-- -------------------------------------------------------------------------

-- Add workspace affinity to each ingestion cursor row.
-- Default → default workspace (00…01) so existing Graph cursors keep working.
ALTER TABLE ingestion_state
    ADD COLUMN IF NOT EXISTS workspace_id UUID
        REFERENCES workspaces (id) ON DELETE CASCADE;

UPDATE ingestion_state
SET workspace_id = '00000000-0000-0000-0000-000000000001'
WHERE workspace_id IS NULL;

ALTER TABLE ingestion_state
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE ingestion_state
    ALTER COLUMN workspace_id SET DEFAULT '00000000-0000-0000-0000-000000000001';

-- Rename drive_id → connector_id: generic SourceConnector support (D1).
-- The Rust ingestion binary (ingestion/src/state.rs) will be updated in PR2.
ALTER TABLE ingestion_state
    RENAME COLUMN drive_id TO connector_id;

-- Discriminator column so the ingestion pipeline knows which connector owns each row.
ALTER TABLE ingestion_state
    ADD COLUMN IF NOT EXISTS connector_kind VARCHAR(40) DEFAULT 'microsoft_graph';

-- -------------------------------------------------------------------------
-- NOTE: hnsw.iterative_scan / hnsw.max_scan_tuples tuning
-- -------------------------------------------------------------------------
-- ALTER DATABASE CURRENT SET hnsw.iterative_scan = 'strict_order';
-- ALTER DATABASE CURRENT SET hnsw.max_scan_tuples = 20000;
--
-- These statements cannot run inside a transaction (Flyway default).
-- Apply manually after deploying this migration:
--
--   psql "$DB_URL" -c "ALTER DATABASE <dbname> SET hnsw.iterative_scan = 'strict_order';"
--   psql "$DB_URL" -c "ALTER DATABASE <dbname> SET hnsw.max_scan_tuples = 20000;"
--
-- Or configure via application.yaml spring.datasource.hikari.connection-init-sql
-- once pgvector 0.8+ confirms per-session SET support for these GUCs.
