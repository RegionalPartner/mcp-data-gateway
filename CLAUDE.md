# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
# Full build (unit tests, Checkstyle, SpotBugs, JaCoCo, CycloneDX SBOM)
./gradlew build

# Unit tests only (fast — no Docker required)
./gradlew test

# Single test class
./gradlew test --tests "io.ancoris.mcp.security.HmacApiKeyHasherTest"

# Single test method
./gradlew test --tests "io.ancoris.mcp.security.HmacApiKeyHasherTest.hash_knownInput_returnsExpectedHex"

# Integration tests (requires Docker — starts PostgreSQL via Testcontainers)
./gradlew integrationTest

# Local full-stack E2E (requires Docker + running app on localhost:8080)
./gradlew test --tests "io.ancoris.mcp.McpEndToEndIT"

# OVH remote E2E — only works locally when CI env var is unset and OVH is reachable
./gradlew test --tests "io.ancoris.mcp.McpRemoteEndToEndIT"

# Mutation testing (slow — nightly CI only)
./gradlew pitest

# JMH benchmarks (slow — nightly CI only, forks a JVM)
./gradlew jmh

# Slop detector on all files
make lint-all
```

**Local dev stack** (PostgreSQL + Ollama):
```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

The `dev` profile loads `application-dev.yaml` with insecure defaults — never use outside localhost.

**Rust tools** (each is an independent crate):
```bash
cargo test --manifest-path tools/key-rotation/Cargo.toml
cargo test --manifest-path ingestion/Cargo.toml
cargo clippy --manifest-path ingestion/Cargo.toml -- -D warnings
```

Always run `cargo fmt` + `cargo clippy -D warnings` locally before pushing — CI blocks on both. `///` doc comments on module-level blocks trigger `empty_line_after_doc_comments`; use `//` for module-level prose instead.

**Pre-commit hooks** (install once after clone):
```bash
make hooks
```

## OVH cluster operations

The Makefile wraps all cluster operations. Secrets are auto-generated on first `make up` and persisted in `.deploy.env` (gitignored). **Loss of `MCP_CONTENT_KEY` makes encrypted document chunks permanently unrecoverable.**

```bash
make up                          # Provision Terraform + deploy (~20 min first run)
make down                        # Destroy cluster and stop OVH billing
make preview IMAGE_TAG=sha-abc   # Boot new image alongside prod (no traffic cut)
make open-preview                # Port-forward preview → localhost:8081
make promote IMAGE_TAG=sha-abc   # Rolling-update prod, teardown preview
make status / make logs
```

`IMAGE_TAG` resolution order: explicit arg → `DEPLOYED_IMAGE_TAG` in `.deploy.env` → `sha-$(git rev-parse --short HEAD)`.

## Architecture

### Package layout and dependency rules (enforced by ArchUnit)

```
io.ancoris.mcp
├── model/          — pure domain types (AccessRole, ApiKey, DataFragment) — no outbound deps
├── connector/      — data access (PostgresConnector, ContentStore, EmbeddingService)
│                     must not depend on tools/, security/, audit/, or config/
├── tools/          — MCP tool implementations (DatabaseQueryTool, DocumentSearchTool, etc.)
│                     use Spring Security APIs directly — must not import security/ internals
├── security/       — ApiKeyFilter, ApiKeyService, HmacApiKeyHasher, RateLimiterFilter
├── oauth/          — OAuthController, JwtTokenService, AuthCodeStore
├── audit/          — AuditService, AuditLog, AuditLogRepository
└── config/         — McpConfig (tool registration), SecurityConfig, StartupValidationConfig
```

Constructor injection is mandatory everywhere — `@Autowired` on fields is banned by ArchUnit.

### Request pipeline (filter chain order)

```
RateLimiterFilter        @Order(DEFAULT_FILTER_ORDER - 1) — outside Spring Security; hits unauthenticated reqs
  └── Spring Security chain
        └── ApiKeyFilter — before UsernamePasswordAuthenticationFilter
              └── Spring AI MCP RouterFunction
                    └── @Tool method
```

The security context is populated only during `REQUEST` dispatches. During `ASYNC` dispatches (SSE finalisation) it is empty — tool method bodies always run fully in `REQUEST`.

