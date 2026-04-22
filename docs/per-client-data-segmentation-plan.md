# Per-Client Data Segmentation — Implementation Plan

## Goal

Allow each API key (agent / application) to be restricted to a specific subset
of documents — a **workspace** — without access to data belonging to other
clients. Example: Agent A sees only `contracts/` documents; Agent B sees only
`HR/` documents.

---

## What already exists (don't touch)

| Mechanism | File | What it does |
|---|---|---|
| Role injection | `RlsContextAspect.java` | `SET LOCAL app.mcp_role` before every `@Tool` call |
| Classification filter | `V5__rls_document_chunks.sql` | RLS hides CONFIDENTIAL from READ_ONLY |
| Column allowlist | `PostgresConnector.java` | Hides `salary` from READ_ONLY |
| API key identity | `ApiKey.java` | `UUID id` already uniquely identifies every caller |

The pattern for injecting a session variable into Postgres and enforcing it via
RLS is fully proven. Workspace segmentation reuses exactly that pattern — one
extra variable (`app.mcp_client_id`).

---

## Architecture overview

```
API key (UUID)
      │
      ▼
api_key_workspaces  ──► workspace_id ──► document_chunks.workspace_id
      │
      └──► (many-to-many: one key can access multiple workspaces)
```

A document belongs to exactly one workspace. An API key can be granted access
to one or more workspaces. At query time Postgres enforces the intersection via
RLS.

---

## Step-by-step implementation

### Step 1 — DB migration: workspaces + ownership

**File: `V11__workspaces.sql`**

```sql
-- Workspace registry
CREATE TABLE workspaces (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name  VARCHAR(100) NOT NULL UNIQUE   -- e.g. "contracts", "HR", "public"
);

-- Many-to-many: which keys can access which workspaces
CREATE TABLE api_key_workspaces (
    api_key_id   UUID NOT NULL REFERENCES api_keys(id)   ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    PRIMARY KEY (api_key_id, workspace_id)
);

-- Seed: a default workspace for existing data
INSERT INTO workspaces (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'default');
```

---

### Step 2 — DB migration: add workspace_id to document_chunks

**File: `V12__document_chunks_workspace.sql`**

```sql
ALTER TABLE document_chunks
    ADD COLUMN workspace_id UUID NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES workspaces(id);

CREATE INDEX idx_chunk_workspace ON document_chunks (workspace_id);
```

The `DEFAULT` back-fills all existing rows into the `default` workspace so
existing data and tests remain green.

---

### Step 3 — DB migration: RLS workspace policy

**File: `V13__rls_workspace_policy.sql`**

```sql
-- Drop the old open policy on document_chunks (V5 already added the
-- classification policy; we now add workspace enforcement on top of it).
-- Postgres evaluates ALL PERMISSIVE policies with OR; we need AND semantics
-- for workspace + classification, so we replace both with a single policy.

DROP POLICY IF EXISTS doc_chunks_classification_policy ON document_chunks;

CREATE POLICY doc_chunks_segmentation_policy ON document_chunks
    AS PERMISSIVE
    FOR SELECT
    TO PUBLIC
    USING (
        -- 1. Classification gate (unchanged logic)
        (
            COALESCE(current_setting('app.mcp_role', true), 'READ_ONLY') = 'ADMIN'
            OR classification != 'CONFIDENTIAL'
        )
        AND
        -- 2. Workspace gate — ADMIN keys bypass; others must be explicitly granted
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
```

Key design decisions:
- **ADMIN bypasses** both gates — same behaviour as today, no regression.
- **Fail-closed**: if `app.mcp_client_id` is not set (or not UUID-parseable),
  the subquery returns zero rows → the client sees nothing.
- Two-layer defence is preserved: app layer + Postgres RLS both enforce the
  workspace restriction independently.

---

### Step 4 — Inject `app.mcp_client_id` in `RlsContextAspect`

**File: `src/main/java/io/ancoris/mcp/security/RlsContextAspect.java`**

Current `applyRlsRole` body:

```java
jdbc.execute("SET LOCAL app.mcp_role = '" + role + "'");
```

Add one line after it:

```java
jdbc.execute("SET LOCAL app.mcp_role = '" + role + "'");
jdbc.execute("SET LOCAL app.mcp_client_id = '" + clientId + "'");
```

`clientId` comes from `resolveClientId()`:

```java
private String resolveClientId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
        return key.getId().toString();   // UUID — safe to interpolate
    }
    // Fail-safe: an invalid UUID so the workspace subquery returns zero rows
    return "00000000-0000-0000-0000-000000000000";
}
```

Injection safety: `UUID.toString()` always produces `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
— no user-supplied characters reach the SQL string.

---

### Step 5 — Expose workspace column in `PostgresConnector`

**File: `src/main/java/io/ancoris/mcp/connector/PostgresConnector.java`**

Add `workspace_id` to the `document_chunks` column allowlist so queries can
filter by it explicitly (optional but useful for debugging):

```java
"document_chunks", List.of(
    "id", "doc_name", "classification", "chunk_index",
    "text_preview", "created_at", "workspace_id"   // ← add
)
```

No other change needed here — RLS already enforces the restriction at the DB
layer regardless of what the app-level SELECT includes.

---

### Step 6 — Provision workspaces (admin tooling / seed)

When creating a new API key for an agent, the admin must:

1. Create (or reuse) a workspace row in `workspaces`.
2. Insert a row in `api_key_workspaces` linking the key to that workspace.
3. Tag ingested documents with the correct `workspace_id`.

Example SQL for provisioning "Agent A → contracts workspace":

```sql
-- 1. Create workspace
INSERT INTO workspaces (name) VALUES ('contracts')
RETURNING id;   -- capture the UUID

-- 2. Create API key (existing flow)
INSERT INTO api_keys (key_hash, label, role) VALUES (...)
RETURNING id;   -- capture the UUID

-- 3. Grant access
INSERT INTO api_key_workspaces (api_key_id, workspace_id)
VALUES ('<key-uuid>', '<workspace-uuid>');
```

---

## What does NOT change

- `AccessRole` enum — roles still work exactly as today
- `ApiKeyFilter` — authentication flow unchanged
- `AuditLogService` — audit trail unchanged
- All existing tests — the `default` workspace back-fill means zero migration
  pain for existing seed data

---

## Testing checklist

- [ ] READ_ONLY key with workspace `contracts` → can query `contracts` docs, cannot see `HR` docs
- [ ] READ_ONLY key with workspace `contracts` → still cannot see CONFIDENTIAL docs (both gates active)
- [ ] ADMIN key → sees all docs in all workspaces (bypass confirmed)
- [ ] Key with no workspace row → sees zero docs (fail-closed confirmed)
- [ ] Existing integration tests still pass (default workspace covers seed data)

---

## File summary

| Action | File |
|---|---|
| New migration | `src/main/resources/db/migration/V11__workspaces.sql` |
| New migration | `src/main/resources/db/migration/V12__document_chunks_workspace.sql` |
| New migration | `src/main/resources/db/migration/V13__rls_workspace_policy.sql` |
| Modify | `src/main/java/io/ancoris/mcp/security/RlsContextAspect.java` |
| Modify | `src/main/java/io/ancoris/mcp/connector/PostgresConnector.java` |

Total: **3 migration files + 2 Java class edits**. No new abstractions, no
architectural rework.
