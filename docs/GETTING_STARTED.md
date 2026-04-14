# MCP Data Gateway — Getting Started with Claude Code

This guide is for colleagues who want to connect Claude Code to the internal data gateway.
No technical background required.

---

## Before you start — a note on API keys

The gateway uses **API keys** to control access. Two demo keys ship with the codebase:

| Key | Access level |
|-----|-------------|
| `demo-readonly-key-001` | Employees (no salaries), public and internal documents |
| `demo-admin-key-001` | Everything, including salaries and confidential documents |

> **Security notice:** these key values appear in the public GitHub repository.
> Anyone who finds the repo can use them to authenticate to the live gateway.
> If you are running the gateway for anything beyond a closed internal demo,
> ask the administrator to create a personal key for you (see the admin at the
> bottom of this page) and rotate or disable the demo keys.

---

## Step 1 — Install Claude Code

If you do not have Claude Code yet:

- **Mac or Windows:** download from [claude.ai/code](https://claude.ai/code) and install the desktop app
- **VS Code / JetBrains:** install the Claude Code extension from the marketplace
- **Terminal:** `npm install -g @anthropic-ai/claude-code` (requires Node.js 18+)

Open a terminal (or the built-in terminal in your IDE) and run `claude` to check it works.

---

## Step 2 — Add the MCP server

In your Claude Code terminal, run:

```
/mcp
```

Select **Add server**, choose **HTTP**, and enter:

```
Name:  mcp-data-gateway
URL:   https://mcp.37.59.24.118.nip.io/mcp
```

Alternatively, edit `~/.claude.json` directly and add this entry inside the
`"mcpServers"` section (create it if it does not exist):

```json
"mcpServers": {
  "mcp-data-gateway": {
    "type": "http",
    "url": "https://mcp.37.59.24.118.nip.io/mcp"
  }
}
```

Save the file and restart Claude Code.

---

## Step 3 — Authenticate

The first time Claude Code connects to the gateway it will open a **browser window**
asking for your API key.

1. Claude Code opens your browser automatically — you will see a page titled
   **"MCP Data Gateway — Authenticate"**
2. Enter one of the API keys from the table above
3. Click **Authorise**
4. The browser shows a success message and closes
5. Claude Code status changes to **✔ connected**

If the browser does not open automatically, run `/mcp` and select **Re-authenticate**.

Your authentication token is stored locally and lasts **one hour**. After it expires,
Claude Code will ask you to re-authenticate.

---

## Step 4 — Try it

Once connected, the gateway tools appear automatically inside Claude Code. Just type
naturally — Claude uses the tools on your behalf.

### Starter prompts

**Get oriented:**
```
What data is available in the MCP gateway, and what am I allowed to see?
```

**Browse employees:**
```
List all employees and their departments.
```

```
Who works in the IT department?
```

**Search documents (keyword):**
```
Find documents mentioning digital infrastructure investment.
```

```
Are there any documents about information security best practices?
```

**Semantic search — finds related ideas even without exact keywords:**
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

## What data is in the gateway

### Employees

A small team of five people used to demonstrate role-based access.

| Name | Department |
|------|-----------|
| Alice Martin | RH |
| Bob Dupont | IT |
| Claire Morin | Finance |
| David Leroy | IT |
| Emma Bernard | RH |

> Salary figures are **only visible with the admin key**.
> With the read-only key, the salary column is hidden.

### Documents

Three documents pre-loaded as searchable fragments:

| Document | Classification | What it contains |
|----------|---------------|-----------------|
| `rapport-annuel-2024.txt` | INTERNAL | Annual report — digital investment, energy figures, overall results |
| `note-technique-securite.txt` | PUBLIC | IT security best-practices guide |
| `politique-rh-v3.txt` | **CONFIDENTIAL** | HR recruitment and evaluation policy |

> The **CONFIDENTIAL** document is only returned for admin keys.
> With the read-only key, search results for "recrutement" or "politique RH" return nothing.

---

## The two access levels side by side

| What you ask | Read-only key | Admin key |
|---|---|---|
| Employee names and departments | Yes | Yes |
| Employee emails | Yes | Yes |
| Employee salaries | **No** | Yes |
| PUBLIC documents | Yes | Yes |
| INTERNAL documents | Yes | Yes |
| CONFIDENTIAL documents | **No** | Yes |

A good way to see the difference: ask the same question twice, once with each key.

```
What does the HR policy say about recruitment?
```
- Read-only → "No documents found"
- Admin → returns content from `politique-rh-v3.txt`

---

## Prompts that show the access control clearly

```
Compare what I can see vs what an admin user would see. What is hidden from me?
```

```
Search for all documents and list their names and classifications.
```

```
Show me the full employee list including salaries.
```
*(Read-only key: salary column is absent. Admin key: salary included.)*

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `/mcp` shows "✘ failed" | Click **Reconnect**. If still failed, click **Re-authenticate**. |
| Browser does not open | Run `/mcp` → **Re-authenticate** to manually trigger the browser flow. |
| "No documents found" for HR topics | You are using the read-only key. Switch to the admin key and re-authenticate. |
| Token expired (usually after ~1 hour) | Run `/mcp` → **Re-authenticate** to get a fresh token. |
| Connected but tools do not appear | Restart Claude Code. The tool list is loaded at startup. |

---

## Requesting a personal key

If you need a key that is not published in the repository (recommended for anything
beyond a quick demo), ask the gateway administrator to create one:

```sql
-- Admin runs this on the gateway database:
-- Replace 'Your Name' and 'READ_ONLY' or 'ADMIN' as needed.
-- The raw key value is communicated to you out-of-band (e.g. Signal, encrypted email).
```

The administrator will give you a raw key value to enter in the browser authentication
form. Everything else in this guide works the same way.
