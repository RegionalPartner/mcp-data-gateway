# Security Hardening Reference

> For senior developers: explains the reasoning behind the four structural security fixes
> applied to this project, how to maintain them, and how to extend the security model for
> future tables or features.

This document complements `ARCHITECTURE.md` (which describes *what* the security controls
do) with the *why* — the threat they address, what breaks if they are removed, and how to
operate them.

---

## Table of Contents

1. [Threat model](#1-threat-model)
2. [HMAC-SHA256 key authentication (SEC-HMAC)](#2-hmac-sha256-key-authentication-sec-hmac)
3. [PostgreSQL Row-Level Security (SEC-RLS)](#3-postgresql-row-level-security-sec-rls)
4. [Encrypted content storage (SEC-ENC)](#4-encrypted-content-storage-sec-enc)
5. [Dual audit sinks (SEC-AUDIT2)](#5-dual-audit-sinks-sec-audit2)
6. [Environment variable reference](#6-environment-variable-reference)

---

## 1. Threat model

A security analysis (2026-04-03) identified four structural vulnerabilities in the original
implementation. Each one represents a case where a single control failure exposes data or
enables undetected tampering.

| Vulnerability | Impact if exploited | Fix |
|---|---|---|
| BCrypt for API keys | 250ms latency per request; no security benefit over HMAC for high-entropy inputs | Replace with HMAC-SHA256 + server-side pepper |
| Single-layer access control | One application bug or direct DB connection bypasses all access control | Add PostgreSQL RLS as a second, independent enforcement layer |
| MinIO for text fragments | Second authentication domain, independent CVE history (CVE-2023-28432), unnecessary for text-sized content | Encrypt content in PostgreSQL; eliminate external service |
| Trigger-based audit logs only | `ALTER TABLE audit_logs DISABLE TRIGGER ALL` silently defeats the append-only guarantee | Add a second audit sink outside PostgreSQL's transaction machinery |

These four fixes are intentionally minimal — they close the identified gaps without
restructuring the application or adding new external dependencies.

---

## 2. HMAC-SHA256 key authentication (SEC-HMAC)

### Why HMAC instead of BCrypt

API keys are random strings (20+ chars), not passwords typed by humans. Their entropy
is already at or above 128 bits. BCrypt's purpose is to compensate for the *low* entropy
of human-chosen passwords via deliberate slowness.

For a high-entropy random string:
- BCrypt adds ~250ms of latency per request
- The security benefit is zero — an attacker who obtains the stored hash cannot invert
  HMAC-SHA256 any faster than brute-forcing the raw key space

NIST SP 800-63B endorses this reasoning: memorized secrets (passwords) need slow KDFs;
randomly-generated credentials do not.

### How the pepper works

```
stored_hash = HMAC-SHA256(rawKey, pepper)
```

The pepper (`MCP_HMAC_PEPPER`, ≥ 32 chars) is a server-side secret stored separately from
the database. Even if the `api_keys` table is leaked, an attacker cannot verify a candidate
raw key without the pepper. This is equivalent to BCrypt's cost factor as a *second factor*,
but without the latency.

**The pepper must never appear in source code, commit history, or application logs.**

### Key rotation procedure

1. Generate a new pepper: `openssl rand -hex 32`
2. Write a new Flyway migration (V8, V9, …) modelled on `V4__hmac_api_keys.sql`:
   ```sql
   UPDATE api_keys
   SET key_hash = encode(hmac('demo-readonly-key-001', '${newPepper}', 'sha256'), 'hex')
   WHERE label = 'Demo Read-Only Client';
   ```
3. Configure `spring.flyway.placeholders.hmacPepper` with the new pepper value.
4. Deploy with the new `MCP_HMAC_PEPPER`.

All existing keys are re-hashed in the migration; no keys are invalidated.

### How `computeDemoHashes` works

```bash
MCP_HMAC_PEPPER=<pepper> ./gradlew computeDemoHashes
```

Prints the HMAC-SHA256 hashes of the two demo key values using the configured pepper.
Used to verify that `V4__hmac_api_keys.sql` will produce the correct hashes for a given
pepper value before deploying.

---

## 3. PostgreSQL Row-Level Security (SEC-RLS)

### Design

The application already filters document classifications in SQL at query build time:
```sql
WHERE classification IN ('PUBLIC', 'INTERNAL')  -- for READ_ONLY
```

This is the first enforcement layer. It fails if application code has a bug (wrong role
check), or if someone connects to the database directly (bypassing the application entirely).

V5 adds RLS as a **second, independent** enforcement layer:

```sql
CREATE POLICY doc_chunks_classification_policy ON document_chunks
    AS PERMISSIVE FOR SELECT TO PUBLIC
    USING (
        COALESCE(current_setting('app.mcp_role', true), 'READ_ONLY') = 'ADMIN'
        OR classification != 'CONFIDENTIAL'
    );
```

The policy reads a session variable (`app.mcp_role`) that is set by `RlsContextAspect`
at the start of each `@Tool` transaction:

```java
jdbc.execute("SET LOCAL app.mcp_role = '" + role.name() + "'");
```

`SET LOCAL` expires at the end of the transaction — it cannot leak between requests.

### Why `SET LOCAL` not `SET`

`SET` persists for the duration of the session (connection). Since the application uses
a connection pool (HikariCP), sessions are reused across requests. `SET LOCAL` expires
at transaction end, ensuring the next request on the same connection starts with a clean
state.

### The superuser caveat

`FORCE ROW LEVEL SECURITY` is bypassed by PostgreSQL roles with `rolsuper = true`. If
the application connects as a superuser, RLS has no effect and the policy is never
evaluated. In production:

```sql
SELECT rolsuper FROM pg_roles WHERE rolname = 'mcpuser';  -- must return 'f'
```

If it returns `t`, re-create the user as a non-superuser (see OVH_DEPLOYMENT.md §7.6).

### Adding a new RLS policy

For a future table `contracts` with a `sensitivity` column:

1. Enable RLS in the migration:
   ```sql
   ALTER TABLE contracts ENABLE ROW LEVEL SECURITY;
   ALTER TABLE contracts FORCE ROW LEVEL SECURITY;

   CREATE POLICY contracts_sensitivity_policy ON contracts
       AS PERMISSIVE FOR SELECT TO PUBLIC
       USING (
           COALESCE(current_setting('app.mcp_role', true), 'READ_ONLY') = 'ADMIN'
           OR sensitivity != 'RESTRICTED'
       );
   ```

2. `RlsContextAspect` already sets `app.mcp_role` for all `@Tool` calls — no code change
   needed.

3. Write an integration test that verifies the policy fires independently of the application
   filter (see `DocumentSearchToolIT.rls_v5Migration_createdClassificationPolicy` for the
   pattern).

---

## 4. Encrypted content storage (SEC-ENC)

### Why not MinIO

MinIO is an S3-compatible object store. The original design stored document text chunks
in MinIO (`minio_key` → object path). This introduced:

- **CVE-2023-28432**: an information disclosure vulnerability that exposed environment
  variables (including `MINIO_ROOT_PASSWORD`) via the `/minio/health/cluster` endpoint
  in versions before `RELEASE.2023-03-13T19-46-17Z`. A pinned-but-outdated image is a
  common operational failure mode.
- **Second authentication domain**: credentials for both PostgreSQL and MinIO must be
  managed, rotated, and audited. Each is an independent attack surface.
- **Unnecessary for the use case**: text-sized content (< 10KB per chunk) fits comfortably
  in a PostgreSQL BYTEA column.

### AES-256-GCM design

```
plaintext ──► ContentEncryptor.encrypt() ──► [12B IV][ciphertext+16B GCM tag]
                     │
                     ▼
           stored as BYTEA in encrypted_content column
```

Wire format per chunk:
- **12 bytes**: random IV (`SecureRandom.nextBytes`)
- **N bytes**: AES-256-GCM ciphertext
- **16 bytes**: GCM authentication tag (appended to ciphertext by JCE)

On decrypt, the tag is verified first. Any modification to the ciphertext or tag causes
`AEADBadTagException` → `SecurityException`. `DbContentStore` catches this and returns
an empty string, rather than propagating the error to the LLM.

### Why application-layer encryption, not pgcrypto

`pgcrypto`'s `pgp_sym_encrypt` runs inside the database engine. The key must be passed
to PostgreSQL on every query. If the database credentials are compromised, the attacker
already has enough to decrypt. Application-layer encryption keeps the key (`MCP_CONTENT_KEY`)
separate from the database — a PostgreSQL dump without the key is useless.

### Key rotation procedure

1. Generate a new key: `openssl rand -hex 32`
2. Write a migration that re-encrypts all rows:
   ```sql
   -- V7 (example): re-encrypt all rows with the new key
   -- The application runner reads old encrypted bytes, decrypts with the old key,
   -- re-encrypts with the new key, and writes back.
   ```
   In practice, use a `@Profile("rotate")` `ApplicationRunner` that:
   - Reads all `(id, encrypted_content)` rows
   - Decrypts each with the old key (passed as a second env var)
   - Re-encrypts with the new key
   - Updates `encrypted_content`
3. Deploy with the new `MCP_CONTENT_KEY`. Remove the rotation runner after completion.

### Why FTS still works on `text_preview`

Full-text search (`to_tsvector`) requires plaintext. The `text_preview` column stores an
unencrypted 500-char excerpt for this purpose. This excerpt is intentionally limited to
what would appear in search results anyway — it is not a security boundary, it is an
index. The sensitive full content lives in `encrypted_content`.

---

## 5. Dual audit sinks (SEC-AUDIT2)

### The gap in trigger-only audit logs

The V3 migration creates a `prevent_audit_modification()` trigger that fires on
`UPDATE OR DELETE` on `audit_logs`. A PostgreSQL superuser can bypass this with:

```sql
ALTER TABLE audit_logs DISABLE TRIGGER ALL;
DELETE FROM audit_logs WHERE ...;
ALTER TABLE audit_logs ENABLE TRIGGER ALL;
```

This requires `SUPERUSER` privilege, which the application user should not have. But a
DBA with superuser access (legitimate or compromised) can silently erase audit records.

### The second sink: Logback JSON file

`AuditService` writes every audit event to a dedicated Logback logger (`AUDIT`) in addition
to the database insert:

```java
MDC.put("tool_name", toolName);
MDC.put("api_key_id", apiKeyId.toString());
MDC.put("result_summary", resultSummary);
auditLog.info("audit_event");
```

`logback-spring.xml` routes the `AUDIT` logger to `/var/log/mcp/audit.json` using
`JsonEncoder` (newline-delimited JSON). The file is written by the JVM process, not by
PostgreSQL.

### Hardening the file with `chattr +a`

After the application starts, set the append-only filesystem attribute on the file:

```bash
chattr +a /var/log/mcp/audit.json   # append-only: no overwrite, no truncation, no delete
lsattr /var/log/mcp/audit.json      # verify: output must contain 'a' flag
```

With `chattr +a`:
- The OS kernel blocks `open(..., O_TRUNC)` and `unlink()` on the file
- Even `root` cannot overwrite or delete it (only remove the `a` flag)
- The JVM can still `append` (the `FileAppender` uses `O_WRONLY | O_APPEND`)

To defeat both audit sinks, an attacker must:
1. Have PostgreSQL superuser access (to disable the trigger)
2. Have OS root access and know to remove the `chattr +a` flag
3. Do both in a timeframe undetected by monitoring

### Limitations of `chattr +a`

- Requires a supported filesystem (ext4, xfs). Not available on NFS, tmpfs, or some
  cloud block storage.
- The flag can be removed with `chattr -a` by root — it is not truly immutable, just
  harder to manipulate.
- For stronger guarantees: ship events over TLS to an external syslog receiver (e.g.
  Loki, Elasticsearch) on a separate host. Compromise of one host cannot affect the other.

### Querying audit events

```bash
# All tool calls in the last hour
jq 'select(.mdc.tool_name != null)' /var/log/mcp/audit.json | \
    jq -r '[.mdc.tool_name, .mdc.api_key_id, .mdc.result_summary] | @tsv'

# Authentication failures only
jq 'select(.mdc.tool_name == "authentication_failure")' /var/log/mcp/audit.json
```

---

## 6. Environment variable reference

| Variable | Format | Minimum length | Required in | Purpose |
|---|---|---|---|---|
| `MCP_HMAC_PEPPER` | Any UTF-8 string | 32 characters | All profiles (dev has fallback) | Server-side secret for HMAC-SHA256 API key hashing |
| `MCP_CONTENT_KEY` | Exactly 64 hex characters (0-9, a-f) | 64 hex chars = 32 bytes | All profiles (dev has fallback) | AES-256 key for document content encryption |
| `DB_URL` | JDBC URL | — | All profiles | PostgreSQL connection string; must contain `sslmode=require` in production |
| `DB_USER` | String | — | All profiles | PostgreSQL username; must not be a superuser in production |
| `DB_PASSWORD` | String | — | All profiles | PostgreSQL password |

### Production requirements

- **`MCP_HMAC_PEPPER`**: generate with `openssl rand -hex 32` (64 hex = 256-bit entropy,
  well above the 32-char minimum). Store in a secrets manager. Never log or commit.
- **`MCP_CONTENT_KEY`**: generate with `openssl rand -hex 32`. Store in a secrets manager
  alongside a rotation date. Loss of this value means encrypted content is unrecoverable
  without a backup of the plaintext.
- Both variables have dev-safe fallbacks in `application-dev.yaml` — these fallbacks must
  **never** appear in a production deployment.

### Verifying the configuration

```bash
# Verify HMAC key hashes for demo keys
MCP_HMAC_PEPPER=<your-pepper> ./gradlew computeDemoHashes

# Verify startup checks pass (production-like environment)
MCP_HMAC_PEPPER=<pepper> MCP_CONTENT_KEY=<key> DB_URL=jdbc:postgresql://...?sslmode=require \
  ./gradlew bootRun --args='--spring.profiles.active=prod'
# Should start without IllegalStateException
```
