-- Workspace registry: each workspace isolates a named subset of document_chunks.
-- api_key_workspaces is many-to-many — one key can access multiple workspaces.

CREATE TABLE workspaces (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name  VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE api_key_workspaces (
    api_key_id   UUID NOT NULL REFERENCES api_keys(id)   ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    PRIMARY KEY (api_key_id, workspace_id)
);

-- Seed: a default workspace so existing data and tests stay green.
INSERT INTO workspaces (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'default');

-- Back-fill: grant every existing API key access to the default workspace.
INSERT INTO api_key_workspaces (api_key_id, workspace_id)
SELECT id, '00000000-0000-0000-0000-000000000001'
FROM api_keys;
