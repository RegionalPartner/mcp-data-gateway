-- V16: PII sanitization vault — token counter + token map.
--
-- These tables back the Rust `ingestion/src/sanitize/vault.rs` module
-- (feature = "sanitize-vault").  They are created regardless of whether the
-- feature is enabled on the ingestion pipeline so the DB is ready the moment
-- the feature is turned on (zero-downtime enablement).
--
-- Design notes:
--   * `workspace_id` is used (not `tenant_id`) to align with the rest of the
--     codebase (see V11 workspaces, V12 document_chunks.workspace_id, V14
--     RESTRICTIVE workspace isolation policy).  One workspace = one tenant.
--   * `pii_token_counter` gives each (workspace, kind) pair a monotonically
--     increasing serial used to build human-readable token strings such as
--     "PII_EMAIL_00042".  A dedicated counter table (rather than a sequence)
--     is used because per-workspace sequences would bloat the catalog.
--   * `pii_token_map` is the canonical lookup surface.  `lookup_hash` is an
--     HMAC-SHA256 over the normalized original value (stable per workspace
--     key) used for race-safe UPSERT-by-hash.  `original_value_ct` is the
--     AES-256-GCM wrapped plaintext with the same wire format used elsewhere
--     (`[12B IV][ciphertext + 16B GCM tag]`).

CREATE TABLE pii_token_counter (
    workspace_id UUID    NOT NULL,
    entity_kind  TEXT    NOT NULL,
    next_id      BIGINT  NOT NULL DEFAULT 1,
    PRIMARY KEY (workspace_id, entity_kind)
);

CREATE TABLE pii_token_map (
    token             TEXT        PRIMARY KEY,
    workspace_id      UUID        NOT NULL,
    entity_kind       TEXT        NOT NULL,
    lookup_hash       BYTEA       NOT NULL,
    original_value_ct BYTEA       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pii_token_map_workspace_kind_hash_key
        UNIQUE (workspace_id, entity_kind, lookup_hash)
);

CREATE INDEX pii_token_map_workspace_kind_idx
    ON pii_token_map (workspace_id, entity_kind);

COMMENT ON TABLE pii_token_counter IS
    'Per (workspace, entity_kind) serial used to mint PII tokens. Counter is updated by the CTE UPSERT in ingestion/src/sanitize/vault.rs.';

COMMENT ON TABLE pii_token_map IS
    'Token -> encrypted original value map. lookup_hash is an HMAC-SHA256 over the normalized original value (NFKC + ws-collapse + lowercase for PER/ORG; strip-ws + lower for EMAIL/IBAN/NIR) keyed per workspace. original_value_ct uses the [12B IV][ciphertext + 16B tag] wire format.';
