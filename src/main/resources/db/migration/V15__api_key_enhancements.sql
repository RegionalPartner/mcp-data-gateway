-- V15 (D5): API key enhancements for agent security controls.
--
-- 1. api_keys.status — plain VARCHAR(16) with three values ACTIVE / REVOKED / EXPIRED.
--    NOT a GENERATED STORED column: PostgreSQL rejects expressions involving now()
--    or other non-immutable functions ("ERROR: generation expression is not
--    immutable"). Instead we back-fill existing rows and keep the column in sync
--    via (a) ApiKeyExpirySweeper @Scheduled cron hourly and (b) application-level
--    filters in ApiKeyService that use revoked/expires_at as the primary source
--    of truth so status lag is never security-relevant.
--
-- 2. api_keys.allowed_tools JSONB — per-key MCP tool allowlist.
--    NULL → unrestricted (all tools allowed, existing behaviour preserved).
--    e.g. '["search_documents","list_sources"]' restricts that key to those two tools.
--    ToolAllowlistAspect (HIGHEST_PRECEDENCE) reads this column and denies calls
--    outside the allowlist BEFORE RlsContextAspect injects any transaction state.

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'));

-- Back-fill status for existing rows (new rows default to 'ACTIVE').
UPDATE api_keys
SET status = CASE
    WHEN revoked THEN 'REVOKED'
    WHEN expires_at IS NOT NULL AND expires_at < now() THEN 'EXPIRED'
    ELSE 'ACTIVE'
END;

ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS allowed_tools JSONB;

-- Status lookups (e.g. /actuator key-health, operator dashboards) are cheap
-- with an index; cardinality is tiny (3 values) but WHERE status='ACTIVE' over
-- thousands of keys still benefits from the partial filter.
CREATE INDEX IF NOT EXISTS idx_api_keys_status ON api_keys (status);
