-- SEC-001: API key lifecycle management
-- Adds expiry, revocation flag, and last-used tracking to api_keys.

ALTER TABLE api_keys
    ADD COLUMN expires_at   TIMESTAMPTZ,
    ADD COLUMN revoked      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN last_used_at TIMESTAMPTZ;

CREATE INDEX idx_api_keys_active ON api_keys (revoked, expires_at);

-- SEC-020: Append-only enforcement on audit_logs.
-- Prevents any UPDATE or DELETE by the application user,
-- ensuring the audit trail cannot be tampered with after the fact.

CREATE OR REPLACE FUNCTION prevent_audit_modification()
    RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: modifications and deletions are not permitted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
