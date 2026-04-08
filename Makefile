SHELL := /bin/bash
.PHONY: up down open status logs help _validate-image _validate-config _ingress

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
IMAGE_TAG  ?= develop
IMAGE      ?= $(IMAGE_REPO):$(IMAGE_TAG)

# ── Colours ───────────────────────────────────────────────────────────────────
G := \033[0;32m
Y := \033[0;33m
N := \033[0m

# ─────────────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  make up      Provision cluster + deploy app  (~20 min first run)"
	@echo "  make down    Destroy cluster + stop charges  (~5 min)"
	@echo "  make open    Port-forward → http://localhost:8080"
	@echo "  make status  Show pod and service status"
	@echo "  make logs    Tail gateway logs"
	@echo ""
	@echo "  App image defaults to IMAGE=$(IMAGE)"
	@echo "  Override with: make up IMAGE_REPO=ghcr.io/<owner>/mcp-data-gateway IMAGE_TAG=develop"
	@echo ""
	@echo "  Ingress domain read from k8s/app/ingress.yaml — set your domain + email first."
	@echo "  Secrets are auto-generated on first 'make up' and saved to $(ENV_FILE)."
	@echo "  Keep that file safe — loss of MCP_CONTENT_KEY = encrypted data unrecoverable."
	@echo ""

# ── Main targets ──────────────────────────────────────────────────────────────

up: _validate-image _validate-config _tf-apply _kubeconfig _secrets _postgresql _app _ingress _smoke
	@echo -e "$(G)✓ Cluster is up.$(N)"
	@echo -e "  Public:  https://$(DOMAIN)/mcp"
	@echo -e "  Local:   make open → http://localhost:8080/mcp"

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

status:
	$(K) get pods,svc -n $(NS)

logs:
	$(K) logs -n $(NS) -l app=mcp-gateway --tail=100 -f

# ── Internal steps ────────────────────────────────────────────────────────────

_validate-config:
	@if grep -qE 'your-domain\.com|your-email@example' k8s/app/ingress.yaml k8s/app/cert-manager-issuer.yaml; then \
	  echo "Error: placeholder values still present in ingress manifests."; \
	  echo "  → Set your domain in k8s/app/ingress.yaml"; \
	  echo "  → Set your email in k8s/app/cert-manager-issuer.yaml"; \
	  exit 1; \
	fi

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
	@echo -e "$(G)▶ 1/6 Provisioning OVH cluster...$(N)"
	cd $(TFDIR) && terraform init -input=false && terraform apply -auto-approve

_kubeconfig:
	@echo -e "$(G)▶ 2/6 Fetching kubeconfig...$(N)"
	@mkdir -p $(HOME)/.kube
	cd $(TFDIR) && terraform output -raw kubeconfig > $(KUBECONFIG)
	@chmod 600 $(KUBECONFIG)
	@echo "Waiting for node to be Ready (up to 5 min)..."
	$(K) wait node --all --for=condition=Ready --timeout=300s

_secrets:
	@echo -e "$(G)▶ 3/6 Secrets...$(N)"
	@if [ ! -f $(ENV_FILE) ]; then \
	  printf 'PG_PASSWORD=%s\nMCP_HMAC_PEPPER=%s\nMCP_CONTENT_KEY=%s\n' \
	    "$$(openssl rand -hex 24)" \
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
	    --dry-run=client -o yaml | $(K) apply -f -

_postgresql:
	@echo -e "$(G)▶ 4/6 Installing PostgreSQL...$(N)"
	@set -a; source $(ENV_FILE); set +a; \
	  $(H) upgrade --install postgresql \
	    oci://registry-1.docker.io/bitnamicharts/postgresql \
	    -f k8s/deps/postgresql-values.yaml \
	    --set auth.password="$${PG_PASSWORD}" \
	    --namespace $(NS) --create-namespace \
	    --wait --timeout 5m

_ingress:
	@echo -e "$(G)▶ 6/6 Installing ingress + TLS...$(N)"
	@# allow-snippet-annotations required for configuration-snippet security headers (nginx-ingress v1.x+)
	@# use-forwarded-headers passes real client IP to app (fixes rate limiter behind proxy)
	$(H) upgrade --install ingress-nginx ingress-nginx \
	  --repo https://kubernetes.github.io/ingress-nginx \
	  --namespace ingress-nginx --create-namespace \
	  --set controller.config.allow-snippet-annotations=true \
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
	@$(K) wait certificate mcp-gateway-tls -n $(NS) --for=condition=Ready --timeout=120s \
	  && echo -e "$(G)  TLS certificate issued.$(N)" \
	  || echo -e "$(Y)  TLS cert pending — DNS may need time. Check: kubectl describe certificate -n $(NS)$(N)"

_app:
	@echo -e "$(G)▶ 5/6 Deploying gateway...$(N)"
	$(K) apply -f k8s/app/namespace.yaml
	$(K) apply -f k8s/app/serviceaccount.yaml
	$(K) apply -f k8s/app/networkpolicy.yaml
	$(K) apply -f k8s/app/service.yaml
	$(K) create configmap mcp-gateway-config \
	  --namespace $(NS) \
	  --from-literal=spring-profiles-active=prod \
	  --dry-run=client -o yaml | $(K) apply -f -
	$(K) apply -f k8s/app/deployment.yaml
	$(K) set image deployment/mcp-gateway mcp-gateway=$(IMAGE) -n $(NS)
	@# In-cluster PostgreSQL has no TLS — disable the enforce-tls startup check
	$(K) set env deployment/mcp-gateway MCP_SECURITY_ENFORCE_TLS=false -n $(NS)
	$(K) rollout status deployment/mcp-gateway -n $(NS) --timeout=120s

_smoke:
	@echo -e "$(G)▶ Smoke test...$(N)"
	@sleep 5
	@POD=$$($(K) get pod -n $(NS) -l app=mcp-gateway -o jsonpath='{.items[0].metadata.name}') && \
	  $(K) exec -n $(NS) $$POD -- wget -qO- http://localhost:8080/actuator/health \
	  && echo -e "$(G)✓ Health check passed$(N)" \
	  || echo -e "$(Y)⚠ Health check failed — check: make logs$(N)"
