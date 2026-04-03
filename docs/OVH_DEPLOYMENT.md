# Deploying to OVHcloud — Step-by-Step Guide

> Complete walkthrough from zero OVH account to a running Kubernetes cluster.
> Written for beginners — every step is explained, nothing is assumed.

---

## What you will build

By the end of this guide you will have:

```
Internet
   │  HTTPS only (Let's Encrypt cert, auto-renewed)
   ▼
OVHcloud Managed Kubernetes (GRA7, Gravelines, France)
   └── Namespace: mcp-demo
         ├── mcp-gateway    (2 replicas, 512Mi each)
         ├── postgresql     (Bitnami Helm chart, 5Gi PV)
         └── minio          (Bitnami Helm chart, 10Gi PV)
```

The cluster is created by Terraform. The apps are deployed with Helm and `kubectl`.
Terraform's own state is stored in OVH Object Storage (the S3-compatible service).

---

## Prerequisites (install these first)

| Tool | Version | Install |
|---|---|---|
| Terraform | ≥ 1.6 | https://developer.hashicorp.com/terraform/install |
| kubectl | any recent | https://kubernetes.io/docs/tasks/tools/ |
| Helm | ≥ 3.14 | https://helm.sh/docs/intro/install/ |

Verify installations:
```bash
terraform version     # Terraform v1.x.x
kubectl version --client
helm version
```

---

## Part 1 — OVHcloud account

### 1.1 Create your account

1. Go to https://www.ovhcloud.com/en/public-cloud/
2. Click **Order** → **Start my project**
3. Fill in your details. Use a real email address — you will receive a confirmation link.
4. Verify your email when the confirmation arrives.

> OVH requires a payment method before you can create cloud resources, even for free-tier usage.
> No charge is made until you actually create paid resources.

### 1.2 Add a payment method

1. Log in at https://www.ovh.com/manager/
2. Click your name (top-right) → **Payment methods**
3. Add a credit card or SEPA direct debit.

### 1.3 Create a Public Cloud project

A "Public Cloud project" is OVH's term for an isolated billing and resource container —
similar to an AWS account or GCP project.

1. In the manager, go to **Public Cloud** (left sidebar) → **+ Create a new project**
2. Name it `mcp-data-gateway` (or anything you like)
3. Accept and confirm

After creation, note the **Project ID** (also called `service_name`) — it is a long
alphanumeric string visible in the URL or on the project overview page.

```
Example: 8f42a1b3c9d04e5f6a7b8c9d0e1f2a3b
```

This is your `ovh_project_id` in Terraform.

---

## Part 2 — OVH API credentials (Terraform authentication)

Terraform talks to OVH via their API, not the web console. You need three tokens:
`application_key`, `application_secret`, and `consumer_key`.

### 2.1 Create an application key and secret

1. Go to https://eu.api.ovh.com/createApp/
2. Fill in:
   - **Application name**: `mcp-terraform`
   - **Application description**: `Terraform automation for mcp-data-gateway`
3. Click **Create keys**
4. You will see your `Application Key` and `Application Secret`. **Save them now** —
   the secret is shown only once.

### 2.2 Generate a consumer key

The consumer key authorizes specific API scopes. Run this `curl` command in your terminal:

```bash
curl -s -X POST https://eu.api.ovh.com/1.0/auth/credential \
  -H "X-Ovh-Application: YOUR_APPLICATION_KEY" \
  -H "Content-type: application/json" \
  -d '{
    "accessRules": [
      {"method": "GET",    "path": "/cloud/*"},
      {"method": "POST",   "path": "/cloud/*"},
      {"method": "PUT",    "path": "/cloud/*"},
      {"method": "DELETE", "path": "/cloud/*"}
    ],
    "redirection": "https://www.ovh.com"
  }'
```

Replace `YOUR_APPLICATION_KEY` with the key from step 2.1.

The response looks like:

```json
{
  "validationUrl": "https://eu.api.ovh.com/auth/?credentialToken=XXXXX",
  "consumerKey": "abc123def456ghi789",
  "state": "pendingValidation"
}
```

1. Copy the `consumerKey` value — that is your `consumer_key` for Terraform.
2. Open the `validationUrl` in your browser and click **Authorize**.
3. The consumer key is now active.

### 2.3 What you have now

You should have three values:

```
application_key    = "xxxxxxxxxxxx"
application_secret = "yyyyyyyyyyyy"
consumer_key       = "zzzzzzzzzzzz"
```

