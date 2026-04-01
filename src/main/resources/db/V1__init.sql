CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE api_keys (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash   VARCHAR(72)  NOT NULL UNIQUE,
    label      VARCHAR(100) NOT NULL,
    role       VARCHAR(20)  NOT NULL CHECK (role IN ('READ_ONLY', 'ADMIN')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE employees (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    department VARCHAR(50),
    email      VARCHAR(150),
    salary     NUMERIC(10, 2)
);

CREATE TABLE document_chunks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_name     VARCHAR(255) NOT NULL,
    classification VARCHAR(20) NOT NULL CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL')),
    minio_key    VARCHAR(500) NOT NULL,
    chunk_index  INTEGER      NOT NULL,
    text_preview VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_name      VARCHAR(50)  NOT NULL,
    api_key_id     UUID REFERENCES api_keys (id),
    params_json    JSONB,
    result_summary VARCHAR(500),
    timestamp      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_timestamp ON audit_logs (timestamp DESC);
CREATE INDEX idx_chunk_fts ON document_chunks USING GIN (to_tsvector('french', coalesce(text_preview, '')));
CREATE INDEX idx_chunk_classification ON document_chunks (classification);
