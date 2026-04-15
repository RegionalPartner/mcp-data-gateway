# mcp-data-gateway

> **A production-grade MCP server** that exposes enterprise data to AI agents through a secured, role-based gateway — built and open-sourced by [Ancoris](https://www.ancoris.fr), the digital and AI consulting division of [Groupe AXTOM](https://www.ancoris.fr).

[![CI](https://github.com/RegionalPartner/mcp-data-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/RegionalPartner/mcp-data-gateway/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

🇬🇧 English | [🇫🇷 Français](README.fr.md)

---

## What this is

[Model Context Protocol (MCP)](https://modelcontextprotocol.io) is the emerging standard that lets AI models — Claude, GPT, Gemini — talk to enterprise systems. This gateway is the **secure bridge** between your internal data and any MCP-compatible AI agent.

**The bridge has a deliberate design choice:** the data ingestion side is intentionally not included in this open-source release. Loading your documents, structuring your database, and classifying your data assets is where the real enterprise work happens — and it is different for every organisation. That is what [Ancoris](https://www.ancoris.fr) does for its clients.

What you get here is the full, production-hardened gateway layer: authentication, authorisation, semantic search, audit, and Kubernetes-ready deployment. Ready to connect to your data.

---

## Live demo

A running instance with anonymised sample data is available at:

```
https://mcp.37.59.24.118.nip.io
```

Connect it to Claude Code:

```bash
claude mcp add mcp-data-gateway --transport http https://mcp.37.59.24.118.nip.io/mcp
```

Demo keys (read-only and admin roles):

| Key | Role | What you can see |
|-----|------|-----------------|
| `demo-readonly-key-001` | READ_ONLY | PUBLIC + INTERNAL documents, employees (no salary) |
| `demo-admin-key-001` | ADMIN | All documents including CONFIDENTIAL, full employee table |

Try asking your AI agent: *"List the available data sources"* or *"Search for documents about HR policy"*.

---

![MCP Data Gateway — overview](docs/assets/mcp-gateway-overview.png)

## What it does

Four MCP tools, exposed over OAuth 2.0 PKCE (streamable HTTP transport):

| Tool | Description |
|------|-------------|
| `list_sources` | Lists tables and document collections accessible to the current key, including visible columns and classification tiers |
| `query_database` | Queries structured tables with role-based column filtering and parameterised SQL (injection-safe) |
| `search_documents` | Keyword search over internal documents — returns text fragments, never raw files |
| `semantic_search_documents` | Vector similarity search (pgvector + Ollama) — finds conceptually related content even without exact keyword matches |

Every tool call is written to an immutable audit log.

---

## Security model

Authentication flows through OAuth 2.0 PKCE — Claude Code and other MCP clients handle this automatically. Internally, API keys are HMAC-peppered and BCrypt-hashed.

**Row-level security is enforced at the PostgreSQL layer**, not in application code. Roles cannot be spoofed by a compromised application process.

| Role | Structured data | Documents |
|------|----------------|-----------|
| `READ_ONLY` | All columns except `salary` | PUBLIC + INTERNAL |
| `ADMIN` | All columns | PUBLIC + INTERNAL + CONFIDENTIAL |

Additional protections:
- Table and column names validated against a hardcoded allowlist (no dynamic SQL)
- Filter values use parameterised queries throughout
- Document content stored AES-256 encrypted at rest
- NGINX session affinity for stateful MCP connections across replicas
- Rate limiter (configurable, default 300 req/window per IP)

---

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.5 |
| MCP | Spring AI MCP Server (streamable HTTP, protocol 2025-03-26 + 2025-11-25) |
| Database | PostgreSQL 16 — data, encrypted documents, audit logs, pgvector embeddings |
| Migrations | Flyway |
| Embeddings | Ollama (local, no external API dependency) |
| Infrastructure | Kubernetes (OVH), Terraform, NGINX Ingress, cert-manager (Let's Encrypt) |
| CI | GitHub Actions — tests, OWASP CVE scan, Trivy container scan, Gitleaks |

---

## Quickstart (local)

**Requirements:** Docker, Java 21

```bash
# Start PostgreSQL + Ollama
docker compose up -d

# Run the app
./gradlew bootRun

# App is on http://localhost:8080
curl -H "X-API-Key: demo-readonly-key-001" http://localhost:8080/actuator/health
```

**Run tests:**

```bash
./gradlew test   # Docker required for Testcontainers
```

---

## Configuration

All sensitive values are injected via environment variables:

| Variable | Description | Dev default |
|----------|-------------|-------------|
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/mcpgateway` |
| `DB_USER` | DB username | `mcpuser` |
| `DB_PASSWORD` | DB password | `mcppass` |
| `MCP_HMAC_PEPPER` | Server-side pepper for API key hashing | dev default (not secure) |
| `MCP_CONTENT_KEY` | AES-256 key (64 hex chars) for document encryption | dev default (all zeros) |
| `MCP_JWT_SECRET` | Secret for signing OAuth JWT tokens | dev default (not secure) |

The `dev` profile (`SPRING_PROFILES_ACTIVE=dev`) loads `application-dev.yaml` with the above defaults and enables debug logging.

---

## Deployment (Kubernetes / OVH)

Infrastructure is managed with Terraform (OVH provider). A Makefile wraps the full lifecycle:

```bash
# Copy and fill in credentials
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars

# Deploy (~20 min first run — provisions cluster, installs cert-manager, deploys app)
source ~/.ovh-terraform.env
make up

# Stop cluster and stop billing (~5 min, secrets preserved in .deploy.env)
make down
```

Before running `make up`, set your domain in `k8s/app/ingress.yaml` and your email in `k8s/app/cert-manager-issuer.yaml` for TLS. See [`docs/OVH_DEPLOYMENT.md`](docs/OVH_DEPLOYMENT.md) for the full walkthrough.

Secrets are auto-generated on first `make up` and saved to `.deploy.env` (gitignored).
**Keep `.deploy.env` safe — loss of `MCP_CONTENT_KEY` makes encrypted documents unrecoverable.**

---

## Build quality

| Check | Tool | Threshold |
|-------|------|-----------|
| Unit + integration tests | JUnit 5, Testcontainers | Must pass |
| Code coverage | JaCoCo | 70% |
| Code style | Checkstyle 10.17 | Zero violations |
| Security bugs | SpotBugs + FindSecBugs | Zero unexcluded findings |
| Dependency CVEs | OWASP Dependency Check | Fail on CVSS 7.0 |
| Container vulnerabilities | Trivy | Fail on HIGH/CRITICAL |
| Secrets in code | Gitleaks | Zero matches |

```bash
./gradlew build                  # All checks except OWASP (slow)
./gradlew dependencyCheckAnalyze # OWASP CVE scan (requires network)
```

---

## Project layout

```
src/main/java/io/ancoris/mcp/
  tools/      # MCP tools: query_database, search_documents, semantic_search_documents, list_sources
  security/   # OAuth 2.0 PKCE, HMAC API key auth, JWT issuance, rate limiter
  connector/  # PostgresConnector (allowlist + RBAC), DbContentStore, ContentEncryptor
  audit/      # AuditService — logs every tool call to audit_logs table
  model/      # ApiKey, AccessRole, DataFragment
  config/     # SecurityConfig, McpConfig, McpProtocolVersionConfig

src/main/resources/db/
  V1__init.sql   # Schema: api_keys, employees, document_chunks, audit_logs, pgvector extension
  V2__seed.sql   # Demo data: 2 API keys, 3 document classifications, 5 employees
```

---

## For AI agents

See [`docs/for-ai-agents.md`](docs/for-ai-agents.md) — machine-readable description of tools, parameters, and expected behaviour.

---

## Go further

The gateway as shipped handles authentication, access control, search, and audit. A production deployment for a real organisation typically adds the following layers.

### Document ingestion pipeline

The most impactful extension. Today chunks are inserted manually into the database. A real ingestion service would handle: file upload (PDF, DOCX, Excel, email export) → text extraction → chunking → embedding via Ollama → AES encryption → storage in PostgreSQL. This can be implemented as a new `ingest_document` MCP tool (ADMIN-only) or a dedicated REST endpoint — either way, the existing security model applies without modification. No additional network service required.

This is the layer that makes the gateway useful with *your* data rather than demo data. It is the first thing Ancoris configures in a client engagement.

### Enterprise data connectors

The gateway's tool model is additive — new `@Tool` classes drop in without touching the security layer. Connectors with immediate business value for enterprise and public-sector clients:

| Connector | Use case |
|-----------|---------|
| **Zoho CRM / Zoho One** | Expose contacts, opportunities, and project pipeline to AI agents — without the agent ever seeing CRM credentials |
| **Microsoft 365 / Google Workspace** | Search past emails and meeting notes by project or client — *"find all exchanges with the CCIM on the cosmetics file"* |
| **SharePoint / Google Drive** | Ingest documents from existing team drives on a schedule |
| **AWS S3 / Azure Blob / GCP Storage / OVH Object Storage** | Pull documents stored in any major cloud — the gateway normalises access regardless of where the data lives |

In all cases the gateway acts as a credential proxy: the AI model sees only the query result, never the upstream API keys. The gateway itself is cloud-agnostic — it deploys on any Kubernetes cluster (OVH, AWS EKS, Azure AKS, GCP GKE) with provider-specific Terraform.

### Monitoring feed as a queryable source

If you run an automated intelligence pipeline (web monitoring, sector press, regulatory updates), exposing it as a `search_intelligence` MCP tool follows the same pattern as `search_documents` — same RLS, same audit, same access tiers. AI agents can then cross-reference internal documents with live market signals in a single query.

### Agent orchestration

The gateway answers questions. An orchestration layer makes it act. Frameworks like [LangGraph](https://github.com/langchain-ai/langgraph), [CrewAI](https://github.com/crewAIInc/crewAI), or [n8n](https://n8n.io) can call the gateway as one tool among several — assembling a territorial diagnosis report, preparing an investor file, or triggering a monitoring run when a new project is detected. The gateway remains stateless; the orchestrator holds the task state.

### Observability

The gateway already emits Micrometer metrics (`mcp.tool.calls`, `mcp.auth.failures`, `mcp.rate.limit.exceeded`) via `/actuator/metrics`. Connecting a Prometheus + Grafana stack turns these into real-time dashboards and alerts — authentication failure spikes, per-tool usage by API key, rate limit saturation. Relevant for any organisation with a security officer or compliance requirement.

---

**Items 1–3 above are where Ancoris engages directly** — the data modelling, connector development, and ingestion configuration are specific to each client's information architecture. Items 4–5 are self-service with standard OSS tooling. [Reach out](https://www.ancoris.fr) if you want to discuss scope.

---

## About Ancoris

**Ancoris** is the digital and AI consulting division of [Groupe AXTOM](https://www.ancoris.fr), a French group specialising in economic development and enterprise real estate — recognised as a *Champion de la Croissance* (Les Échos) since 2023.

We built this gateway because our clients — intercommunalities, economic development agencies, and enterprise operators — hold years of structured data that their teams cannot easily query or reason over. Connecting that data safely to AI agents is now one of the most valuable things we do.

**What we do for clients:**

- Structure and ingest your data assets (documents, databases, CRM exports) into a gateway like this one
- Define your classification tiers and access roles to match your internal security policy
- Deploy and operate the infrastructure on your preferred cloud (OVH, Azure, GCP)
- Train your teams to use AI agents against their own data

This repository is the open-source foundation. The data pipeline is the engagement.

**Interested?** Visit [ancoris.fr](https://www.ancoris.fr) or open a [GitHub Discussion](https://github.com/RegionalPartner/mcp-data-gateway/discussions).

---

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 Groupe AXTOM.

---