Keep these safe. Do not put them in any file that gets committed to git.

---

## Part 3 — Object Storage bucket for Terraform state

Terraform needs to store its state file somewhere. We use OVH Object Storage
(S3-compatible), so the state is shared and versioned.

### 3.1 Create a storage user (S3 credentials)

1. In the OVH manager, go to your Public Cloud project → **Object Storage**
2. Click **Users & Roles** (or **S3 Users** depending on UI version) → **+ Add user**
3. Give it a description: `terraform-state`
4. Note the **Access key** and **Secret key** shown after creation.
   (If you miss them, you can regenerate — but only one pair is shown at a time.)

### 3.2 Create the state bucket

1. In **Object Storage** → **+ Create container**
2. Settings:
   - **Region**: `GRA` (Gravelines — same region as the cluster)
   - **Container type**: **Standard** (not archive)
   - **Container name**: `mcp-tf-state`
3. Create it.

The bucket must exist before `terraform init` can use it as a backend.

### 3.3 Export the S3 credentials as environment variables

Terraform's S3 backend reads these env vars automatically:

```bash
export AWS_ACCESS_KEY_ID="your-object-storage-access-key"
export AWS_SECRET_ACCESS_KEY="your-object-storage-secret-key"
```

Add these to your shell profile (`~/.zshrc` or `~/.bashrc`) or use a secrets manager.

---

## Part 4 — Terraform: create the Kubernetes cluster

### 4.1 Fill in terraform.tfvars

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` with your values:

```hcl
ovh_project_id         = "8f42a1b3c9d04e5f6a7b8c9d0e1f2a3b"   # Part 1.3
ovh_application_key    = "xxxxxxxxxxxx"                         # Part 2.1
ovh_application_secret = "yyyyyyyyyyyy"                         # Part 2.1
consumer_key           = "zzzzzzzzzzzz"                         # Part 2.2
```

> `terraform.tfvars` is in `.gitignore`. It will never be committed.

### 4.2 Initialize Terraform

```bash
terraform init
```

This downloads the OVH Terraform provider and connects to the state backend.
You should see:

```
Initializing the backend...
Successfully configured the backend "s3"!
Initializing provider plugins...
- Installing ovh/ovh v0.40.x...
Terraform has been successfully initialized!
```

If you see `Error: Failed to get existing workspaces` — double-check that:
- The `mcp-tf-state` bucket exists in `GRA`
- `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are exported in your current shell

### 4.3 Preview what Terraform will create

```bash
terraform plan
```

You should see two resources to be created:
- `ovh_cloud_project_kube.mcp_cluster` — a Managed Kubernetes 1.30 cluster
- `ovh_cloud_project_kube_nodepool.default` — a 2-node pool of `b3-8` (4 vCPU, 8GB RAM)

> **Cost estimate**: `b3-8` costs approximately €0.08/hour per node (~€58/month for 2 nodes).
> Delete the cluster when not in use: `terraform destroy`.

### 4.4 Create the cluster

```bash
terraform apply
```

Type `yes` when prompted.

Cluster creation takes **10–20 minutes**. You will see:

```
ovh_cloud_project_kube.mcp_cluster: Creating...
ovh_cloud_project_kube.mcp_cluster: Still creating... [10s elapsed]
...
Apply complete! Resources: 2 added, 0 changed, 0 destroyed.

Outputs:
api_server_url = "https://xxxxxxxx.c1.gra7.k8s.ovh.net"
cluster_id     = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
kubeconfig     = <sensitive>
```

### 4.5 Get the kubeconfig

```bash
terraform output -raw kubeconfig > ~/.kube/ovh-mcp.yaml
export KUBECONFIG=~/.kube/ovh-mcp.yaml
```

Verify connectivity:

```bash
kubectl get nodes
```

Expected output (after a few minutes for nodes to be Ready):

```
NAME                                  STATUS   ROLES    AGE   VERSION
nodepool-xxxxxx-node-xxxxxx-00000     Ready    <none>   3m    v1.30.x
nodepool-xxxxxx-node-xxxxxx-00001     Ready    <none>   3m    v1.30.x
```

---

## Part 5 — Cluster addons

Before deploying the app, two cluster-level services must be installed:
- **ingress-nginx** — receives incoming HTTP/HTTPS traffic and routes it to pods
- **cert-manager** — automatically issues and renews Let's Encrypt TLS certificates

