# mcp-data-gateway

A Spring Boot MCP server that exposes internal PostgreSQL data and MinIO documents to AI models through secured, role-based API endpoints.

**For AI agents:** see [`docs/for-ai-agents.md`](docs/for-ai-agents.md)

---

## What it does

- Exposes 3 MCP tools: `query_database`, `search_documents`, `list_sources`
- Authenticates clients with API keys (BCrypt-hashed, stored in DB)
- Enforces column-level and classification-level access control per role
- Logs every tool call to an audit table

## Stack

- Java 21, Spring Boot 3.5, Spring AI MCP Server (streamable-http)
- PostgreSQL 16 (data + audit logs, migrations via Flyway)
- MinIO (document object storage)
- Testcontainers for integration tests

---

## Quickstart (local)

**Requirements:** Docker, Java 21

```bash
# Start dependencies + app
docker compose up -d

# App is on http://localhost:8080
# Test with demo keys (local dev only — rotate before production):
curl -H "X-API-Key: demo-readonly-key-001" http://localhost:8080/actuator/health
```

**Run from source (dependencies already running):**

```bash
export SPRING_PROFILES_ACTIVE=dev
./gradlew bootRun
```

**Run tests:**

```bash
./gradlew test          # Runs all tests (Docker required for Testcontainers)
```

---

## Configuration

All sensitive values are injected via environment variables:

| Variable | Description | Dev default |
|----------|-------------|-------------|
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/mcpgateway` |
| `DB_USER` | DB username | `mcpuser` |
| `DB_PASSWORD` | DB password | `mcppass` |
| `MINIO_ENDPOINT` | MinIO S3 URL | `http://localhost:9000` |
| `MINIO_ACCESS_KEY` | MinIO access key | `minioadmin` |
| `MINIO_SECRET_KEY` | MinIO secret key | `minioadmin` |
| `MINIO_BUCKET` | Bucket name | `mcp-documents` |

The `dev` profile (`SPRING_PROFILES_ACTIVE=dev`) loads `application-dev.yaml` with the defaults above and enables debug logging.

---

## Project layout

```
src/main/java/io/ancoris/mcp/
  tools/          # MCP tools: DatabaseQueryTool, DocumentSearchTool, SourceListTool
  security/       # ApiKeyFilter, ApiKeyService — BCrypt key auth
  connector/      # PostgresConnector (allowlist + RBAC), MinioConnector
  audit/          # AuditService, AuditLog — logs every tool call
  model/          # ApiKey, AccessRole, DataFragment
  config/         # SecurityConfig, McpConfig, PasswordEncoderConfig

src/main/resources/
  db/
    V1__init.sql  # Schema: api_keys, employees, document_chunks, audit_logs
    V2__seed.sql  # Demo data: 2 API keys, 5 employees, 5 document chunks
  application.yaml
  application-dev.yaml

src/test/java/io/ancoris/mcp/
  integration/    # AbstractIntegrationTest (singleton Testcontainers)
  security/       # ApiKeyFilterIT
  tools/          # DatabaseQueryToolIT, SourceListToolIT, DocumentSearchToolIT
```

---

## Security model

**Authentication:** `X-API-Key` header → BCrypt match against `api_keys` table.  
**Session:** Stateless (no cookies). Context cleared after each request.  
**Bypass:** `/actuator/health` requires no key.

**Roles:**

| Role | Employees | Documents |
|------|-----------|-----------|
| `READ_ONLY` | All columns except `salary` | PUBLIC + INTERNAL |
| `ADMIN` | All columns | PUBLIC + INTERNAL + CONFIDENTIAL |

**SQL safety:** Table names validated against a hardcoded allowlist; filter column names validated against the per-table column list; filter values use parameterized queries.

---

## API keys management

Keys are stored as BCrypt hashes (strength 12) in the `api_keys` table.

To verify or regenerate demo hashes:

```bash
./gradlew verifyHashes
```

To add a new key, insert a row directly:

```sql
INSERT INTO api_keys (key_hash, label, role)
VALUES ('$2a$12$...bcrypt-hash...', 'My Client', 'READ_ONLY');
```

---

## Deployment

### Docker

```bash
docker build -t mcp-data-gateway .
docker run -p 8080:8080 \
  -e DB_URL=... -e DB_USER=... -e DB_PASSWORD=... \
  -e MINIO_ENDPOINT=... -e MINIO_ACCESS_KEY=... -e MINIO_SECRET_KEY=... \
  mcp-data-gateway
```

### Kubernetes (OVH)

Infrastructure is managed with Terraform (OVH provider) and Helm charts:

```bash
# Provision cluster
cd infra/terraform && terraform apply

# Deploy dependencies
helm install postgres  k8s/deps/postgres/
helm install minio     k8s/deps/minio/

# Create secrets (never committed)
kubectl create secret generic mcp-gateway-secrets \
  --from-literal=db-url=... \
  --from-literal=db-user=... \
  --from-literal=db-password=... \
  --from-literal=minio-access-key=... \
  --from-literal=minio-secret-key=...

# Deploy app
kubectl apply -f k8s/
```

**Ingress host:** `mcp.ancoris-demo.io` (TLS via cert-manager / Let's Encrypt)

---

## Build quality

| Check | Tool | Threshold |
|-------|------|-----------|
| Unit + integration tests | JUnit 5, Testcontainers | Must pass |
| Code coverage | JaCoCo | ≥ 70% |
| Code style | Checkstyle 10.17 | Zero violations |
| Security bugs | SpotBugs + FindSecBugs | Zero unexcluded findings |
| Dependency CVEs | OWASP Dependency Check | Fail on CVSS ≥ 7.0 |
| Secrets in code | Gitleaks | Zero matches |

```bash
./gradlew build                    # Run everything except OWASP (slow)
./gradlew dependencyCheckAnalyze   # Run OWASP CVE scan (requires network)
```
