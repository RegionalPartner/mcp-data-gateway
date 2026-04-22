-- Replace the single-gate classification policy (V5) with a two-gate policy
-- that enforces BOTH classification AND workspace membership in a single
-- PERMISSIVE policy.  Using two PERMISSIVE policies would give OR semantics;
-- combining both gates inside one policy gives the required AND semantics.

DROP POLICY IF EXISTS doc_chunks_classification_policy ON document_chunks;

CREATE POLICY doc_chunks_segmentation_policy ON document_chunks
    AS PERMISSIVE
    FOR SELECT
    TO PUBLIC
    USING (
        -- Gate 1: classification — ADMIN sees everything; others skip CONFIDENTIAL.
        (
            COALESCE(current_setting('app.mcp_role', true), 'READ_ONLY') = 'ADMIN'
            OR classification != 'CONFIDENTIAL'
        )
        AND
        -- Gate 2: workspace — ADMIN bypasses; others must hold an explicit grant.
        -- Fail-closed: an unset or invalid app.mcp_client_id resolves to the
        -- nil UUID, which has no workspace rows, so the client sees nothing.
        (
            COALESCE(current_setting('app.mcp_role', true), 'READ_ONLY') = 'ADMIN'
            OR workspace_id IN (
                SELECT akw.workspace_id
                FROM   api_key_workspaces akw
                WHERE  akw.api_key_id =
                       COALESCE(
                           current_setting('app.mcp_client_id', true),
                           '00000000-0000-0000-0000-000000000000'
                       )::UUID
            )
        )
    );