### 5.1 Install ingress-nginx

```bash
helm upgrade --install ingress-nginx ingress-nginx \
  --repo https://kubernetes.github.io/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace \
  --set controller.service.type=LoadBalancer
```

Wait for the load balancer to get an external IP (takes 1–3 minutes on OVH):

```bash
kubectl get svc -n ingress-nginx ingress-nginx-controller --watch
```

When `EXTERNAL-IP` changes from `<pending>` to an IP address, copy it.

```
NAME                       TYPE           CLUSTER-IP     EXTERNAL-IP      PORT(S)
ingress-nginx-controller   LoadBalancer   10.3.212.45    57.128.xxx.yyy   80:31080/TCP,443:31443/TCP
```

### 5.2 Point your domain at the load balancer

In your DNS provider (OVHcloud DNS, Cloudflare, etc.), create an A record:

```
mcp.your-domain.com  →  57.128.xxx.yyy   (the EXTERNAL-IP from above)
```

DNS propagation takes a few minutes to several hours depending on TTL.

> If you don't have a domain, you can use a free one from https://freedns.afraid.org or
> skip TLS for now and use `kubectl port-forward` for local access (see Part 8).

### 5.3 Update the Ingress hostname

Edit `k8s/app/ingress.yaml` — replace `mcp.ancoris-demo.io` with your domain:

```yaml
spec:
  tls:
    - hosts:
        - mcp.your-domain.com      # ← your domain
      secretName: mcp-gateway-tls
  rules:
    - host: mcp.your-domain.com   # ← your domain
```

### 5.4 Install cert-manager

```bash
helm upgrade --install cert-manager cert-manager \
  --repo https://charts.jetstack.io \
  --namespace cert-manager \
  --create-namespace \
  --set crds.enabled=true
```

Apply the Let's Encrypt issuer (replace the email with yours):

```bash
# k8s/app/cert-manager-issuer.yaml already exists in the repo
# Edit it first to set your email:
#   spec.acme.email: your@email.com
kubectl apply -f k8s/app/cert-manager-issuer.yaml
```

---

## Part 6 — Deploy dependencies (PostgreSQL and MinIO)

### 6.1 Create the namespace

```bash
kubectl apply -f k8s/app/namespace.yaml
```

### 6.2 Generate strong passwords

```bash
export PG_PASSWORD=$(openssl rand -hex 24)
export MINIO_PASSWORD=$(openssl rand -hex 24)

# Save them somewhere safe — you will need them for the app secret
echo "PG_PASSWORD: $PG_PASSWORD"
echo "MINIO_PASSWORD: $MINIO_PASSWORD"
```

### 6.3 Install PostgreSQL

```bash
helm upgrade --install postgresql \
  oci://registry-1.docker.io/bitnamicharts/postgresql \
  -f k8s/deps/postgresql-values.yaml \
  --set auth.password="$PG_PASSWORD" \
  --namespace mcp-demo
```

Wait for the pod to be running:

```bash
kubectl get pods -n mcp-demo -l app.kubernetes.io/name=postgresql --watch
```

When you see `1/1 Running`, PostgreSQL is ready.

### 6.4 Install MinIO

```bash
helm upgrade --install minio \
  oci://registry-1.docker.io/bitnamicharts/minio \
  -f k8s/deps/minio-values.yaml \
  --set auth.rootPassword="$MINIO_PASSWORD" \
  --namespace mcp-demo
```

```bash
kubectl get pods -n mcp-demo -l app.kubernetes.io/name=minio --watch
```

When you see `1/1 Running`, MinIO is ready. The `mcp-documents` bucket is created
automatically by the Bitnami chart's `defaultBuckets` setting.

---

## Part 7 — Deploy the application

### 7.1 Create the Kubernetes Secret

The app's credentials are stored as a Kubernetes Secret (base64-encoded values,
kept in etcd, never in git).

```bash
# Construct the PostgreSQL JDBC URL
PG_JDBC="jdbc:postgresql://postgresql.mcp-demo.svc.cluster.local:5432/mcpgateway?sslmode=disable"
# Note: sslmode=disable is OK here because PostgreSQL is on the same cluster network.
# TLS termination is at the Ingress; in-cluster traffic uses the cluster overlay network.
# For production with external PostgreSQL, use sslmode=require.

kubectl create secret generic mcp-gateway-secrets \
  --namespace mcp-demo \
  --from-literal=db-url="$PG_JDBC" \
  --from-literal=db-user="mcpuser" \
  --from-literal=db-password="$PG_PASSWORD" \
  --from-literal=minio-access-key="minioadmin" \
  --from-literal=minio-secret-key="$MINIO_PASSWORD"
```

