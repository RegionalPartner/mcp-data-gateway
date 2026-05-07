SHELL := /bin/bash
.PHONY: up down open open-prometheus open-grafana status logs help hooks lint lint-all preview open-preview promote teardown-preview _validate-image _ingress _ingress-ip _observability

# ── Config ────────────────────────────────────────────────────────────────────
TFDIR      := infra/terraform
KUBECONFIG := $(HOME)/.kube/ovh-mcp.yaml
K          := KUBECONFIG=$(KUBECONFIG) kubectl
H          := KUBECONFIG=$(KUBECONFIG) helm
NS         := mcp-demo
ENV_FILE   := .deploy.env
GIT_REMOTE  := $(shell git config --get remote.origin.url 2>/dev/null)
DOMAIN      := $(shell grep -m1 '^\s*- host:' k8s/app/ingress.yaml | awk '{print $$3}' 2>/dev/null)
GITHUB_OWNER := $(shell printf '%s\n' "$(GIT_REMOTE)" | sed -nE 's#(git@|https://)github.com[:/]([^/]+)/.*#\2#p' | tr '[:upper:]' '[:lower:]')
IMAGE_REPO ?= $(if $(GITHUB_OWNER),ghcr.io/$(GITHUB_OWNER)/mcp-data-gateway,)
# ── Production checkpoint ─────────────────────────────────────────────────────
# Last known-good image running on the OVH cluster as of 2026-04-17:
#   IMAGE_TAG : sha-1747402
#   Git commit: 17474020  fix: derive client_secret via HMAC instead of in-memory cache
#   Digest    : sha256:b61c40d429f17a822a252483ba22384fe6af8ec848a7ad97f16067dfe015fa50
#   Deployed  : 2026-04-15
# Emergency rollback:  make up IMAGE_TAG=sha-1747402
# ─────────────────────────────────────────────────────────────────────────────
# IMAGE_TAG resolution order (first match wins):
#   1. Explicit override on the command line:  make up IMAGE_TAG=sha-abc1234
#   2. DEPLOYED_IMAGE_TAG saved in .deploy.env by the last make up / make promote
#      → make down on Friday + make up on Monday redeploys the exact same image
#   3. SHA of the current local HEAD (fallback for first-ever deploy)
_SAVED_TAG  := $(shell grep '^DEPLOYED_IMAGE_TAG=' $(ENV_FILE) 2>/dev/null | cut -d= -f2)
IMAGE_TAG   ?= $(if $(_SAVED_TAG),$(_SAVED_TAG),sha-$(shell git rev-parse --short HEAD))
IMAGE      ?= $(IMAGE_REPO):$(IMAGE_TAG)

