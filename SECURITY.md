# Security Policy

## Supported versions

This is a demonstration project. Only the latest commit on `main` is maintained.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Report privately via GitHub's built-in mechanism:
**Security → Report a vulnerability** (top of this repository).

Include:
- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- Any suggested fix if you have one

You can expect an acknowledgement within 72 hours.

## Scope

This gateway exposes only demo/fictional data. There are no real employees,
real salaries, or confidential business documents in the seeded database.
The demo API keys (`demo-readonly-key-001`, `demo-admin-key-001`) are
intentionally published for local development and are documented as such.

If you find a vulnerability that would affect a production deployment of this
codebase (auth bypass, SQL injection, privilege escalation, etc.), please
report it — it matters even if the demo data is fictional.