### Security invariants

- **Column allowlist**: `PostgresConnector.ALL_COLUMNS` is hardcoded, not derived from DB metadata. Adding a column to a table requires a code change alongside the Flyway migration. This is intentional.
- **SQL injection**: table names validated against `ALLOWED_TABLES` set; filter column names validated against the role-visible column list; filter values use `?` parameterized queries only.
- **AES-256-GCM**: `ContentEncryptor` wraps doc chunks. Wire format: `[12B IV][ciphertext + 16B GCM tag]`. Key source: `MCP_CONTENT_KEY` (64 hex chars).
- **HMAC API key hashing**: `HmacApiKeyHasher` uses HMAC-SHA256 with a server-side pepper (≥32 chars). `MessageDigest.isEqual` for constant-time comparison.
- **Dual audit sinks**: PostgreSQL `audit_logs` (trigger-enforced append-only) + `/var/log/mcp/audit.json` (`chattr +a`). Both must be compromised simultaneously to erase a record.

### Test structure

| Class | What it tests | Docker needed |
|---|---|---|
| `*Test.java` | Unit tests, mocked collaborators | No |
| `AbstractIntegrationTest` subclasses | Full Spring context + real PostgreSQL (Testcontainers `pgvector/pgvector:pg16`) | Yes |
| `McpEndToEndIT` | Full MCP protocol over HTTP against a locally running app | Yes |
| `McpRemoteEndToEndIT` | Live OVH cluster smoke test — skipped when `CI=true` | No (OVH reachable) |

`AbstractIntegrationTest` uses a singleton Testcontainers container to avoid Spring context cache misses. `EmbeddingModel` is mocked to remove Ollama dependency from integration tests.

### Adding a new MCP tool

1. Create `@Component` class in `tools/` with `@Tool`-annotated method; call `auditService.log(...)` and `currentApiKey()` from `SecurityContextHolder`.
2. Register in `McpConfig.java` by adding to the `MethodToolCallbackProvider.builder().toolObjects(...)` call.
3. Add the data source to `SourceListTool.listSources()` so LLM clients can discover it.
4. Tests: unit test with mocked collaborators + integration test extending `AbstractIntegrationTest` + assertion in `McpEndToEndIT`.

### Rust components

`tools/key-rotation/` — standalone CLI for rotating API key secrets on the cluster.
`ingestion/` — standalone pipeline for chunking, embedding, and loading documents into PostgreSQL. Uses `nomic-embed-text` via Ollama. Not part of the Spring Boot build.

## CI quality gates

| Gate | When | Blocking |
|---|---|---|
| Checkstyle + SpotBugs + JaCoCo (≥70% overall, ≥80% changed files) | Every push/PR | Yes |
| ArchUnit architecture rules | Every push/PR (runs in `./gradlew build`) | Yes |
| OWASP Dependency Check (CVSS ≥ 7.0) | Every push (blocking on main, warn on PR) | Conditional |
| Trivy container scan (CRITICAL + HIGH) | Every push | Yes |
| Gitleaks secret scan | Every push/PR | Yes |
| AI slop detector | Every push/PR (`quality.yml`) | No (`--warn-only`) |
| PITest mutation testing (threshold 60%) | Nightly | Informational |
| JMH benchmarks (alert at 150% regression) | Nightly | Yes on regression |

`./gradlew build` runs gates 1–2. The `gitignore` contains a bare `io/` entry — always stage Java sources with `git add -f src/main/java/io/...`.

## Environment variables

| Variable | Requirement |
|---|---|
| `MCP_HMAC_PEPPER` | ≥32 chars |
| `MCP_CONTENT_KEY` | Exactly 64 hex chars (32-byte AES key) |
| `MCP_JWT_SECRET` | Any non-empty string (≥256-bit recommended) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Standard JDBC |
| `TEI_BASE_URL` | Embedding endpoint (OpenAI-compatible, default: `http://tei:8080`) |

`StartupValidationConfig` aborts the app at startup if `sslmode=require` is absent from `DB_URL` in production (bypassed by the `dev` profile).