# ── Colours ───────────────────────────────────────────────────────────────────
G := \033[0;32m
Y := \033[0;33m
N := \033[0m

# ─────────────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  make up               Provision cluster + deploy app  (~20 min first run)"
	@echo "  make down             Destroy cluster + stop charges  (~5 min)"
	@echo "  make open             Port-forward gateway → http://localhost:8080"
	@echo "  make open-prometheus  Port-forward Prometheus → http://localhost:9090"
	@echo "  make open-grafana     Port-forward Grafana    → http://localhost:3000"
	@echo "  make status           Show pod and service status"
	@echo "  make logs             Tail gateway logs"
	@echo "  make hooks         Install pre-commit hooks (run once after clone)"
	@echo "  make lint          Run pre-commit checks on staged files"
	@echo "  make lint-all      Run pre-commit checks on all files"
	@echo ""
	@echo "  Zero-downtime deployment:"
	@echo "  make preview       Deploy new image alongside current (no traffic cut)"
	@echo "  make open-preview  Port-forward preview → http://localhost:8081"
	@echo "  make promote       Rolling-update production to IMAGE, teardown preview"
	@echo "  make teardown-preview  Remove preview deployment + service"
	@echo ""
	@echo "  App image defaults to IMAGE=$(IMAGE)"
	@echo "  Override with: make up IMAGE_REPO=ghcr.io/<owner>/mcp-data-gateway IMAGE_TAG=develop"
	@echo ""
	@echo "  Ingress domain read from k8s/app/ingress.yaml — set your domain + email first."
	@echo "  Secrets are auto-generated on first 'make up' and saved to $(ENV_FILE)."
	@echo "  Keep that file safe — loss of MCP_CONTENT_KEY = encrypted data unrecoverable."
	@echo ""

# ── Main targets ──────────────────────────────────────────────────────────────

hooks:
	@command -v pre-commit >/dev/null 2>&1 || pipx install pre-commit
	pre-commit install
	@echo -e "$(G)✓ Pre-commit hooks installed.$(N) Runs automatically on every git commit."

lint:
	pre-commit run

lint-all:
	pre-commit run --all-files

# ── Zero-downtime blue/green ───────────────────────────────────────────────────
# preview: boots IMAGE alongside the current production pod — no traffic cut.
#   Both pods share the same DB and secrets; Flyway migrations run on startup.
#   Production Service keeps routing to the original pod until 'make promote'.
#
# promote: rolling-updates production to IMAGE (maxUnavailable=0 in deployment.yaml
#   ensures readinessProbe passes on the new pod before the old one is removed),
#   then tears down the preview deployment automatically.
#
# Usage:
#   make preview  IMAGE_TAG=boot4-upgrade-abc1234
#   make open-preview                              # test at localhost:8081
#   make promote  IMAGE_TAG=boot4-upgrade-abc1234  # flip + cleanup
#   make teardown-preview                          # if you want to abort instead

preview: _validate-image
	@echo -e "$(G)▶ Deploying preview alongside production...$(N)"
	$(K) get deployment mcp-gateway -n $(NS) -o yaml \
	  | python3 -c "\
import sys, re; t = sys.stdin.read(); \
t = re.sub(r'(metadata:\n  name:) mcp-gateway\b', r'\1 mcp-gateway-preview', t); \
t = re.sub(r'(app:) mcp-gateway\b', r'\1 mcp-gateway-preview', t); \
print(t)" \
	  | $(K) apply -f -
	$(K) set image deployment/mcp-gateway-preview mcp-gateway=$(IMAGE) -n $(NS)
	$(K) set env deployment/mcp-gateway-preview MCP_SECURITY_ENFORCE_TLS=false -n $(NS)
	$(K) expose deployment mcp-gateway-preview \
	  --name=mcp-gateway-preview --port=80 --target-port=8080 \
	  -n $(NS) --dry-run=client -o yaml | $(K) apply -f -
	$(K) rollout status deployment/mcp-gateway-preview -n $(NS) --timeout=120s
	@echo -e "$(G)✓ Preview is live.$(N)"
	@echo -e "  Health: $(K) exec -n $(NS) deploy/mcp-gateway-preview -- wget -qO- http://localhost:8080/actuator/health"
	@echo -e "  Logs:   $(K) logs -n $(NS) -l app=mcp-gateway-preview --tail=50 -f"
	@echo -e "  Port:   make open-preview  → http://localhost:8081"

open-preview:
	@echo -e "$(G)Preview:$(N) http://localhost:8081/mcp"
	@echo -e "$(G)Health: $(N) http://localhost:8081/actuator/health"
	@echo    "  Ctrl+C to stop."
	$(K) port-forward svc/mcp-gateway-preview 8081:80 -n $(NS)

promote: _validate-image
	@echo -e "$(G)▶ Promoting $(IMAGE) to production (rolling, zero-downtime)...$(N)"
	$(K) set image deployment/mcp-gateway mcp-gateway=$(IMAGE) -n $(NS)
	$(K) set env deployment/mcp-gateway MCP_SECURITY_ENFORCE_TLS=false -n $(NS)
	$(K) rollout status deployment/mcp-gateway -n $(NS) --timeout=120s
	@sed -i '/^DEPLOYED_IMAGE_TAG=/d' $(ENV_FILE) 2>/dev/null; \
	  echo "DEPLOYED_IMAGE_TAG=$(IMAGE_TAG)" >> $(ENV_FILE)
	@echo -e "$(G)✓ Production updated — image tag recorded in $(ENV_FILE).$(N)"
	@$(MAKE) teardown-preview 2>/dev/null || true

teardown-preview:
	@echo -e "$(Y)Removing preview deployment and service...$(N)"
	$(K) delete deployment mcp-gateway-preview -n $(NS) --ignore-not-found
	$(K) delete service    mcp-gateway-preview -n $(NS) --ignore-not-found
	@echo -e "$(G)✓ Preview cleaned up.$(N)"

up: _validate-image _tf-apply _kubeconfig _secrets _postgresql _observability _app _smoke
	@echo -e "$(G)✓ Cluster is up.$(N)"
	@echo -e "  Local:       make open → http://localhost:8080/mcp"
	@echo -e "  Prometheus:  make open-prometheus → http://localhost:9090"
	@echo -e "  Grafana:     make open-grafana    → http://localhost:3000  (anon Viewer; admin pw in $(ENV_FILE))"
	@echo -e "  Public:      make _ingress  (once you have a domain set in k8s/app/ingress.yaml)"

down:
	@echo -e "$(Y)Destroying cluster — all Kubernetes data will be lost.$(N)"
	@echo -e "$(Y)Secrets in $(ENV_FILE) are preserved for the next make up.$(N)"
	cd $(TFDIR) && terraform destroy -auto-approve
	@rm -f $(KUBECONFIG)
	@echo -e "$(G)✓ Done. Charges stopped.$(N)"

open:
	@echo -e "$(G)Gateway:$(N) http://localhost:8080/mcp"
	@echo -e "$(G)Health: $(N) http://localhost:8080/actuator/health"
	@[ -n "$(DOMAIN)" ] && echo -e "$(G)Public: $(N) https://$(DOMAIN)/mcp" || true
	@echo    "  Ctrl+C to stop."
	$(K) port-forward svc/mcp-gateway 8080:80 -n $(NS)

open-prometheus:
	@echo -e "$(G)Prometheus:$(N) http://localhost:9090"
	@echo    "  Ctrl+C to stop."
	$(K) port-forward svc/prometheus 9090:9090 -n $(NS)

open-grafana:
	@echo -e "$(G)Grafana:$(N) http://localhost:3000"
	@echo -e "  Anonymous Viewer is enabled — no login needed for read-only dashboards."
	@PW=$$(grep '^GRAFANA_ADMIN_PASSWORD=' $(ENV_FILE) 2>/dev/null | cut -d= -f2-) ; \
	  [ -n "$$PW" ] && echo -e "  Admin login (edit mode): $(G)admin$(N) / $(G)$$PW$(N)" \
	                || echo -e "  $(Y)Admin password not found in $(ENV_FILE) — run: make _observability$(N)"
	@echo    "  Ctrl+C to stop."
	$(K) port-forward svc/grafana 3000:3000 -n $(NS)

status:
	$(K) get pods,svc -n $(NS)

logs:
	$(K) logs -n $(NS) -l app=mcp-gateway --tail=100 -f

# ── Internal steps ────────────────────────────────────────────────────────────


_validate-image:
	@if [ -z "$(IMAGE_REPO)" ] || [ -z "$(IMAGE_TAG)" ] || [ -z "$(IMAGE)" ]; then \
	  echo "Error: unable to determine the app image."; \
	  echo "Set IMAGE_REPO=ghcr.io/<owner>/mcp-data-gateway and optionally IMAGE_TAG=<tag>."; \
	  exit 1; \
	fi
	@if [[ "$(IMAGE)" == *YOUR_REGISTRY* ]] || [[ "$(IMAGE)" == ghcr.io//:* ]]; then \
	  echo "Error: invalid IMAGE='$(IMAGE)'."; \
	  echo "Set IMAGE_REPO=ghcr.io/<owner>/mcp-data-gateway and optionally IMAGE_TAG=<tag>."; \
	  exit 1; \
	fi
	@echo -e "$(G)Using app image:$(N) $(IMAGE)"

_tf-apply:
	@echo -e "$(G)▶ 1/7 Provisioning OVH cluster...$(N)"
	cd $(TFDIR) && terraform init -input=false && terraform apply -auto-approve

_kubeconfig:
	@echo -e "$(G)▶ 2/7 Fetching kubeconfig...$(N)"
	@mkdir -p $(HOME)/.kube
	cd $(TFDIR) && terraform output -raw kubeconfig > $(KUBECONFIG)
	@chmod 600 $(KUBECONFIG)
	@echo "Waiting for node to be Ready (up to 5 min)..."
	$(K) wait node --all --for=condition=Ready --timeout=300s

_secrets:
	@echo -e "$(G)▶ 3/7 Secrets...$(N)"
	@if [ ! -f $(ENV_FILE) ]; then \
	  printf 'PG_PASSWORD=%s\nMCP_HMAC_PEPPER=%s\nMCP_CONTENT_KEY=%s\nMCP_JWT_SECRET=%s\n' \
	    "$$(openssl rand -hex 24)" \
	    "$$(openssl rand -hex 32)" \
	    "$$(openssl rand -hex 32)" \
	    "$$(openssl rand -hex 32)" > $(ENV_FILE); \
	  chmod 600 $(ENV_FILE); \
	  echo -e "  $(G)Generated → $(ENV_FILE)$(N)"; \
	else \
	  echo -e "  Reusing existing $(ENV_FILE)"; \
	fi
	@set -a; source $(ENV_FILE); set +a; \
	  $(K) create namespace $(NS) --dry-run=client -o yaml | $(K) apply -f - && \
	  $(K) create secret generic mcp-gateway-secrets \
	    --namespace $(NS) \
	    --from-literal=db-url="jdbc:postgresql://postgresql.$(NS).svc.cluster.local:5432/mcpgateway?sslmode=disable" \
	    --from-literal=db-user=mcpuser \
	    --from-literal=db-password="$${PG_PASSWORD}" \
	    --from-literal=mcp-hmac-pepper="$${MCP_HMAC_PEPPER}" \
	    --from-literal=mcp-content-key="$${MCP_CONTENT_KEY}" \
	    --from-literal=mcp-jwt-secret="$${MCP_JWT_SECRET}" \
	    --dry-run=client -o yaml | $(K) apply -f -

_postgresql:
	@echo -e "$(G)▶ 4/7 Installing PostgreSQL (pgvector/pg16)...$(N)"
	$(H) uninstall postgresql --namespace $(NS) 2>/dev/null || true
	$(K) apply -f k8s/deps/postgresql-pgvector.yaml
	$(K) rollout status statefulset/postgresql -n $(NS) --timeout=5m

_observability:
	@echo -e "$(G)▶ 5/7 Installing observability (Prometheus + exporters + Grafana)...$(N)"
	$(K) apply -f k8s/deps/prometheus.yaml
	$(K) apply -f k8s/deps/node-exporter.yaml
	$(K) apply -f k8s/deps/kube-state-metrics.yaml
	$(K) apply -f k8s/deps/postgres-exporter.yaml
	@# Grafana admin password — generated once, persisted in .deploy.env so the
	@# UI is reachable across rolling restarts without rotating creds.
	@if ! grep -q '^GRAFANA_ADMIN_PASSWORD=' $(ENV_FILE) 2>/dev/null; then \
	  echo "GRAFANA_ADMIN_PASSWORD=$$(openssl rand -hex 16)" >> $(ENV_FILE); \
	  echo -e "  $(G)Generated GRAFANA_ADMIN_PASSWORD → $(ENV_FILE)$(N)"; \
	fi
	@set -a; source $(ENV_FILE); set +a; \
	  $(K) create secret generic grafana-admin \
	    --namespace $(NS) \
	    --from-literal=admin-password="$${GRAFANA_ADMIN_PASSWORD}" \
	    --dry-run=client -o yaml | $(K) apply -f -
	$(K) apply -f k8s/deps/grafana.yaml
	$(K) rollout status statefulset/prometheus        -n $(NS) --timeout=2m
	$(K) rollout status daemonset/node-exporter       -n $(NS) --timeout=2m
	$(K) rollout status deployment/kube-state-metrics -n $(NS) --timeout=2m
	$(K) rollout status deployment/postgres-exporter  -n $(NS) --timeout=2m
	$(K) rollout status deployment/grafana            -n $(NS) --timeout=2m

_ingress:
	@if grep -qE 'your-domain\.com|your-email@example' k8s/app/ingress.yaml k8s/app/cert-manager-issuer.yaml; then \
	  echo -e "$(Y)Skipping ingress — placeholders still present.$(N)"; \
	  echo "  → Set your domain in k8s/app/ingress.yaml"; \
	  echo "  → Set your email in k8s/app/cert-manager-issuer.yaml"; \
	  echo "  Then run: make _ingress"; \
	  exit 0; \
	fi
	@echo -e "$(G)▶ Installing ingress + TLS...$(N)"
	@# allow-snippet-annotations required for configuration-snippet security headers (nginx-ingress v1.x+)
	@# use-forwarded-headers passes real client IP to app (fixes rate limiter behind proxy)
	$(H) upgrade --install ingress-nginx ingress-nginx \
	  --repo https://kubernetes.github.io/ingress-nginx \
	  --namespace ingress-nginx --create-namespace \
	  --set controller.config.use-forwarded-headers=true \
	  --set controller.config.compute-full-forwarded-for=true \
	  --wait --timeout 5m
	$(H) upgrade --install cert-manager cert-manager \
	  --repo https://charts.jetstack.io \
	  --namespace cert-manager --create-namespace \
	  --set crds.enabled=true \
	  --wait --timeout 5m
	$(K) apply -f k8s/app/cert-manager-issuer.yaml
	$(K) apply -f k8s/app/ingress.yaml
	@echo -e "$(G)  Ingress ready. Public: https://$(DOMAIN)/mcp$(N)"
	@$(K) wait certificate mcp-gateway-tls -n $(NS) --for=condition=Ready --timeout=120s \
	  && echo -e "$(G)  TLS certificate issued.$(N)" \
	  || echo -e "$(Y)  TLS cert pending — DNS may need time. Check: kubectl describe certificate -n $(NS)$(N)"

_ingress-ip:
	@echo -e "$(G)▶ Installing nginx-ingress (LoadBalancer)...$(N)"
	$(H) upgrade --install ingress-nginx ingress-nginx \
	  --repo https://kubernetes.github.io/ingress-nginx \
	  --namespace ingress-nginx --create-namespace \
	  --set controller.config.use-forwarded-headers=true \
	  --set controller.config.compute-full-forwarded-for=true \
	  --wait --timeout 5m
	@echo -e "$(G)▶ Waiting for LoadBalancer IP...$(N)"
	@$(K) wait --namespace ingress-nginx \
	  --for=jsonpath='{.status.loadBalancer.ingress[0].ip}' \
	  service/ingress-nginx-controller --timeout=120s
	@IP=$$($(K) get svc ingress-nginx-controller -n ingress-nginx \
	  -o jsonpath='{.status.loadBalancer.ingress[0].ip}'); \
	  echo -e "$(G)  LoadBalancer IP: $$IP$(N)"; \
	  echo -e "  → nip.io domain:  mcp.$$IP.nip.io"; \
	  echo -e "  → Next: edit k8s/app/ingress.yaml + cert-manager-issuer.yaml, then: make _ingress"

_app:
	@echo -e "$(G)▶ 6/7 Deploying gateway...$(N)"
	$(K) apply -f k8s/app/namespace.yaml
	$(K) apply -f k8s/deps/tei-deployment.yaml
	$(K) apply -f k8s/app/serviceaccount.yaml
	$(K) apply -f k8s/app/networkpolicy.yaml
	$(K) apply -f k8s/app/service.yaml
	$(K) create configmap mcp-gateway-config \
	  --namespace $(NS) \
	  --from-literal=spring-profiles-active=prod \
	  --from-literal=oauth-issuer=https://mcp.$$($(K) get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "localhost").nip.io \
	  --dry-run=client -o yaml | $(K) apply -f -
	$(K) apply -f k8s/app/deployment.yaml
	$(K) set image deployment/mcp-gateway mcp-gateway=$(IMAGE) -n $(NS)
	@# In-cluster PostgreSQL has no TLS — disable the enforce-tls startup check
	$(K) set env deployment/mcp-gateway MCP_SECURITY_ENFORCE_TLS=false -n $(NS)
	$(K) rollout status deployment/mcp-gateway -n $(NS) --timeout=120s
	@sed -i '/^DEPLOYED_IMAGE_TAG=/d' $(ENV_FILE) 2>/dev/null; \
	  echo "DEPLOYED_IMAGE_TAG=$(IMAGE_TAG)" >> $(ENV_FILE)
	@echo -e "  $(G)Deployed image tag recorded in $(ENV_FILE)$(N)"

_smoke:
	@echo -e "$(G)▶ Smoke test...$(N)"
	@sleep 5
	@POD=$$($(K) get pod -n $(NS) -l app=mcp-gateway -o jsonpath='{.items[0].metadata.name}') && \
	  $(K) exec -n $(NS) $$POD -- wget -qO- http://localhost:8080/actuator/health \
	  && echo -e "$(G)✓ Health check passed$(N)" \
	  || echo -e "$(Y)⚠ Health check failed — check: make logs$(N)"
