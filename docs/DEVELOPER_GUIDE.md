# MCP Data Gateway — Developer Guide

> A beginner-friendly walkthrough of every file, every concept, and how they fit together.

---

## Table of Contents

1. [What is this project?](#1-what-is-this-project)
2. [Concepts you need first](#2-concepts-you-need-first)
3. [Technology stack](#3-technology-stack)
4. [Architecture overview](#4-architecture-overview)
5. [How a request flows through the system](#5-how-a-request-flows-through-the-system)
6. [Project structure](#6-project-structure)
7. [Database schema](#7-database-schema)
8. [Security layer](#8-security-layer)
9. [The three MCP tools](#9-the-three-mcp-tools)
10. [Configuration files](#10-configuration-files)
11. [Running locally](#11-running-locally)
12. [Tests](#12-tests)
13. [Build tooling and code quality](#13-build-tooling-and-code-quality)
14. [Common mistakes and their fixes](#14-common-mistakes-and-their-fixes)

---

## 1. What is this project?

This is a **secure gateway** that lets an AI model (like Claude) read data from a company's internal systems.

Imagine a company employee who asks an AI assistant: *"Show me the IT department's employees"* or *"Find documents about recruitment"*. Without this gateway, the AI has no way to reach internal databases or document stores. This project is the bridge.

```
Employee's AI assistant
        │
        │  (sends tool calls over HTTP)
        ▼
  MCP Data Gateway          ← this project
        │
        ├──► PostgreSQL      (employee records, document metadata)
        └──► PostgreSQL      (encrypted document content, AES-256-GCM)
```

The gateway exposes three **tools** that the AI can call:
- `query_database` — read rows from internal tables
- `search_documents` — full-text search across stored documents
- `list_sources` — discover what data is available

Crucially, **not all callers see the same data**. A `READ_ONLY` key can query employees but never sees salaries. A `CONFIDENTIAL` document is invisible unless you hold an `ADMIN` key.

---

## 2. Concepts you need first

### MCP — Model Context Protocol

MCP is a standard that defines how an AI model communicates with external tools over HTTP. Think of it as a menu system: the AI first asks *"what tools do you have?"*, then calls a specific tool with arguments, and gets back a structured result.

The protocol always starts with a handshake:

```
1. POST /mcp   (method: initialize)       → server returns a session ID
2. POST /mcp   (method: notifications/initialized)  → client confirms ready
3. POST /mcp   (method: tools/list or tools/call)   → actual work
```

Every request after step 1 must include the `Mcp-Session-Id` header. If you omit it, the server doesn't know which session you belong to.

### JSON-RPC 2.0

MCP uses JSON-RPC for its message format. Every call looks like:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "query_database",
    "arguments": { "table": "employees" }
  }
}
```

- `jsonrpc` — always `"2.0"`, identifies the protocol version
- `id` — you pick a number; the server echoes it back in the response
- `method` — what you want to do
- `params` — the arguments

A **notification** is the same structure but with no `id` field. It means: "I'm telling you something, I don't expect a reply."

### SSE — Server-Sent Events

When you call a tool, the server doesn't reply with a simple JSON body. Instead it opens a streaming connection and sends data in chunks:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream

id: 1
event: message
data: {"jsonrpc":"2.0","id":1,"result":[...]}

```

This is the SSE wire format. Each "event" is a small text block ending with a blank line. The client reads chunks as they arrive. This is useful for long-running tool calls that want to stream partial results.

### BCrypt

BCrypt is a password-hashing algorithm. You never store a raw API key in the database — you store a **hash** of it. When someone presents a key, you hash what they sent and compare to the stored hash.

BCrypt is deliberately slow (the `12` in `BCryptPasswordEncoder(12)` is the "cost factor" — each increment roughly doubles the time). This makes brute-force attacks impractical.

### RBAC — Role-Based Access Control

Every API key has a `role`: either `READ_ONLY` or `ADMIN`. What you can see or do depends on your role. The same query tool returns different columns depending on who is calling.

---

## 3. Technology stack

| Library / Tool | What it does in this project |
|---|---|
| **Spring Boot 3.5** | The application framework. Handles HTTP serving, dependency injection, configuration loading. |
| **Spring AI (MCP Server WebMVC)** | Adds MCP protocol support on top of Spring MVC. Registers `@Tool`-annotated methods as callable tools and handles the SSE streaming. |
| **Spring Security** | The security framework. Processes filters, manages the security context per request. |
| **Spring Data JPA + Hibernate** | Maps Java objects to database tables (the `@Entity` classes). Provides repository interfaces for querying. |
| **PostgreSQL** | The relational database. Stores employees, document metadata, API keys, and audit logs. |
| **Flyway** | Database migration tool. Applies SQL files in version order (`V1__`, `V2__`, …, `V6__`) on startup. You never change the database manually. |
| **AES-256-GCM (javax.crypto)** | Content encryption. Document chunk text is encrypted at rest in PostgreSQL using a 256-bit key from `MCP_CONTENT_KEY`. Each chunk gets a fresh 12-byte random IV, prepended to the ciphertext. No external service required. |
| **Caffeine** | An in-memory cache library. Used to cache the API key list so every request doesn't trigger a database + HMAC round-trip. |
| **Micrometer** | Metrics library. Counts authentication failures, rate-limit hits, tool calls — exposes them on `/actuator/metrics`. |
| **Testcontainers** | Starts real Docker containers (PostgreSQL, MinIO) during tests, then tears them down. No mocking of the database. |
| **JaCoCo** | Measures test code coverage. Fails the build if coverage drops below 70%. |
| **SpotBugs + FindSecBugs** | Static analysis. Looks for common bugs and security vulnerabilities in the compiled bytecode. |
| **OWASP Dependency Check** | Scans your dependencies for known CVEs (security vulnerabilities). Fails the build if a critical one is found. |
| **Checkstyle** | Enforces code style rules (indentation, naming, etc.). |

---

## 4. Architecture overview

```
                          HTTP :8080
                              │
                    ┌─────────▼─────────┐
                    │  RateLimiterFilter │  ← 60 req/min per IP (SEC-002)
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │   ApiKeyFilter    │  ← validates X-API-Key header (SEC-001)
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │  SecurityConfig   │  ← RBAC rules, ASYNC dispatch permit
                    └─────────┬─────────┘
                              │
              ┌───────────────▼───────────────┐
              │     Spring AI MCP Server      │
              │  (handles protocol handshake,  │
              │   session management, SSE)     │
              └──────┬────────────────────────┘
                     │
       ┌─────────────┼─────────────┐
       ▼             ▼             ▼
DatabaseQueryTool  DocumentSearchTool  SourceListTool
       │             │                    │
       ▼             ▼                    │
PostgresConnector  DbContentStore  PostgresConnector
       │             │
       ▼             ▼
  PostgreSQL       PostgreSQL
   (data, keys,  (encrypted_content
    audit logs)   BYTEA, AES-256-GCM)
              │
              ▼
         AuditService  ──► audit_logs table (append-only)
```

### The layers, from outside in

1. **Network** — the raw TCP connection arrives on port 8080
2. **Filters** — `RateLimiterFilter` and `ApiKeyFilter` run before any business logic
3. **Spring Security** — enforces authorization rules based on the authenticated context
4. **MCP Server** — Spring AI handles the JSON-RPC protocol, routing `tools/call` to the right Java method
5. **Tool layer** — `DatabaseQueryTool`, `DocumentSearchTool`, `SourceListTool` — the actual business logic
6. **Connector layer** — `PostgresConnector` and `MinioConnector` handle data access; they know about roles and safety rules
7. **Audit** — every tool invocation writes an immutable audit log entry asynchronously

---

## 5. How a request flows through the system

Let's trace a single tool call from start to finish.

**Scenario**: an AI sends `{"table":"employees"}` to `query_database` with a `READ_ONLY` key.

```
Step 1 — RateLimiterFilter
  Request arrives. The filter checks: how many requests has this IP sent
  in the last 60 seconds? If < 60, the timestamp is recorded and the
  request passes through. If ≥ 60, a 429 response is returned immediately.

Step 2 — ApiKeyFilter
  The filter reads the X-API-Key header. It calls ApiKeyService.authenticate():
    a) Load all API keys from the database (or from the 60-second Caffeine cache)
    b) For each key, BCrypt-compare the raw header value to the stored hash
    c) Also check: is the key revoked? Has it expired?
  If a match is found, the ApiKey object is stored in Spring's SecurityContext
  so downstream code can read it. If not found → 401 Unauthorized.

Step 3 — Spring Security
  The framework checks the authorization rules from SecurityConfig.
  This request is to /mcp — it requires .authenticated().
  The SecurityContext already has the authentication token from step 2, so
  it passes.

Step 4 — Spring AI MCP Server
  Spring AI parses the JSON-RPC body, reads method: "tools/call",
  name: "query_database", and routes the call to DatabaseQueryTool.queryDatabase().

Step 5 — DatabaseQueryTool
  It reads the current ApiKey from the SecurityContext:
    ApiKey apiKey = currentApiKey();  // READ_ONLY role
  It calls:
    postgresConnector.query("employees", null, READ_ONLY, 100)

Step 6 — PostgresConnector
  It checks: is "employees" in the ALLOWED_TABLES set? Yes.
  It builds the column list for READ_ONLY:
    all columns = [id, name, department, email, salary]
    hidden for READ_ONLY = [salary]
    result = [id, name, department, email]
  It runs:
    SELECT id, name, department, email FROM employees LIMIT 100
  Returns the rows.

Step 7 — AuditService
  DatabaseQueryTool calls auditService.log(...) with:
    toolName="query_database", apiKeyId=..., params={table:"employees"}, summary="returned 5 rows"
  This runs on a background thread ("audit-0") so it doesn't slow down
  the HTTP response.

Step 8 — SSE Response
  Spring AI wraps the returned list in a JSON-RPC response and streams it
  back as a text/event-stream SSE event. The caller reads it.
```

The `salary` column was never in the SQL query — it was stripped before the query was even built. There is no post-processing filter that could accidentally leak it.

---

## 6. Project structure

```
mcp-data-gateway/
├── build.gradle.kts                  ← build script, all dependencies and tools
├── docker-compose.yaml               ← runs the full stack locally
│
├── config/
│   ├── checkstyle/checkstyle.xml     ← code style rules
│   ├── spotbugs/exclude.xml          ← SpotBugs false-positive suppressions
│   └── owasp/suppression.xml        ← CVE suppressions (known false positives)
│
├── src/main/
│   ├── java/io/ancoris/mcp/
│   │   ├── McpGatewayApplication.java          ← entry point
│   │   ├── audit/
│   │   │   ├── AuditLog.java                   ← database entity (one audit row)
│   │   │   ├── AuditLogRepository.java         ← JPA repository interface
│   │   │   └── AuditService.java               ← writes audit entries asynchronously
│   │   ├── config/
│   │   │   ├── AsyncConfig.java                ← thread pool for audit writes
│   │   │   ├── McpConfig.java                  ← MinIO client + tool registration
│   │   │   ├── PasswordEncoderConfig.java      ← BCrypt bean (strength 12)
│   │   │   ├── SecurityConfig.java             ← HTTP security rules
│   │   │   └── StartupValidationConfig.java    ← enforces TLS in production
│   │   ├── connector/
│   │   │   ├── MinioConnector.java             ← fetches document chunks from MinIO
│   │   │   └── PostgresConnector.java          ← runs role-aware SQL queries
│   │   ├── model/
│   │   │   ├── AccessRole.java                 ← READ_ONLY / ADMIN enum
│   │   │   ├── ApiKey.java                     ← JPA entity for api_keys table
│   │   │   └── DataFragment.java               ← what the LLM receives from search
│   │   ├── security/
│   │   │   ├── ApiKeyFilter.java               ← HTTP filter: validates X-API-Key
│   │   │   ├── ApiKeyRepository.java           ← loads all keys from the database
│   │   │   ├── ApiKeyService.java              ← BCrypt matching + Caffeine cache
│   │   │   └── RateLimiterFilter.java          ← per-IP sliding window
│   │   └── tools/
│   │       ├── DatabaseQueryTool.java          ← MCP tool: query_database
│   │       ├── DocumentSearchTool.java         ← MCP tool: search_documents
│   │       └── SourceListTool.java             ← MCP tool: list_sources
│   └── resources/
│       ├── application.yaml                    ← production config
│       ├── application-dev.yaml                ← overrides for local dev
│       └── db/
│           ├── V1__init.sql                    ← creates all tables
│           ├── V2__seed.sql                    ← inserts demo data
│           └── V3__key_lifecycle.sql           ← adds expiry/revocation + audit trigger
│
└── src/test/
    └── java/io/ancoris/mcp/
        ├── integration/
        │   ├── AbstractIntegrationTest.java    ← base class: starts Docker containers
        │   └── TestSecurityHelper.java         ← helper: set SecurityContext for tests
        ├── McpEndToEndIT.java                  ← E2E: full HTTP stack test
        ├── audit/
        │   ├── AuditLogIT.java                 ← integration: audit rows + immutability
        │   └── AuditServiceTest.java           ← unit: audit event fields + counters
        ├── connector/
        │   ├── MinioConnectorTest.java         ← unit: chunk fetch + path validation
        │   └── PostgresConnectorTest.java      ← unit: column filtering + SQL injection
        └── security/
            ├── ApiKeyFilterIT.java             ← integration: 401 on missing key
            ├── ApiKeyLifecycleIT.java          ← integration: expiry + revocation
            ├── ApiKeyServiceTest.java          ← unit: BCrypt matching + caching
            ├── RateLimiterFilterIT.java        ← integration: 429 on 61st request
            └── RateLimiterFilterTest.java      ← unit: sliding window logic
```

---

## 7. Database schema

Flyway applies migrations in order at startup. You never touch the database directly.

### V1 — tables (`V1__init.sql`)

```
api_keys
┌────────────┬──────────────┬──────────────────────────────────┐
│ id         │ UUID         │ primary key (auto-generated)     │
│ key_hash   │ VARCHAR(72)  │ BCrypt hash of the raw API key   │
│ label      │ VARCHAR(100) │ human-readable name              │
│ role       │ VARCHAR(20)  │ 'READ_ONLY' or 'ADMIN'           │
│ created_at │ TIMESTAMPTZ  │ set automatically on insert      │
└────────────┴──────────────┴──────────────────────────────────┘

employees
┌────────────┬──────────────┬──────────────────────────────────┐
│ id         │ SERIAL       │ auto-incrementing integer PK     │
│ name       │ VARCHAR(100) │                                  │
│ department │ VARCHAR(50)  │                                  │
│ email      │ VARCHAR(150) │                                  │
│ salary     │ NUMERIC      │ ADMIN-only column                │
└────────────┴──────────────┴──────────────────────────────────┘

document_chunks
┌───────────────────┬──────────────┬────────────────────────────────────────────────┐
│ id                │ UUID         │ primary key                                    │
│ doc_name          │ VARCHAR(255) │ filename of the original document              │
│ classification    │ VARCHAR(20)  │ 'PUBLIC', 'INTERNAL', or 'CONFIDENTIAL'        │
│ minio_key         │ VARCHAR(500) │ legacy column (nullable); superseded by V6     │
│ chunk_index       │ INTEGER      │ which chunk of the document                    │
│ text_preview      │ VARCHAR(500) │ short excerpt for full-text search (unencrypted)│
│ encrypted_content │ BYTEA        │ AES-256-GCM: [12B IV][ciphertext+16B tag]      │
│ created_at        │ TIMESTAMPTZ  │                                                │
└───────────────────┴──────────────┴────────────────────────────────────────────────┘

audit_logs
┌────────────────┬──────────────┬──────────────────────────────────┐
│ id             │ UUID         │ primary key                      │
│ tool_name      │ VARCHAR(50)  │ which tool was called            │
│ api_key_id     │ UUID         │ FK to api_keys (who called it)   │
│ params_json    │ JSONB        │ the parameters passed            │
│ result_summary │ VARCHAR(500) │ short summary of the result      │
│ timestamp      │ TIMESTAMPTZ  │                                  │
└────────────────┴──────────────┴──────────────────────────────────┘
```

Two important indexes on `document_chunks`:
- `idx_chunk_fts` — a GIN (inverted index) on `to_tsvector('french', text_preview)`. This is what makes full-text search fast.
- `idx_chunk_classification` — makes the `WHERE classification IN (...)` filter fast.

### V2 — seed data (`V2__seed.sql`)

Inserts:
- Two demo API keys (hashed with BCrypt strength-12)
- Five employees across departments RH, IT, Finance
- Five document chunks with classifications PUBLIC, INTERNAL, and CONFIDENTIAL

### V4 — HMAC-SHA256 key authentication (`V4__hmac_api_keys.sql`)

Migrates `api_keys.key_hash` from BCrypt VARCHAR(72) to HMAC-SHA256 VARCHAR(64).
Updates the two demo key hashes using a server-side pepper from `MCP_HMAC_PEPPER`.

### V5 — PostgreSQL Row-Level Security (`V5__rls_document_chunks.sql`)

Enables RLS on `document_chunks` and `employees`. Creates a policy that blocks
`CONFIDENTIAL` rows for non-ADMIN sessions at the database level, independently of
any application-layer filter.

### V6 — encrypted content column (`V6__encrypted_content_column.sql`)

Adds `encrypted_content BYTEA` to `document_chunks`. Makes `minio_key` nullable
(transition period). Adds a partial index `idx_chunk_needs_migration` for rows not
yet migrated. V7 will drop `minio_key` once all rows have been re-encrypted.

### V3 — lifecycle + immutability (`V3__key_lifecycle.sql`)

Adds three columns to `api_keys`:
- `expires_at` — the key stops working after this timestamp
- `revoked` — set to `TRUE` to instantly invalidate a key
- `last_used_at` — tracks last successful authentication

Also creates a **PostgreSQL trigger** on `audit_logs`:

```sql
CREATE TRIGGER audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
```

This means `audit_logs` is **append-only at the database level**. Even if application code tries to delete or modify an audit entry (accidentally or through a bug), the database refuses it. The audit trail can only grow, never shrink.

---

## 8. Security layer

### 8.1 RateLimiterFilter (`security/RateLimiterFilter.java`)

Runs **before** Spring Security at `DEFAULT_FILTER_ORDER - 1`.

**Algorithm**: sliding window per remote IP.
- A `Caffeine` cache maps each IP to a `ArrayDeque<Long>` (a list of timestamps).
- On each request: remove all timestamps older than 60 seconds from the front of the queue.
- If the remaining count is ≥ 60, return HTTP 429.
- Otherwise, add the current timestamp and let the request through.

```
IP: 10.0.0.1 — deque contains timestamps of last N requests within 60s window
[t-58s, t-45s, t-30s, ... 59 entries total]
→ count = 59, allow, add t-now

Next request from same IP one second later:
→ t-58s falls out of the window, new count = 59, still allow

61st request in the same window:
→ count = 60, BLOCK → 429
```

The health endpoint `/actuator/health` is exempt (`shouldNotFilter`) so Kubernetes liveness probes are never rate-limited.

The `@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)` annotation places this filter **one position before** Spring Security in the filter chain, so unauthenticated probes are rate-limited too.

### 8.2 ApiKeyFilter (`security/ApiKeyFilter.java`)

A `OncePerRequestFilter` — Spring guarantees it runs exactly once per HTTP request (not on redirects/forwards).

**Flow:**
1. Read the `X-API-Key` header.
2. If missing → 401, log `authentication_failure`.
3. Call `ApiKeyService.authenticate(rawKey)`.
4. If no match → 401, log `authentication_failure`.
5. If found → build a `UsernamePasswordAuthenticationToken` with the `ApiKey` object as principal and `ROLE_READ_ONLY` or `ROLE_ADMIN` as the granted authority.
6. Store it in `SecurityContextHolder`.
7. Call `chain.doFilter(...)` to continue.
8. In `finally`: clear the SecurityContext (prevents leaking between requests on the same thread).

The `/actuator/health` endpoint is also exempt here — load balancers check it without credentials.

### 8.3 ApiKeyService (`security/ApiKeyService.java`)

The service that does the BCrypt matching.

**Cache strategy**: `Caffeine` stores the entire list of API keys for 60 seconds under the key `"all"`. This means:
- The database is queried at most once every 60 seconds, not on every request.
- BCrypt matching still happens per request against the cached list.
- If you add/revoke a key, call `invalidateCache()` and the next request will reload.

```java
public Optional<ApiKey> authenticate(String rawKey) {
    List<ApiKey> keys = keyCache.get("all", k -> repository.findAll());
    return keys.stream()
            .filter(key -> !key.isRevoked())
            .filter(key -> key.getExpiresAt() == null || key.getExpiresAt().isAfter(Instant.now()))
            .filter(key -> encoder.matches(rawKey, key.getKeyHash()))
            .findFirst();
}
```

The `.filter(encoder.matches(...))` is the expensive BCrypt step — it happens once per cached key per request. For a small key table (< 100 rows) this is acceptable.

### 8.4 SecurityConfig (`config/SecurityConfig.java`)

Defines the HTTP security rules:

```java
.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
```
SSE responses are completed on an internal async thread (Spring MVC's `DeferredResult`). That async dispatch doesn't carry the original request's `SecurityContext`. Without this rule, the async thread would be blocked by the security filter. Since the original `REQUEST` dispatch was already authenticated, permitting `ASYNC` dispatches is safe.

```java
.requestMatchers("/actuator/health").permitAll()
.requestMatchers("/error").permitAll()
.requestMatchers("/actuator/**").hasRole("ADMIN")
```
- `/actuator/health` — infrastructure health checks, no auth needed
- `/error` — Spring's internal error forwarding endpoint, must not 401-loop
- `/actuator/**` — all other metrics/management endpoints require ADMIN

### 8.5 StartupValidationConfig (`config/StartupValidationConfig.java`)

Enabled only when `mcp.security.enforce-tls=true` (production). On startup it checks:
- The DB URL must contain `sslmode=require`

If the check fails, the application refuses to start with a clear error message. This prevents accidentally deploying to production without encryption.

### 8.6 HmacApiKeyHasher (`security/HmacApiKeyHasher.java`)

API keys are 20+ character random strings — their entropy is already high. BCrypt adds
~250ms per request for no security benefit. HMAC-SHA256 with a server-side pepper
achieves equivalent protection in microseconds (NIST FIPS 198-1).

```java
// pepper from MCP_HMAC_PEPPER (≥ 32 chars, out-of-band secret)
public String hash(String rawKey)            // → 64 lowercase hex chars
public boolean matches(String rawKey, String stored)  // constant-time comparison
```

The pepper is never stored in source — it comes from `MCP_HMAC_PEPPER` env var.

### 8.7 RlsContextAspect (`security/RlsContextAspect.java`)

PostgreSQL Row-Level Security policies enforce CONFIDENTIAL access at the database
level, independently of the application-layer classification filter. The aspect injects
`SET LOCAL app.mcp_role = '<role>'` at the start of each `@Tool` transaction so the
policy can read the current role.

```sql
-- V5 policy: blocks CONFIDENTIAL rows unless app.mcp_role = 'ADMIN'
CREATE POLICY doc_chunks_classification_policy ON document_chunks ...
```

**Important:** `FORCE ROW LEVEL SECURITY` is bypassed by PostgreSQL superusers. In
production, verify `SELECT rolsuper FROM pg_roles WHERE rolname = 'mcpuser'` returns
`false`.

### 8.8 ContentEncryptor (`connector/ContentEncryptor.java`)

Document chunk text is encrypted with AES-256-GCM before storage in PostgreSQL.
Wire format: `[12B random IV][ciphertext + 16B GCM authentication tag]`.

Key source: `MCP_CONTENT_KEY` env var — exactly 64 hex characters (32 bytes).
A wrong or tampered key causes a `SecurityException` (GCM tag mismatch); the
chunk is silently returned as empty rather than surfacing a decryption error to
the LLM.

---

## 9. The three MCP tools

### 9.1 DatabaseQueryTool (`tools/DatabaseQueryTool.java`)

Exposes: `query_database`

```
Parameters:
  table     (required) — "employees" or "document_chunks"
  filters   (optional) — key-value pairs, e.g. {"department": "IT"}
  maxRows   (optional) — 1 to 500, default 100
```

**What happens:**
1. Reads the caller's `ApiKey` from the `SecurityContext`.
2. Clamps `maxRows` to `[1, 500]`.
3. Calls `PostgresConnector.query(...)` which:
   - Validates `table` against a whitelist (blocks SQL injection via table name)
   - Builds the column list based on role (strips `salary` for `READ_ONLY`)
   - Validates each filter column against the allowlist
   - Runs a parameterized `SELECT ... WHERE ... LIMIT ?` query
4. If `PostgresConnector` throws a `SecurityException` (disallowed table or column), increments the `mcp.authz.denials` counter and logs to audit.
5. On success, logs to audit and returns the rows.

**Why the column whitelist matters:**

If you allowed arbitrary filter columns, an attacker could send `{"salary": "60000"}` and probe for exact salary values. By validating filter columns against the same role-visible column list, a `READ_ONLY` caller cannot even filter on columns they're not allowed to see.

### 9.2 DocumentSearchTool (`tools/DocumentSearchTool.java`)

Exposes: `search_documents`

```
Parameters:
  query      (required) — natural language, max 500 chars
  maxResults (optional) — 1 to 10, default 5
```

**What happens:**
1. Validates query length (rejects > 500 chars to block prompt injection via search).
2. Reads the caller's role. Builds the `IN (...)` clause:
   - `READ_ONLY` → `IN ('PUBLIC', 'INTERNAL')`
   - `ADMIN` → `IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL')`
3. Runs a PostgreSQL full-text search query using `plainto_tsquery('french', ?)` against the pre-indexed `text_preview` column. The `?` parameter is passed safely (no string interpolation).
4. For each matching row, fetches the decrypted chunk text from PostgreSQL via `DbContentStore.fetchChunk(UUID)` (AES-256-GCM decryption).
5. Wraps each chunk with trust boundary markers:
   ```
   [EXTERNAL_CONTENT_START]
   ...actual text...
   [EXTERNAL_CONTENT_END]
   ```
   This is a prompt injection mitigation — it signals to the LLM that this content is untrusted external data, not instructions.
6. Returns a list of `DataFragment` records (never raw document bytes).

**Full-text search explained:**

PostgreSQL's `tsvector` is a pre-parsed document representation. `to_tsvector('french', text)` tokenizes French text, removes stop words, and stems words (e.g. "recrutements" → "recrutement"). `plainto_tsquery` does the same to the search query and finds matching documents using an inverted index (GIN), which is very fast.

### 9.3 SourceListTool (`tools/SourceListTool.java`)

Exposes: `list_sources`

No parameters required.

Returns a JSON object describing what the current key can access:
```json
{
  "role": "READ_ONLY",
  "sources": [
    {
      "name": "employees",
      "type": "structured",
      "columns": ["id", "name", "department", "email"]
    },
    {
      "name": "document_chunks",
      "type": "structured",
      "columns": ["id", "doc_name", "classification", "chunk_index", "text_preview", "created_at"]
    },
    {
      "name": "documents (MinIO)",
      "type": "object-storage",
      "accessible_classifications": ["PUBLIC", "INTERNAL"]
    }
  ]
}
```

An `ADMIN` key would see `salary` in the employees columns and `CONFIDENTIAL` in accessible_classifications.

This tool is important for LLMs: before making a query, the AI can ask `list_sources` to know what tables and columns exist — so it doesn't guess and send invalid queries.

---

## 10. Configuration files

### `application.yaml` (production)

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        name: mcp-data-gateway
        version: 1.0.0
        protocol: STREAMABLE      # ← streamable-HTTP transport (SSE)
  datasource:
    url: ${DB_URL}               # ← must be set as an env var
    username: ${DB_USER}
    password: ${DB_PASSWORD}

mcp:
  security:
    enforce-tls: true             # ← forces sslmode=require on startup (SEC-010)
  hmac:
    pepper: ${MCP_HMAC_PEPPER}   # ← ≥ 32 chars, required for HMAC-SHA256 key auth
  content:
    key: ${MCP_CONTENT_KEY}      # ← exactly 64 hex chars (32 bytes), for AES-256-GCM
```

All secrets use `${VAR}` syntax — Spring reads them from environment variables. No secret ever appears in source code.

### `application-dev.yaml` (local development)

Active when `SPRING_PROFILES_ACTIVE=dev`. Overrides specific values from `application.yaml`:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/mcpgateway}
    username: ${DB_USER:mcpuser}
    password: ${DB_PASSWORD:mcppass}

mcp:
  security:
    enforce-tls: false            # ← disables TLS check so http:// works locally
```

The `${VAR:default}` syntax means: use the env var if set, otherwise use the default. This allows docker-compose to override the values via its `environment:` block, while also allowing developers to run the app directly outside Docker with sane defaults.

---

## 11. Running locally

### Prerequisites

- Docker and Docker Compose
- Java 21 (only needed if you want to run tests or build outside Docker)

### Starting the full stack

```bash
docker compose up --build
```

This starts:
1. `postgres` — PostgreSQL on `127.0.0.1:5432`
2. `mcp-gateway` — the Spring Boot app on `127.0.0.1:8080`

All ports are bound to `127.0.0.1` (localhost only) — they are not reachable from other machines on your network.

The gateway uses two environment variables with dev-safe fallbacks in `application-dev.yaml`:
- `MCP_HMAC_PEPPER` — pepper for HMAC-SHA256 key hashing (≥ 32 chars). Default is a dev-only value — **never use the default in production**.
- `MCP_CONTENT_KEY` — 64 hex chars (32 bytes) for AES-256-GCM content encryption. Default is 64 zeros — **never use the default in production**.

### Testing with curl

**Step 1: initialize a session**
```bash
curl -s -D - -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-API-Key: demo-readonly-key-001" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0"}}}'
```

Copy the `Mcp-Session-Id` value from the response headers.

**Step 2: confirm initialized**
```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-API-Key: demo-readonly-key-001" \
  -H "Mcp-Session-Id: <paste-session-id-here>" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
```

**Step 3: call a tool**
```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-API-Key: demo-readonly-key-001" \
  -H "Mcp-Session-Id: <paste-session-id-here>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query_database","arguments":{"table":"employees"}}}'
```

The response body is SSE format — look for the `data:` line which contains the JSON result.

---

## 12. Tests

### Test layers

```
./gradlew test               ← runs unit tests (fast, no Docker)
./gradlew integrationTest    ← runs integration + E2E tests (Docker required)
./gradlew check              ← runs all of the above + coverage + SpotBugs
```

### Unit tests (no Docker, no Spring context)

These use Mockito to replace real collaborators with fakes.

| Test file | What it tests |
|---|---|
| `AuditServiceTest` | Verifies that `AuditService.log()` saves the right fields and increments the `mcp.tool.calls` Micrometer counter. |
| `ApiKeyServiceTest` | Verifies HMAC matching, cache behavior (second call doesn't hit the DB), expiry rejection, revocation rejection. |
| `HmacApiKeyHasherTest` | Verifies round-trip, constant-time comparison, wrong-key rejection, and short-pepper rejection at construction. |
| `RateLimiterFilterTest` | Verifies 60 requests are allowed, the 61st is blocked, and the response is 429. Also checks the `/actuator/health` exemption. |
| `ContentEncryptorTest` | Verifies round-trip, random IV (two encryptions differ), tampered bytes → SecurityException, wrong key → SecurityException, short key → IllegalStateException. |
| `DbContentStoreTest` | Verifies decryption via mocked JdbcTemplate, null column → empty, long text truncated to 500 chars, tampered/error → empty. |
| `PostgresConnectorTest` | Verifies the column allowlist, role-based column removal, filter validation, and that an unknown table throws `SecurityException`. |

### Integration tests (Testcontainers: real PostgreSQL)

These extend `AbstractIntegrationTest`, which:
1. Uses `@Testcontainers` to start a real PostgreSQL container
2. Uses `@DynamicPropertySource` to tell Spring the container's URL

Flyway runs the migrations (`V1`, `V2`, `V3`) against the test PostgreSQL container automatically on startup.

| Test file | What it tests |
|---|---|
| `ApiKeyFilterIT` | HTTP 401 when X-API-Key is missing or wrong. Uses MockMvc to send requests through the filter chain. |
| `ApiKeyLifecycleIT` | Inserts a test key with BCrypt-4, verifies it works, then sets `expires_at` to the past and verifies it returns 401. Does the same for `revoked=true`. |
| `RateLimiterFilterIT` | Sends 60 requests from a unique IP (allowed), then a 61st (blocked as 429). Each test method uses a different fake IP to avoid cross-test state. |
| `AuditLogIT` | Calls `DatabaseQueryTool` directly (via `TestSecurityHelper` to set the security context), then queries the `audit_logs` table to verify the row was written. Also verifies that trying to delete an audit log row throws an exception (the trigger). |

### End-to-end test (`McpEndToEndIT`)

Uses `WebTestClient` (Reactor Netty) to make real HTTP requests through the full running application. Exercises the complete MCP protocol handshake and verifies:
- `tools/list` returns all three tools
- Missing/invalid API keys return 401
- `/actuator/health` works without auth
- `READ_ONLY` cannot see `salary` or `CONFIDENTIAL` documents
- `ADMIN` can see both
- Department filter works end-to-end
- SQL injection in table name does not cause a 500
- Error responses do not leak Java stack traces

See `DEVELOPER_GUIDE.md` section on E2E tests for a deeper walkthrough of this file.

---

## 13. Build tooling and code quality

### Gradle tasks reference

```bash
./gradlew build              ← compiles, runs unit tests, runs SpotBugs, Checkstyle
./gradlew test               ← unit tests only
./gradlew jacocoTestReport   ← generates coverage report at build/reports/jacoco/
./gradlew jacocoTestCoverageVerification  ← fails if coverage < 70%
./gradlew spotbugsMain       ← static analysis (security bugs, null pointers, etc.)
./gradlew dependencyCheckAnalyze  ← CVE scan (needs NVD_API_KEY env var)
./gradlew verifyHashes       ← confirm that demo key values match their BCrypt hashes
```

### JaCoCo — code coverage

Coverage reports are generated at `build/reports/jacoco/test/html/index.html`. Open in a browser to see which lines are covered.

The build fails if coverage drops below 70%. This threshold is in `build.gradle.kts`:
```kotlin
minimum = "0.70".toBigDecimal()
```

### SpotBugs + FindSecBugs

SpotBugs analyzes compiled `.class` files for common bugs. `FindSecBugs` is a plugin that adds security-specific checks (SQL injection patterns, XSS, etc.).

Results at: `build/reports/spotbugs/main.xml`

Suppressions (known false positives) are in `config/spotbugs/exclude.xml`.

### OWASP Dependency Check

Scans all dependencies against the NVD (National Vulnerability Database). Fails the build if any dependency has a CVE with CVSS score ≥ 7.0.

Requires the `NVD_API_KEY` environment variable (free registration at nvd.nist.gov).

Results at: `build/reports/dependency-check-report.html`

### `verifyHashes` task

The demo BCrypt hashes in `V2__seed.sql` were generated from real key values. The `verifyHashes` Gradle task lets you confirm they still match:

```bash
DEMO_READONLY_KEY=demo-readonly-key-001 DEMO_ADMIN_KEY=demo-admin-key-001 ./gradlew verifyHashes
```

Key values are read from environment variables — never from source code.

---

## 14. Common mistakes and their fixes

### "tools/list returns an empty array"

**Cause**: Spring AI's auto-scanner looks for the community `@McpTool` annotation. This project uses Spring AI's own `@Tool` annotation instead. The auto-scanner doesn't find anything.

**Fix**: `McpConfig` explicitly registers the tools via `MethodToolCallbackProvider`:
```java
@Bean
public ToolCallbackProvider mcpTools(...) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(databaseQueryTool, documentSearchTool, sourceListTool)
            .build();
}
```

### "SSE tool call responses return 403"

**Cause**: When Spring MVC sends an SSE response using `DeferredResult`, the actual HTTP write happens on an async thread. That thread doesn't have the `SecurityContext` from the original request thread. Spring Security treats it as unauthenticated.

**Fix**: `SecurityConfig` permits `DispatcherType.ASYNC`:
```java
.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
```

### "docker-compose env vars are ignored — app uses hardcoded values"

**Cause**: `application-dev.yaml` had hardcoded values like `url: jdbc:postgresql://localhost:5432/mcpgateway`. Spring's `${VAR:default}` syntax was not used, so Docker environment variables had no effect.

**Fix**: Use fallback syntax:
```yaml
url: ${DB_URL:jdbc:postgresql://localhost:5432/mcpgateway}
```
Now docker-compose's `DB_URL` env var overrides the default when running in containers.

### "The application crashes on startup with 'DB_URL must contain sslmode=require'"

**Cause**: `StartupValidationConfig` is active, meaning `mcp.security.enforce-tls=true`. You are using a plain JDBC URL without SSL.

**Fix**: Either add `?sslmode=require` to your `DB_URL`, or set `mcp.security.enforce-tls=false` in your local config (already done in `application-dev.yaml`).

### "Application fails to start: MCP_CONTENT_KEY must be exactly 64 hex characters"

**Cause**: `ContentEncryptor` validates `MCP_CONTENT_KEY` at construction. The value is either missing, too short, or contains non-hex characters.

**Fix**: Generate a valid key with `openssl rand -hex 32` (produces exactly 64 hex characters). Set it as `MCP_CONTENT_KEY` in your environment. In dev, the `application-dev.yaml` fallback (64 zeros) is used if the env var is not set — but **never use the all-zeros key in production**.

**YAML gotcha**: if you hardcode a hex value in a YAML file, quote it:
```yaml
mcp:
  content:
    key: "0101010101010101010101010101010101010101010101010101010101010101"
```
Without quotes, SnakeYAML (YAML 1.1) interprets sequences of `0`s and `1`s as octal integers.

### "Application fails to start: MCP_HMAC_PEPPER must be ≥ 32 chars"

**Cause**: `HmacApiKeyHasher` validates the pepper at construction. A short pepper weakens the HMAC to a predictable value.

**Fix**: Set `MCP_HMAC_PEPPER` to a random string of at least 32 characters (`openssl rand -hex 32` works). In dev, the `application-dev.yaml` fallback is used automatically.

### "Test fails: audit log row not found immediately after tool call"

**Cause**: `AuditService.log()` is annotated with `@Async`. The audit write happens on a background thread. The test assertion may run before the write completes.

**Fix**: Use `Awaitility` to poll until the row appears:
```java
await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
    assertThat(auditLogRepository.findAll()).hasSizeGreaterThanOrEqualTo(1)
);
```
