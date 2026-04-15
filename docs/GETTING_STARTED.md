# MCP Data Gateway — Getting Started

A step-by-step guide to connecting Claude Code to the live demo gateway.
No technical background required.

🇬🇧 English | [🇫🇷 Français](GETTING_STARTED.fr.md)

---

## Before you start — demo keys

The gateway uses API keys to control access. Two demo keys are available:

| Key | Access level |
|-----|-------------|
| `demo-readonly-key-001` | Employees (no salaries), PUBLIC + INTERNAL documents |
| `demo-admin-key-001` | Everything, including salaries and CONFIDENTIAL documents |

> These keys are public — anyone with this guide can use them.
> They are intended for demonstration only.
> Contact [Ancoris](https://www.ancoris.fr) if you need a private key for your organisation.

---

## Step 1 — Install Claude Code

If you do not have Claude Code yet:

- **Mac or Windows:** download from [claude.ai/code](https://claude.ai/code) and install the desktop app
- **VS Code / JetBrains:** install the Claude Code extension from the marketplace
- **Terminal:** `npm install -g @anthropic-ai/claude-code` (requires Node.js 18+)

Open a terminal and run `claude` to confirm it works.

---

## Step 2 — Add the MCP server

Run this command once in your terminal:

```bash
claude mcp add mcp-data-gateway --transport http https://mcp.37.59.24.118.nip.io/mcp
```

Then start Claude Code:

```bash
claude
```

---

## Step 3 — Authenticate

The first time Claude Code connects it will open a **browser window** asking for your API key.

1. A page titled **"MCP Data Gateway — Authenticate"** opens automatically
2. Enter one of the demo keys from the table above
3. Click **Authorise**
4. The browser shows a success message
5. Claude Code status changes to **✔ connected**

If the browser does not open, run `/mcp` inside Claude Code and select **Re-authenticate**.

Your token lasts **one hour**. After expiry, Claude Code will prompt you to re-authenticate.

---

## Step 4 — Try it

Once connected, just type naturally — Claude uses the gateway tools on your behalf.

**Get oriented:**
```
What data is available in the gateway, and what am I allowed to see?
```

**Browse employees:**
```
List all employees and their departments.
```
```
Who works in the IT department?
```

**Search documents:**
```
Find documents mentioning digital infrastructure investment.
```
```
Are there any documents about information security best practices?
```

**Semantic search** — finds related ideas even without exact keywords:
```
Search for content related to energy efficiency and datacentres.
```
```
Find any content about staff recruitment or performance evaluation.
```

**Cross-cutting question:**
```
Give me a summary of the organisation based on the employee list
and the documents available to me.
```

---

## What data is in the demo gateway

### Employees

| Name | Department |
|------|-----------|
| Alice Martin | RH |
| Bob Dupont | IT |
| Claire Morin | Finance |
| David Leroy | IT |
| Emma Bernard | RH |

> Salary figures are only visible with the admin key.

### Documents

| Document | Classification | Contents |
|----------|---------------|---------|
| `rapport-annuel-2024.txt` | INTERNAL | Annual report — digital investment, energy, results |
| `note-technique-securite.txt` | PUBLIC | IT security best-practices guide |
| `politique-rh-v3.txt` | **CONFIDENTIAL** | HR recruitment and evaluation policy |

> The CONFIDENTIAL document is only returned for the admin key.

---

## Access levels at a glance

| | Read-only key | Admin key |
|---|---|---|
| Employee names and departments | Yes | Yes |
| Employee emails | Yes | Yes |
| Employee salaries | No | Yes |
| PUBLIC documents | Yes | Yes |
| INTERNAL documents | Yes | Yes |
| CONFIDENTIAL documents | No | Yes |

Try this to see the difference clearly:
```
What does the HR policy say about recruitment?
```
- Read-only → "No documents found"
- Admin → returns content from `politique-rh-v3.txt`

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| MCP server shows "✘ failed" | Run `/mcp` → **Reconnect**, then **Re-authenticate** if still failing |
| Browser does not open | Run `/mcp` → **Re-authenticate** to trigger the browser flow manually |
| "No documents found" for HR topics | You are using the read-only key — switch to `demo-admin-key-001` |
| Token expired | Run `/mcp` → **Re-authenticate** |
| Connected but no tools appear | Restart Claude Code |

---

## Want to connect your own data?

This demo uses static sample data. Ancoris configures the ingestion pipeline — loading
your actual documents, databases, and CRM data — as part of a client engagement.

[Contact Ancoris](https://www.ancoris.fr)
