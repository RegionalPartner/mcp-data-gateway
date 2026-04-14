# MCP Data Gateway — Architecture & Engineering Reference

> For senior developers: design decisions, trade-offs, security model, operational concerns,
> and extension points. Assumes familiarity with Spring Boot and distributed systems.

---

## Table of Contents

1. [Design goals and non-goals](#1-design-goals-and-non-goals)
2. [Security model](#2-security-model)
3. [Request pipeline internals](#3-request-pipeline-internals)
4. [Data access layer design](#4-data-access-layer-design)
5. [Async and SSE complexity](#5-async-and-sse-complexity)
6. [Caching strategy](#6-caching-strategy)
7. [Audit subsystem](#7-audit-subsystem)
8. [Spring AI MCP internals](#8-spring-ai-mcp-internals)
9. [Performance characteristics](#9-performance-characteristics)
10. [Adding a new tool](#10-adding-a-new-tool)
11. [Operational runbook](#11-operational-runbook)
12. [Known limitations and future work](#12-known-limitations-and-future-work)
13. [Security findings index (SEC-XXX)](#13-security-findings-index-sec-xxx)

---

## 1. Design goals and non-goals

### Goals

- **Least-privilege data exposure**: an LLM calling a tool should receive the minimum data
  consistent with the caller's role. No post-processing filter, no client-side enforcement —
  the allowlist is enforced at query build time.
- **Append-only audit trail**: every tool invocation is recorded in two independent sinks —
  the PostgreSQL `audit_logs` table (trigger-enforced append-only) and a structured JSON file
  (`/var/log/mcp/audit.json`) outside PostgreSQL's reach. Both must be compromised simultaneously
  to erase an audit trail entry.
- **Defense in depth**: no single layer is trusted to be the only gate. Rate limiting,
  authentication, authorization, input validation, and output sanitization are all independent.
- **Fail closed**: startup aborts if TLS is not configured in production
  (`StartupValidationConfig`). A misconfigured deployment produces an immediate, loud error
  rather than silently operating in an insecure mode.
- **No secrets in source**: all credentials come from environment variables. The `verifyHashes`
  Gradle task validates demo hashes without embedding raw key values anywhere in the repo.

### Non-goals

- **Multi-tenant isolation**: all API keys share the same database schema. Row-level security
  is enforced at two layers — application code (classification filter) and PostgreSQL RLS
  policies (V5 migration). Good enough for a controlled internal deployment; insufficient for
  a public SaaS where schema-level tenant isolation would be required.
- **Horizontal scaling of rate limiting**: `RateLimiterFilter` uses a JVM-local Caffeine cache.
  A load-balanced deployment would require a distributed counter (Redis `INCR` + TTL).
- **Real-time key revocation**: `ApiKeyService` caches the key list for 60 seconds. A revoked
  key can continue to authenticate for up to one minute. This is an intentional trade-off
  (see [Caching strategy](#6-caching-strategy)).
- **Streaming tool results**: tools return a complete list. Progressive streaming of rows is
  not supported in the current Spring AI MCP version.

---

## 2. Security model

### Threat model summary

| Threat | Control |
|---|---|
| Unauthenticated access | `ApiKeyFilter` — 401 before any business logic |
| Brute-force key enumeration | HMAC-SHA256 (server-side pepper) + 60 req/min rate limit |
| Horizontal privilege escalation (READ_ONLY sees ADMIN data) | Column + classification allowlist enforced at SQL build time |
| SQL injection via table name | `ALLOWED_TABLES` Set whitelist in `PostgresConnector` |
| SQL injection via filter values | Parameterized query (`?` placeholders, `jdbc.queryForList(sql, args)`) |
| SQL injection via filter column names | Column name validated against role-visible allowlist before inclusion |
| Content data exfiltration via DB dump | AES-256-GCM encryption at rest; key separate from DB credentials |
| Prompt injection via document content | `[EXTERNAL_CONTENT_START/END]` trust boundary framing |
| Prompt injection via oversized query | `query.length() > 500` check before processing |
| Stack trace leakage in error responses | Spring's default error handler + no stack trace in MCP error result |
| Sensitive data in logs | Parameters logged as structured fields; salary values never appear in params |
| Audit trail tampering | DB trigger (append-only) + `chattr +a` JSON file outside PostgreSQL |
| Cleartext credentials in transit | `StartupValidationConfig` enforces `sslmode=require` at startup |
| CVEs in dependencies | OWASP Dependency Check, fails on CVSS ≥ 7.0 |
| Exposed management endpoints | `/actuator/**` requires `ROLE_ADMIN`; only `/actuator/health` is public |
| Container ports exposed on LAN | `127.0.0.1:PORT:PORT` binding in docker-compose |

### Authentication flow in detail

```
raw key (header)
      │
      ▼
ApiKeyService.authenticate(rawKey)
      │
      ├─ 1. Load List<ApiKey> from Caffeine cache (or DB if expired)
      │
      ├─ 2. For each key in list:
      │       filter: !revoked
      │       filter: expiresAt == null || expiresAt.isAfter(now)
      │       filter: HmacApiKeyHasher.matches(rawKey, key.keyHash)
      │                      ← microseconds; constant-time comparison
      │
      └─ 3. Return first match (Optional.empty() if none)
```

**HMAC-SHA256 vs BCrypt**: API keys are 20+ character random strings (entropy ≈ 128 bits).
BCrypt is designed for low-entropy passwords; its slowness adds latency without security gain
when the input is already high-entropy. HMAC-SHA256 with a 32-byte server-side pepper is
equivalent protection (NIST SP 800-63B, FIPS 198-1) at microsecond cost.

The pepper (`MCP_HMAC_PEPPER`) is the secret, not the algorithm. Rotation procedure:
1. Generate a new pepper
2. Re-hash all existing key values against the new pepper (V4-style migration)
3. Deploy with the new pepper value

### Why no JWT?

JWTs are stateless — revocation requires a blocklist (which reintroduces statefulness) or
waiting for expiry. BCrypt-hashed API keys stored in the database allow immediate revocation
by setting `revoked=true`, with propagation bounded by the cache TTL (60 seconds). For an
internal gateway where revocation latency matters, this is a better trade-off.

### The `ASYNC` dispatcher permit

Spring MVC SSE responses use `DeferredResult`. The SSE writer runs on a Tomcat async thread
after the original request thread has returned. Under `SessionCreationPolicy.STATELESS`, Spring
Security does not propagate the `SecurityContext` to the async thread — it creates a new,
empty context for each dispatch.

Without `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()`, the SSE finalisation
triggers another round of security evaluation. Since the async thread has no credentials, it
would be rejected with 403, which manifests as a truncated or empty SSE response body.

The fix is safe: the `REQUEST` dispatch was already fully authenticated and authorized. The
`ASYNC` dispatch is an internal Spring mechanism, not a new external request. It is never
reachable from outside the JVM.

---

## 3. Request pipeline internals

### Filter ordering

```
Servlet Container (Tomcat)
  └── FilterChain
        ├── [ORDER: DEFAULT-1] RateLimiterFilter       ← @Order(DEFAULT_FILTER_ORDER - 1)
        ├── [ORDER: DEFAULT]   Spring Security filters
        │     ├── SecurityContextPersistenceFilter
        │     ├── ... (standard Spring Security chain)
        │     └── ApiKeyFilter                         ← addFilterBefore(UsernamePasswordAuthenticationFilter)
        └── DispatcherServlet
              └── Spring AI MCP RouterFunction
                    └── @Tool method (DatabaseQueryTool etc.)
```

`RateLimiterFilter` runs **outside** Spring Security's filter chain. It uses
`@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)` to register directly with the servlet
container. This means:
- It applies to all requests, including those that fail authentication
- An unauthenticated brute-force probe is rate-limited before it ever reaches BCrypt
- The `/actuator/health` exemption must be duplicated in both `RateLimiterFilter.shouldNotFilter`
  and `ApiKeyFilter.shouldNotFilter`

`ApiKeyFilter` is registered with Spring Security via
`addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)`. It runs within
Spring Security's internal chain after the security context is set up, but before the default
form-login filter that it replaces.

### SecurityContext lifetime

```
REQUEST dispatch:
  ApiKeyFilter.doFilterInternal()
    → setAuthentication(apiKey)          sets context
    → chain.doFilter()                   downstream code can call currentApiKey()
    → [finally] SecurityContextHolder.clearContext()   cleared after response

ASYNC dispatch (SSE finalisation):
  SecurityContextHolder has an EMPTY context (stateless policy)
  → .dispatcherTypeMatchers(ASYNC).permitAll() skips security evaluation entirely
  → SSE bytes are written and connection is closed
```

This means tool methods can safely call `SecurityContextHolder.getContext().getAuthentication()`
during `REQUEST` dispatches, but should never depend on the security context being populated
during `ASYNC` dispatches. In practice this is not an issue because tool method bodies run
entirely during the `REQUEST` dispatch — the `ASYNC` dispatch only performs I/O.

---

## 4. Data access layer design

### PostgresConnector: column allowlist architecture

```java
private static final Map<String, List<String>> ALL_COLUMNS = Map.of(
    "employees",       List.of("id", "name", "department", "email", "salary"),
    "document_chunks", List.of("id", "doc_name", "classification", ...)
);

private static final Map<String, Set<String>> ROLE_HIDDEN_COLUMNS = Map.of(
    "employees", Set.of("salary")
);
```

**Design choice**: The column list is hardcoded in the application, not derived from database
metadata at runtime. This is intentional:
- No risk of a new database column being accidentally exposed before the code is updated
- The allowlist is auditable in source code
- No risk of SQL injection via schema introspection

**Trade-off**: adding a new column to a table requires a code change alongside the migration.
This is acceptable for a security-sensitive internal gateway.

**Filter column validation:**

```java
for (Map.Entry<String, String> entry : filters.entrySet()) {
    String col = entry.getKey();
    if (!allowedCols.contains(col)) {
        throw new SecurityException("Access denied");
    }
    conditions.add(col + " = ?");
    args.add(entry.getValue());
}
```

Column names are validated against `allowedCols` (already role-filtered) before being
concatenated into the SQL string. Values are always passed as `?` parameters.
This prevents both:
- Column name injection (e.g. `"salary; DROP TABLE"`)
- Blind data extraction through filter values on hidden columns

### DbContentStore: encrypted content in PostgreSQL (SEC-ENC)

Document chunk text is encrypted with AES-256-GCM before storage in the
`document_chunks.encrypted_content BYTEA` column.

**Wire format:** `[12B random IV][ciphertext + 16B GCM authentication tag]`

```java
// ContentEncryptor — no external service, no extra authentication domain
public byte[] encrypt(String plaintext)  // fresh SecureRandom IV per call
public String decrypt(byte[] bytes)      // throws SecurityException on tag mismatch
```

**Key rotation**: generate a new 32-byte key, re-encrypt all rows in a migration (analogous
to V6), deploy with the new `MCP_CONTENT_KEY`. The `text_preview` column (unencrypted 500-char
excerpt) is intentionally kept unencrypted — it is used only for FTS indexing and contains
no more data than what appears in search results.

**Why not pgcrypto?** `pgcrypto` moves the key into the database engine, reducing the attack
surface value of application-layer encryption. If the database credentials are compromised,
pgcrypto-encrypted data is also compromised. Application-layer encryption keeps the key
separate from the ciphertext.

### DocumentSearchTool: full-text search architecture

```sql
WHERE classification IN ('PUBLIC', 'INTERNAL')
  AND to_tsvector('french', coalesce(text_preview, '')) @@ plainto_tsquery('french', ?)
```

Two important points:

**The classification filter is built as a string literal, not a parameter:**
```java
List<String> allowedClassifications = role.canAccessConfidential()
    ? List.of("'PUBLIC'", "'INTERNAL'", "'CONFIDENTIAL'")
    : List.of("'PUBLIC'", "'INTERNAL'");
String inClause = String.join(", ", allowedClassifications);
```

This is safe because the values come from internal code, not user input. PostgreSQL's
parameterized query mechanism does not support parameterizing `IN (?, ?, ?)` with a
variable-length list cleanly in JDBC. The alternative (dynamically generating the right number
of `?` placeholders) adds complexity for no security benefit when the values are compile-time
constants.

**`text_preview` vs `encrypted_content`**: the database stores a 500-char excerpt in
`text_preview` for full-text indexing (unencrypted, because FTS requires plaintext). The full
content lives in `encrypted_content BYTEA`. The search query matches against `text_preview`
(fast, GIN-indexed), then fetches and decrypts the full content from the same row. This keeps
all data in one database, while maintaining encryption for the content that an AI processes.

**`plainto_tsquery` vs `to_tsquery`**: `plainto_tsquery` is used because it accepts natural
language input and tokenizes it safely. `to_tsquery` requires pre-formatted tsquery syntax
(`word1 & word2 | word3`) and would throw a syntax error on arbitrary user input.

---

## 5. Async and SSE complexity

### Spring AI MCP streamable-HTTP transport

Spring AI's MCP server uses `RouterFunction`-based routing (not `@Controller`). The route
handler is registered by `McpServerWebMvcAutoConfiguration` and handles all `POST /mcp`
requests. It returns a `ServerResponse` that may be:
- A plain `application/json` response (for `initialize` and `notifications/initialized`)
- A `text/event-stream` response (for `tools/list` and `tools/call`)

For SSE responses, Spring MVC uses `DeferredResult<ServerResponse>`. The SSE bytes are
written on Tomcat's async thread pool after the request-processing thread has returned the
`DeferredResult` to the container.

### Why WebTestClient is required in tests

```
TestRestTemplate (JDK HttpClient) behavior:
  POST /mcp tools/call
    → response headers received (Content-Type: text/event-stream)
    → JDK async receiver starts reading
    → Spring MVC commits response on async thread
    → JDK receiver interprets async thread completion as end-of-stream
    → stream closed prematurely
    → body is empty or truncated

WebTestClient (Reactor Netty) behavior:
  POST /mcp tools/call
    → response headers received
    → Reactor Netty buffer reads all chunks until connection close
    → full SSE body available for assertion
```

This is documented in `McpEndToEndIT`'s class Javadoc. The root cause is the JDK's HTTP client
treating `Connection: close` or async response commits as premature EOF, while Reactor Netty
correctly reads until the server closes the connection.

The `spring-boot-starter-webflux` dependency (added to `testImplementation` in `build.gradle.kts`)
pulls in Reactor Netty solely for use by `WebTestClient` in tests. The production application
does not use WebFlux.

---

## 6. Caching strategy

### ApiKeyService: single-entry Caffeine cache

```java
private final Cache<String, List<ApiKey>> keyCache = Caffeine.newBuilder()
    .expireAfterWrite(60, TimeUnit.SECONDS)
    .maximumSize(1)
    .build();

List<ApiKey> keys = keyCache.get("all", k -> repository.findAll());
```

**Why a cache at all?** Even though HMAC-SHA256 is fast (microseconds), the cache avoids a
`repository.findAll()` DB round-trip on every request. Without caching, every request triggers
a full table scan of `api_keys`. For the typical key table (< 100 rows) this is minor, but the
pattern also ensures cache consistency at the same 60-second granularity as before — unchanged
by the BCrypt→HMAC migration.

**Why a single entry keyed on `"all"`?** The key table is small (< 100 rows in any realistic
deployment). The simplest correct approach is to load all keys and BCrypt-match in memory.
This avoids exposing `findByKeyHash` which would require storing a fast lookup index
(defeating BCrypt's value) or timing-safe lookup by prefix.

**Consistency trade-off**: after `invalidateCache()` is called, the next request reloads from
the database. Callers that are mid-BCrypt when `invalidateCache()` is called will complete
against the old list — this is safe (at worst they get an extra successful auth before the
next refresh). The 60-second window for revocation propagation is documented as acceptable.

**Cache size = 1**: `maximumSize(1)` ensures only one entry (`"all"`) ever exists. If
`invalidateCache()` races with a cache miss, Caffeine's loading semantics ensure only one
DB query fires (subsequent concurrent misses wait for the first loader to complete).

### RateLimiterFilter: per-IP sliding window

```java
private final Cache<String, ArrayDeque<Long>> requestLog = Caffeine.newBuilder()
    .expireAfterAccess(2, TimeUnit.MINUTES)
    .maximumSize(10_000)
    .build();
```

**`expireAfterAccess(2, TimeUnit.MINUTES)`**: IPs that go quiet for 2 minutes have their
timestamp queues evicted, freeing memory. A 10,000-entry limit bounds worst-case memory to
approximately 10,000 × (60 timestamps × 8 bytes) = ~5MB.

**Thread safety**: the `ArrayDeque` is synchronized explicitly:
```java
synchronized (times) {
    while (!times.isEmpty() && times.peekFirst() < cutoff) times.pollFirst();
    if (times.size() >= MAX_REQUESTS_PER_WINDOW) { ... return; }
    times.addLast(now);
}
```

Caffeine's cache itself is thread-safe for concurrent `get()` calls. The `synchronized`
block is needed because the read-modify-write on the `ArrayDeque` is not atomic. Multiple
threads from the same IP could otherwise both pass the `size >= 60` check.

**Limitation**: `request.getRemoteAddr()` returns the direct TCP connection source IP. Behind
a reverse proxy or load balancer, this is the proxy's IP, not the real client. To rate-limit
real clients behind a proxy, you would need to trust and parse `X-Forwarded-For` — which
introduces its own spoofing risks if not validated against known proxy IPs.

---

## 7. Audit subsystem

### Write path (dual-sink)

```
Tool method
  → auditService.log(toolName, apiKeyId, params, summary)
        │
        │ @Async("auditExecutor")
        ▼
  ThreadPoolTaskExecutor "audit-"
    coreSize=2, maxSize=4, queue=500
    RejectedExecutionHandler=CallerRunsPolicy
        │
        ├─ AuditLogRepository.save(entry)   ← INSERT INTO audit_logs (SEC-020 trigger)
        │
        └─ auditLog.info("audit_event")     ← Logback AUDIT logger → /var/log/mcp/audit.json
```

**Two independent sinks** (SEC-AUDIT2): the PostgreSQL table is append-only via a row-level
trigger; a database superuser with `DISABLE TRIGGER ALL` privilege can bypass it. The JSON
file is written by the JVM process and hardened with `chattr +a` (ext4/xfs append-only flag)
in production — a superuser cannot overwrite it without root access to the filesystem.
The `additivity="false"` on the AUDIT logger keeps audit events out of the console log.

**`@Async("auditExecutor")`**: audit writes are decoupled from the request thread. The tool
method returns to the MCP server and the SSE response begins writing before the audit INSERT
completes. This prevents slow database writes from adding latency to the tool call response.

**`CallerRunsPolicy`**: if all 4 threads are busy and the 500-entry queue is full, the
calling thread performs the audit write synchronously. This means:
- Audit entries are **never silently dropped** (no `DiscardPolicy`)
- Under extreme saturation, tool calls degrade gracefully rather than losing audit records
- Back-pressure naturally limits request throughput when the audit system is overwhelmed

**`@PrePersist` timestamp**: `AuditLog.onCreate()` sets `timestamp = Instant.now()` in the
JPA layer, not in SQL. The SQL `DEFAULT now()` in the schema provides a database-side
fallback for direct inserts. The application-side timestamp is authoritative and easier to
mock in unit tests.

### Immutability enforcement

```sql
-- V3__key_lifecycle.sql
CREATE OR REPLACE FUNCTION prevent_audit_modification()
    RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: ...';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
```

The trigger fires at the database level regardless of which connection attempts the
modification. The application user cannot DROP TRIGGER or DROP FUNCTION without `SUPERUSER`
privilege. In production, the application should connect as a non-superuser with only
`INSERT` privilege on `audit_logs` and `SELECT`/`INSERT`/`UPDATE` on other tables.

**`AuditLogIT` verifies this:**
```java
assertThatThrownBy(() ->
    jdbc.update("DELETE FROM audit_logs WHERE id = ?", logId))
    .isInstanceOf(DataAccessException.class)
    .hasMessageContaining("append-only");
```

---

## 8. Spring AI MCP internals

### Tool registration

Spring AI 1.0 supports two auto-registration paths:

1. `McpServerAnnotationScannerAutoConfiguration` — scans for
   `@org.springaicommunity.mcp.annotation.McpTool` (community annotation)
2. `ToolCallbackConverterAutoConfiguration` — consumes `ToolCallbackProvider` beans

This project uses `@org.springframework.ai.tool.annotation.Tool` (Spring AI's own annotation).
This annotation is **not** picked up by `McpServerAnnotationScannerAutoConfiguration`.
Without the explicit `ToolCallbackProvider` bean in `McpConfig`, `tools/list` returns `[]`.

The explicit registration:
```java
@Bean
public ToolCallbackProvider mcpTools(DatabaseQueryTool db, DocumentSearchTool doc, SourceListTool src) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(db, doc, src)
            .build();
}
```

`MethodToolCallbackProvider` reflects over the passed beans, finds `@Tool`-annotated methods,
and wraps them in `MethodToolCallback` instances. These are returned to
`ToolCallbackConverterAutoConfiguration`, which registers them with the MCP server.

### `name = "..."` on `@Tool`

Without explicit names, Spring AI generates tool names from the method name using camelCase:
`queryDatabase`, `searchDocuments`, `listSources`. The E2E tests assert on `query_database`,
`search_documents`, `list_sources` (snake_case). The explicit `name` attribute controls the
advertised name in `tools/list`.

### Session lifecycle

The MCP session is managed entirely by Spring AI. The session ID returned in the
`Mcp-Session-Id` response header is an opaque token. There is no application-level session
state associated with it — the gateway is fully stateless per request. The session ID is
required by the MCP protocol but is not used for authentication or context tracking by this
application.

### `protocol: STREAMABLE` vs `transport: streamable-http`

`application.yaml` uses `spring.ai.mcp.server.protocol: STREAMABLE`. Earlier Spring AI
milestones used `transport: streamable-http`. The correct key for Spring AI 1.1.x is
`protocol`, bound to `McpServerStreamableHttpProperties`. Using the old key results in the
SSE endpoint not being registered, so all tool calls return 404.

### Protocol version 2025-11-25 and `McpProtocolVersionConfig`

**Background.** The MCP spec has three protocol versions: `2025-03-26`, `2025-06-18`, and
`2025-11-25`. Claude Code ≥ 2.1.104 requires `2025-11-25` and disconnects if the server
responds with an older version.

**Root cause.** Spring AI 1.1.4 bundles MCP SDK 0.17.0.
`WebMvcStreamableServerTransportProvider.protocolVersions()` is hardcoded to return
`["2024-11-05", "2025-03-26", "2025-06-18"]`. The server's `initialize` handler reads this
list from the transport on construction and rejects any version not in it.

**Why not upgrade to Spring AI 2.x?** Spring AI 2.0.0-M4 uses Jackson 3.x (`tools.jackson`)
and targets Spring Boot 4.x. Our stack (Spring Boot 3.5.0) uses Jackson 2.x — the two
major versions conflict at runtime with `NoSuchFieldError` in `DeserializerCache`.

**Why not MCP SDK 0.18.x directly?** SDK 0.18.x removed `McpJsonMapper.createDefault()`,
which is called by `org.springaicommunity:mcp-annotations:0.8.0` (a Spring AI 1.1.4 transitive
dependency). Upgrading to 0.18.x produces `NoSuchMethodError` at startup.

**Solution — two steps:**

1. **`build.gradle.kts`** pins all `io.modelcontextprotocol.sdk` artefacts to `0.17.2` via
   `dependencyManagement`. SDK 0.17.2 adds the `MCP_2025_11_25` constant to `ProtocolVersions`
   and keeps `McpJsonMapper.createDefault()`, preserving binary compatibility with
   `mcp-annotations:0.8.0`.

2. **`McpProtocolVersionConfig`** is a `BeanPostProcessor` that runs after Spring creates the
   `McpSyncServer` bean. It reflectively appends `"2025-11-25"` to the
   `McpAsyncServer.protocolVersions` field (which is `private` but not `final`):

   ```java
   Field versionsField = McpAsyncServer.class.getDeclaredField("protocolVersions");
   versionsField.setAccessible(true);
   List<String> extended = new ArrayList<>((List<String>) versionsField.get(asyncServer));
   extended.add("2025-11-25");
   versionsField.set(asyncServer, extended);
   ```

   CGLIB proxying is not viable because `WebMvcStreamableServerTransportProvider` has a
   private constructor — `Enhancer.filterConstructors` throws before Objenesis can
   bypass it.

**Startup log entry confirming the patch was applied:**
```
INFO McpProtocolVersionConfig : MCP server protocol versions extended to: [2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25]
```

**If Spring AI is ever upgraded to a version compatible with Spring Boot 3.x that natively
supports `2025-11-25`**, `McpProtocolVersionConfig` can be deleted — it is idempotent
(`contains()` check) and the startup log line confirms whether the patch is active.

---

## 9. Performance characteristics

### Latency budget per request

| Stage | Typical latency | Notes |
|---|---|---|
| RateLimiterFilter | < 1ms | In-memory ArrayDeque scan |
| ApiKeyFilter (cache hit) | < 1ms | HMAC-SHA256 `matches()` × N keys |
| ApiKeyFilter (cache miss) | DB latency + < 1ms | Rare (once per 60 seconds) |
| RlsContextAspect | < 1ms | `SET LOCAL` within existing transaction |
| PostgreSQL query (employees) | 2–10ms | Indexed; 5 rows |
| PostgreSQL FTS (documents) | 5–20ms | GIN index on tsvector |
| PostgreSQL content fetch + AES decrypt | 1–5ms per chunk | In-process decryption |
| AuditService.log | async | Does not block response |
| Spring AI SSE serialisation | 1–5ms | JSON serialisation |
| **Total (typical tool call)** | **~15–50ms** | No longer BCrypt-dominated |

HMAC-SHA256 reduces authentication latency from ~300ms to < 1ms. The dominant cost is now
PostgreSQL query latency for `maxResults` chunk fetches, which are sequential in the loop.

### Throughput ceiling

With HMAC at < 1ms and the Caffeine cache, authentication is no longer the bottleneck:
- Database connection pool (HikariCP default: 10 connections) is the likely ceiling
- Rate limiter caps at 60 req/min = 1 req/s per IP for any single client
- For many clients, Tomcat thread pool (200 threads) and DB pool are the ceilings

---

## 10. Adding a new tool

### Step 1: create the tool class

```java
@Component
public class MyNewTool {

    private final AuditService auditService;

    public MyNewTool(AuditService auditService) {
        this.auditService = auditService;
    }

    @Tool(name = "my_tool", description = "What this tool does.")
    public MyResult myTool(
            @ToolParam(description = "Input parameter", required = true) String input) {

        ApiKey apiKey = currentApiKey();

        // ... business logic ...

        auditService.log("my_tool", apiKey.getId(), Map.of("input", input), "summary");
        return new MyResult(...);
    }

    private ApiKey currentApiKey() {
        return (ApiKey) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

### Step 2: register with the MCP server

```java
// McpConfig.java
@Bean
public ToolCallbackProvider mcpTools(DatabaseQueryTool db, DocumentSearchTool doc,
                                      SourceListTool src, MyNewTool myNew) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(db, doc, src, myNew)
            .build();
}
```

### Step 3: update SourceListTool

`SourceListTool.listSources()` hardcodes the list of available sources. Add your new tool's
data source to the list so the LLM can discover it.

### Step 4: write tests

- **Unit test** (`MyNewToolTest`): mock all collaborators, test business logic
- **Integration test** extending `AbstractIntegrationTest`: test against real DB/MinIO
- **E2E assertion in McpEndToEndIT**: add one assertion to `toolsList_validKey_returnsAllThreeTools`
  (rename it to `...AllFourTools` or similar)

### Checklist

- [ ] Tool name is snake_case (matches LLM expectations)
- [ ] All inputs are validated (length, allowlist, type)
- [ ] Role check implemented if data sensitivity varies
- [ ] `auditService.log()` called on every invocation (including error paths)
- [ ] Output never contains raw credentials, stack traces, or internal paths
- [ ] `SourceListTool` updated
- [ ] Unit + integration + E2E tests added

---

## 11. Operational runbook

### Revoking an API key

```sql
UPDATE api_keys SET revoked = TRUE WHERE label = 'Some Client Label';
```

The change propagates within 60 seconds (Caffeine TTL). If immediate effect is required,
call `apiKeyService.invalidateCache()` via an admin endpoint (not currently implemented —
add a `DELETE /admin/keys/cache` endpoint behind `hasRole("ADMIN")` if needed).

### Rotating an API key

1. Generate a new key value (e.g. `openssl rand -hex 32`)
2. Hash it: `DEMO_READONLY_KEY=<new-key> ./gradlew verifyHashes` (or a similar utility)
3. Insert the new key hash with the same role
4. Communicate the new raw key to the client out-of-band
5. Once the client has migrated, revoke the old key

Never update an existing `key_hash` in place — the trigger does not protect `api_keys`,
but auditing key rotation via separate INSERT + UPDATE (revoke old) is more auditable.

### Reading audit logs

```sql
-- Last 50 tool calls by a specific key
SELECT a.tool_name, a.params_json, a.result_summary, a.timestamp
FROM audit_logs a
JOIN api_keys k ON k.id = a.api_key_id
WHERE k.label = 'Demo Read-Only Client'
ORDER BY a.timestamp DESC
LIMIT 50;

-- Failed authentication attempts in the last hour
SELECT params_json->>'ip' AS source_ip, COUNT(*) AS attempts
FROM audit_logs
WHERE tool_name = 'authentication_failure'
  AND timestamp > now() - INTERVAL '1 hour'
GROUP BY 1
ORDER BY 2 DESC;

-- Authorization denials (potential privilege escalation probes)
SELECT a.params_json, a.timestamp, k.label
FROM audit_logs a
JOIN api_keys k ON k.id = a.api_key_id
WHERE a.tool_name = 'authz_denial'
ORDER BY a.timestamp DESC;
```

### Monitoring (Micrometer counters)

| Metric | Alert threshold | Interpretation |
|---|---|---|
| `mcp.auth.failures` rate | > 10/min sustained | Brute-force probe or misconfigured client |
| `mcp.rate.limit.exceeded` rate | > 5/min | Client exceeding quota or DDoS |
| `mcp.authz.denials{tool=query_database}` | Any | Potential privilege escalation attempt |
| `mcp.tool.calls{tool=...}` | Baseline deviation | Anomalous usage pattern |

All counters are available at `/actuator/metrics/<name>` (requires `ADMIN` key).

### Database maintenance

`audit_logs` grows unboundedly (append-only trigger prevents DELETE). Implement a retention
policy at the database level:

```sql
-- Create a partition table or use pg_partman for time-based partitioning.
-- For a simple approach, archive old rows to a cold-storage table periodically:

-- This requires SUPERUSER to temporarily disable the trigger, or a separate
-- archive user that bypasses RLS. Design this carefully.
```

Alternative: use PostgreSQL table partitioning by `timestamp` with automatic partition
detachment. Old partitions can be dropped (bypassing row-level triggers) without a schema
change to the active partition.

---

## 12. Known limitations and future work

### Spring AI version pinned to 1.1.4

Spring AI 2.0.x targets Spring Boot 4.x / Jackson 3.x and is not compatible with our Spring
Boot 3.5.x stack. `McpProtocolVersionConfig` bridges the MCP protocol gap (see §8). When a
Spring AI 2.x release compatible with Spring Boot 3.x is available, upgrade the BOM, remove
`McpProtocolVersionConfig`, and drop the `dependencyManagement` overrides for
`io.modelcontextprotocol.sdk` artefacts.

### `last_used_at` is never updated

`V3__key_lifecycle.sql` adds the `last_used_at` column to `api_keys`. `ApiKeyService`
reads it (implicitly, via JPA entity load) but never writes it. Updating it on every
authentication would require either:
- A JPA `merge()` call on every request (write amplification)
- An async `UPDATE api_keys SET last_used_at = now() WHERE id = ?` (acceptable, similar to
  the audit write pattern)

This is a known gap — `last_used_at` is currently always `NULL` in the database.

### No `X-Forwarded-For` trust in rate limiter

As noted in [Caching strategy](#6-caching-strategy), `RateLimiterFilter` uses
`request.getRemoteAddr()`. Behind a load balancer, all requests appear to come from the
same proxy IP. Fix: configure `server.forward-headers-strategy: native` in `application.yaml`
and trust only known proxy IPs via `TomcatServletWebServerFactory.addConnectorCustomizers`.

### Full-text search language is hardcoded to French

`to_tsvector('french', ...)` and `plainto_tsquery('french', ...)` are hardcoded. For a
multilingual deployment, store the language per document in `document_chunks` and use
dynamic SQL: `to_tsvector(chunk.language::regconfig, ...)`. Requires a migration to add
a `language` column with a CHECK constraint.

### No pagination in `query_database`

Results are bounded by `maxRows` (500) but there is no cursor-based pagination. An LLM
cannot retrieve the "next page" of a large table. Consider adding an `offset` parameter
with appropriate security review (offset-based pagination is inefficient at scale; keyset
pagination is preferred).

### PostgreSQL content fetch is sequential

`DocumentSearchTool` fetches one encrypted chunk per matching row in a sequential loop. For
`maxResults=10`, this is 10 SELECT + decrypt operations (~1–5ms each). A future optimization:
batch all chunk UUIDs into a single `SELECT ... WHERE id = ANY(?)` query and decrypt in
memory. Not implemented — the current QPS is low.

### RLS bypassed if mcpuser is a superuser

`FORCE ROW LEVEL SECURITY` is not enforced for PostgreSQL superusers. If `mcpuser` has
`rolsuper=true`, the V5 policies have no effect. In production, verify:
```sql
SELECT rolsuper FROM pg_roles WHERE rolname = 'mcpuser';  -- must return false
```

### No outbound webhook on key revocation

When a key is revoked, other JVM instances (in a scaled deployment) retain the cached list
for up to 60 seconds. Implementing cache invalidation via a Redis pub/sub channel or a
short-polling `/admin/keys/version` endpoint would bring revocation latency down to
milliseconds.

---

## 13. Security findings index (SEC-XXX)

All `SEC-NNN` references in code comments map to findings from the initial security audit.

| ID | Control | Implementation location |
|---|---|---|
| SEC-001 | API key lifecycle (expiry + revocation) | `V3__key_lifecycle.sql`, `ApiKeyService.authenticate()` |
| SEC-002 | Per-IP rate limiting (60 req/min) | `RateLimiterFilter` |
| SEC-003 | API key list caching (60s TTL, avoids N×BCrypt/req) | `ApiKeyService.keyCache` |
| SEC-007 | Auth failure audit logging | `ApiKeyFilter.recordFailure()` |
| SEC-008 | Authorization denial audit logging | `DatabaseQueryTool` catch block |
| SEC-009 | Row limit on all database queries (max 500) | `PostgresConnector.query()` LIMIT clause |
| SEC-010 | TLS enforcement at startup | `StartupValidationConfig` |
| SEC-012 | Table and column name allowlist | `PostgresConnector.ALLOWED_TABLES`, `buildColumnList()` |
| SEC-014 | Micrometer counters for auth failures, rate limits, tool calls | `ApiKeyFilter`, `RateLimiterFilter`, `AuditService` |
| SEC-015 | `/actuator/**` requires ADMIN role | `SecurityConfig` |
| SEC-017 | Prompt injection framing for external content | `DocumentSearchTool` `[EXTERNAL_CONTENT_START/END]` |
| SEC-018 | Search query length limit (500 chars) | `DocumentSearchTool` null/length check |
| SEC-020 | Append-only audit log (database trigger) | `V3__key_lifecycle.sql` trigger |
| SEC-022 | Audit writes never silently dropped (`CallerRunsPolicy`) | `AsyncConfig.auditExecutor()` |
| SEC-023 | No raw key values in source code | `build.gradle.kts` `computeDemoHashes` task |
| SEC-HMAC | HMAC-SHA256 API key authentication with server-side pepper | `HmacApiKeyHasher`, `V4__hmac_api_keys.sql` |
| SEC-RLS | PostgreSQL Row-Level Security for document classification | `RlsContextAspect`, `V5__rls_document_chunks.sql` |
| SEC-ENC | AES-256-GCM content encryption at rest | `ContentEncryptor`, `DbContentStore`, `V6__encrypted_content_column.sql` |
| SEC-AUDIT2 | Second append-only audit sink (Logback JSON file) | `AuditService` AUDIT logger, `logback-spring.xml` |