Verify the secret was created (values are intentionally hidden):

```bash
kubectl get secret mcp-gateway-secrets -n mcp-demo
```

### 7.2 Apply remaining manifests

```bash
kubectl apply -f k8s/app/configmap.yaml
kubectl apply -f k8s/app/serviceaccount.yaml
kubectl apply -f k8s/app/networkpolicy.yaml
kubectl apply -f k8s/app/service.yaml
kubectl apply -f k8s/app/deployment.yaml
kubectl apply -f k8s/app/ingress.yaml
```

### 7.3 Watch the deployment roll out

```bash
kubectl rollout status deployment/mcp-gateway -n mcp-demo --timeout=120s
```

You should see:

```
Waiting for deployment "mcp-gateway" rollout to finish: 0 of 2 updated replicas are available...
Waiting for deployment "mcp-gateway" rollout to finish: 1 of 2 updated replicas are available...
deployment "mcp-gateway" successfully rolled out
```

If it times out, check the pod logs:

```bash
kubectl logs -n mcp-demo -l app=mcp-gateway --tail=50
```

### 7.4 Verify the health endpoint

```bash
curl https://mcp.your-domain.com/actuator/health
```

Expected:

```json
{"status":"UP"}
```

If you see a TLS error, cert-manager may still be issuing the certificate
(takes 1–3 minutes). Check with:

```bash
kubectl describe certificate mcp-gateway-tls -n mcp-demo
```

Look for `Status: True` on the `Ready` condition.

---

## Part 7.5 — Audit log hardening (SEC-AUDIT2)

The gateway writes a second copy of every audit event to a structured JSON file
(`/var/log/mcp/audit.json`) that lives **outside PostgreSQL**. A database superuser
can `ALTER TABLE audit_logs DISABLE TRIGGER ALL` to bypass the DB trigger; they
cannot remove entries from an OS-level append-only file.

After the first pod starts, run the following **once per node** (or in the pod via
`kubectl exec`) to set the append-only filesystem attribute:

```bash
# Inside the mcp-gateway pod (or on the node if using a hostPath volume)
mkdir -p /var/log/mcp
chown mcp-gateway:mcp-gateway /var/log/mcp
chattr +a /var/log/mcp/audit.json   # append-only: no overwrite, no delete
lsattr /var/log/mcp/audit.json      # verify: output must contain 'a' flag
```

After hardening, verify that a write attempt is blocked:
```bash
echo "" > /var/log/mcp/audit.json   # must fail: Operation not permitted
```

Query audit events with `jq`:
```bash
jq '.mdc | {tool: .tool_name, summary: .result_summary}' /var/log/mcp/audit.json
```

> **Note:** `chattr +a` requires a supported filesystem (ext4, xfs). On WORM
> block storage or network filesystems it may not be available — in that case
> ship events to a separate syslog receiver over TLS for equivalent guarantees.

---

## Part 8 — Testing the deployment

### Quick smoke test (no session required)

```bash
# No API key → must return 401
curl -s -o /dev/null -w "%{http_code}" https://mcp.your-domain.com/mcp \
  -X POST \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# Expected: 401
```

### Full MCP session test

```bash
# Step 1: initialize
RESP=$(curl -s -D - -X POST https://mcp.your-domain.com/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-API-Key: demo-readonly-key-001" \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')

SESSION=$(echo "$RESP" | grep -i "Mcp-Session-Id:" | awk '{print $2}' | tr -d '\r')
echo "Session: $SESSION"

# Step 2: confirm initialized
curl -s -X POST https://mcp.your-domain.com/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-API-Key: demo-readonly-key-001" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'

# Step 3: list tools
curl -s -X POST https://mcp.your-domain.com/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-API-Key: demo-readonly-key-001" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

### Port-forward alternative (no domain required)

If you skipped the domain step, access the app directly:

```bash
kubectl port-forward svc/mcp-gateway 8080:80 -n mcp-demo
# Then use http://localhost:8080 in the curl commands above
```

---

## Part 9 — Pushing a new image (CI/CD flow)

The GitHub Actions CI pipeline in `.github/workflows/` builds and pushes the Docker image
to `ghcr.io` on every push to `main`. The Kubernetes deployment uses:

```yaml
image: ghcr.io/ancoris/mcp-data-gateway:latest
```

To trigger a new deployment after a code push:

```bash
# Option A: rolling restart (pulls the new :latest image)
kubectl rollout restart deployment/mcp-gateway -n mcp-demo

