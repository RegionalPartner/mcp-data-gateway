# MCP Data Gateway — Configuration Reference

All sensitive values are injected via environment variables. No secrets appear in source code or configuration files checked into the repository.

---

## Environment variables

| Variable | Description | Dev default |
|----------|-------------|-------------|
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/mcpgateway` |
| `DB_USER` | DB username | `mcpuser` |
| `DB_PASSWORD` | DB password | `mcppass` |
| `MCP_HMAC_PEPPER` | Server-side pepper for API key hashing (≥ 32 chars) | dev default (not secure) |
| `MCP_CONTENT_KEY` | AES-256 key — exactly 64 hex chars — for document encryption | dev default (all zeros) |
| `MCP_JWT_SECRET` | Secret for signing OAuth JWT tokens | dev default (not secure) |

---

## Development profile

The `dev` profile (`SPRING_PROFILES_ACTIVE=dev`) loads `application-dev.yaml` with the defaults above and enables debug logging. These defaults are intentionally weak — never use them outside a local environment.

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

---

## Generating production secrets

```bash
# HMAC pepper (256-bit)
openssl rand -hex 32

# AES-256 content key (256-bit)
openssl rand -hex 32

# JWT secret (256-bit)
openssl rand -hex 32
```

> **Critical:** loss of `MCP_CONTENT_KEY` makes encrypted documents permanently unrecoverable without a plaintext backup. Store it in a secrets manager alongside a rotation date.

---

## Kubernetes / OVH deployment

When using the Makefile-based deployment, secrets are auto-generated on first `make up` and saved to `.deploy.env` (gitignored). See [OVH_DEPLOYMENT.md](OVH_DEPLOYMENT.md) for the full walkthrough.

---

## Security requirements per variable

For minimum lengths, rotation procedures, and the production checklist for each variable, see [SECURITY_HARDENING.md §6 — Environment variable reference](SECURITY_HARDENING.md#6-environment-variable-reference).
