-- SEC-RLS: Row-Level Security on document_chunks and employees.
--
-- The application sets app.mcp_role per-transaction via RlsContextAspect
-- before any query. RLS policies read the variable with current_setting().
-- The second argument (missing_ok = true) returns NULL when the variable is
-- absent; COALESCE defaults that to 'READ_ONLY' (least privilege).
--
-- IMPORTANT: FORCE ROW LEVEL SECURITY is bypassed by PostgreSQL superusers.
-- The application user (mcpuser) must NOT be a superuser.
-- Verify before deploying:
--   SELECT rolsuper FROM pg_roles WHERE rolname = 'mcpuser';  -- must be false

-- ── document_chunks ─────────────────────────────────────────────────────────

ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunks FORCE ROW LEVEL SECURITY;

-- CONFIDENTIAL rows are only visible when app.mcp_role = 'ADMIN'.
-- Non-ADMIN sessions see PUBLIC and INTERNAL rows.
-- This is a second, independent enforcement layer alongside the application
-- filter in DocumentSearchTool (defence in depth).
CREATE POLICY doc_chunks_classification_policy ON document_chunks
    AS PERMISSIVE
    FOR SELECT
    TO PUBLIC
    USING (
        COALESCE(current_setting('app.mcp_role', true), 'READ_ONLY') = 'ADMIN'
        OR classification != 'CONFIDENTIAL'
    );

-- ── employees ────────────────────────────────────────────────────────────────

ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;

-- All rows are currently visible to all authenticated roles.
-- Salary column filtering remains in PostgresConnector (column-level, not row-level).
-- This policy activates the RLS machinery so future per-row policies can be
-- added without additional schema changes.
CREATE POLICY employees_open_policy ON employees
    AS PERMISSIVE
    FOR SELECT
    TO PUBLIC
    USING (true);
