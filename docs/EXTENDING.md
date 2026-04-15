# Extending the MCP Data Gateway

The gateway as shipped handles authentication, access control, search, and audit. This page describes the natural extension layers for a production deployment serving real organisational data.

---

## 1. Document ingestion pipeline

The most impactful extension. Today, chunks are inserted manually into the database. A real ingestion service would handle:

```
file upload (PDF, DOCX, Excel, email export)
  → text extraction
  → chunking
  → embedding via Ollama
  → AES-256 encryption
  → storage in PostgreSQL
```

This can be implemented as a new `ingest_document` MCP tool (ADMIN-only) or a dedicated REST endpoint — either way, the existing security model applies without modification. No additional network service is required.

This is the layer that makes the gateway useful with *your* data rather than demo data. It is the first thing Ancoris configures in a client engagement.

---

## 2. Enterprise data connectors

The gateway's tool model is additive — new `@Tool` classes drop in without touching the security layer. For the technical pattern, see [ARCHITECTURE.md §10 — Adding a new tool](ARCHITECTURE.md#10-adding-a-new-tool).

Connectors with immediate business value for enterprise and public-sector clients:

| Connector | Use case |
|-----------|---------|
| **Zoho CRM / Zoho One** | Expose contacts, opportunities, and project pipeline to AI agents — without the agent ever seeing CRM credentials |
| **Microsoft 365 / Google Workspace** | Search past emails and meeting notes by project or client — *"find all exchanges with the CCIM on the cosmetics file"* |
| **SharePoint / Google Drive** | Ingest documents from existing team drives on a schedule |
| **AWS S3 / Azure Blob / GCP Storage / OVH Object Storage** | Pull documents stored in any major cloud — the gateway normalises access regardless of where the data lives |

In all cases the gateway acts as a credential proxy: the AI model sees only the query result, never the upstream API keys. The gateway is cloud-agnostic — it deploys on any Kubernetes cluster (OVH, AWS EKS, Azure AKS, GCP GKE) with provider-specific Terraform.

---

## 3. Monitoring feed as a queryable source

If you run an automated intelligence pipeline (web monitoring, sector press, regulatory updates), exposing it as a `search_intelligence` MCP tool follows the same pattern as `search_documents` — same RLS, same audit, same access tiers. AI agents can then cross-reference internal documents with live market signals in a single query.

---

## 4. Agent orchestration

The gateway answers questions. An orchestration layer makes it act. Frameworks like [LangGraph](https://github.com/langchain-ai/langgraph), [CrewAI](https://github.com/crewAIInc/crewAI), or [n8n](https://n8n.io) can call the gateway as one tool among several — assembling a territorial diagnosis report, preparing an investor file, or triggering a monitoring run when a new project is detected. The gateway remains stateless; the orchestrator holds the task state.

---

## 5. Observability

The gateway already emits Micrometer metrics (`mcp.tool.calls`, `mcp.auth.failures`, `mcp.rate.limit.exceeded`) via `/actuator/metrics`. Connecting a Prometheus + Grafana stack turns these into real-time dashboards and alerts — authentication failure spikes, per-tool usage by API key, rate limit saturation. Relevant for any organisation with a security officer or compliance requirement.

---

**Items 1–3 above are where Ancoris engages directly** — data modelling, connector development, and ingestion configuration are specific to each client's information architecture. Items 4–5 are self-service with standard OSS tooling. [Reach out](https://www.ancoris.fr) if you want to discuss scope.