# Option B: use a versioned image tag (recommended for production)
kubectl set image deployment/mcp-gateway \
  mcp-gateway=ghcr.io/ancoris/mcp-data-gateway:v1.2.3 \
  -n mcp-demo
```

For zero-downtime updates, the deployment already uses 2 replicas and rolling update strategy
(default in Kubernetes). One pod is updated at a time; the old pod stays alive until the
new one passes its readiness probe (`/actuator/health` returns 200).

---

## Part 10 — Tearing down

```bash
# Delete Kubernetes resources (keeps the cluster)
kubectl delete namespace mcp-demo

# Delete the cluster (stops all charges for compute)
cd infra/terraform
terraform destroy
```

> Terraform destroy takes ~5 minutes. The OVH Object Storage bucket (`mcp-tf-state`)
> and its contents are **not** deleted by Terraform — delete it manually in the console
> if you no longer need the state.

---

## Troubleshooting

### Pod stuck in `ImagePullBackOff`

```bash
kubectl describe pod -n mcp-demo -l app=mcp-gateway
```

Look for `Failed to pull image`. The image `ghcr.io/ancoris/mcp-data-gateway:latest`
is in a private registry. You need to create an image pull secret:

```bash
kubectl create secret docker-registry ghcr-secret \
  --docker-server=ghcr.io \
  --docker-username=YOUR_GITHUB_USERNAME \
  --docker-password=YOUR_GITHUB_PAT \
  --namespace mcp-demo
```

Then add `imagePullSecrets` to `k8s/app/deployment.yaml`:

```yaml
spec:
  imagePullSecrets:
    - name: ghcr-secret
```

### Pod stuck in `CrashLoopBackOff`

```bash
kubectl logs -n mcp-demo -l app=mcp-gateway --previous
```

Common causes:
- `DB_URL must contain 'sslmode=require'` — `mcp.security.enforce-tls=true` but you used
  an in-cluster URL without SSL. Either add `?sslmode=require` to the JDBC URL (requires
  PostgreSQL TLS setup) or add `MCP_SECURITY_ENFORCE_TLS=false` to the ConfigMap.
  In-cluster PostgreSQL without TLS is acceptable if you trust the cluster network.
- `Connection refused` to PostgreSQL or MinIO — the dependency pods are not yet ready.
  Check: `kubectl get pods -n mcp-demo`
- `Failed to get existing workspaces` during Terraform — see Part 4.2.

### Certificate not issuing

```bash
kubectl describe certificaterequest -n mcp-demo
kubectl describe order -n mcp-demo
```

Common cause: Let's Encrypt cannot reach your domain for the HTTP-01 challenge.
Verify the A record points to the correct EXTERNAL-IP and that port 80 is not blocked.

### `terraform apply` fails with 401/403

The consumer key has expired or was never validated. Repeat Part 2.2 to generate a new one.

### Nodes not joining (NotReady for > 10 minutes)

In the OVH manager → Public Cloud → your project → Managed Kubernetes → your cluster →
**Node pools** — check the node status there. If nodes show errors, destroy the pool and
re-apply Terraform. This is rare but can happen during OVH platform maintenance.

---

## Cost reference (OVH GRA7, 2025 prices)

| Resource | Spec | Price |
|---|---|---|
| 2 × `b3-8` nodes | 4 vCPU, 8GB RAM each | ~€0.08/h each = ~€115/mo |
| Load Balancer (ingress) | OVH Floating IP | ~€0.01/h = ~€7/mo |
| Object Storage (state) | < 1MB | Effectively free |
| Persistent volumes (15Gi total) | Block storage | ~€0.00015/GB/h = ~€1.5/mo |
| **Total** | | **~€124/mo** |

For a development/demo cluster, scale down to 1 node and delete when not in use:

```bash
# Scale to 0 nodes (cluster stays, no compute charges)
# Not directly supported — use terraform apply with desired_nodes=0
# Or: delete the node pool in the OVH console
```
