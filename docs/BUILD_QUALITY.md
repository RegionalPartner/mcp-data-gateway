# Build Quality

Every commit is validated by the following automated checks:

| Check | Tool | Threshold |
|-------|------|-----------|
| Unit + integration tests | JUnit 5, Testcontainers | Must pass |
| Code coverage | JaCoCo | 70% |
| Code style | Checkstyle 10.17 | Zero violations |
| Security bugs | SpotBugs + FindSecBugs | Zero unexcluded findings |
| Dependency CVEs | OWASP Dependency Check | Fail on CVSS ≥ 7.0 |
| Container vulnerabilities | Trivy | Fail on HIGH/CRITICAL |
| Secrets in code | Gitleaks | Zero matches |

---

## Running checks locally

```bash
./gradlew build                  # All checks except OWASP (slow)
./gradlew dependencyCheckAnalyze # OWASP CVE scan (requires network)
```

Docker is required for Testcontainers integration tests.

---

## CI pipeline

All checks run automatically on every push via GitHub Actions. See [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) for the full pipeline definition.
