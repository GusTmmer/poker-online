# Infrastructure & deployment runbook

Operational steps for deploying the poker server to GCP under the Path B / cost-first constraints
(see `../docs/path-b-plan.md`).

> **Prefer Terraform.** The whole stack is now described declaratively in [`terraform/`](terraform/) —
> `terraform apply` builds everything below in one shot, repeatably. Use that. The `gcloud` steps here
> remain as a manual reference / fallback (e.g. if you can't grant billing-account admin via Terraform).

These `gcloud` steps run once per environment. Substitute `PROJECT_ID` and `REGION=us-central1` throughout.

```bash
export PROJECT_ID=poker-online-dev
export REGION=us-central1
gcloud config set project "$PROJECT_ID"
```

## 1. Enable APIs

```bash
gcloud services enable \
  run.googleapis.com \
  firestore.googleapis.com \
  cloudtasks.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  secretmanager.googleapis.com \
  cloudfunctions.googleapis.com \
  cloudbilling.googleapis.com \
  billingbudgets.googleapis.com
```

## 2. Firestore (Native) + TTL policy

```bash
# One-time: create the Native-mode database in-region.
gcloud firestore databases create --location="$REGION"

# TTL policy so expired tables (the `expiresAt` field, set 12h out by the app) are actually deleted.
gcloud firestore fields ttls update expiresAt \
  --collection-group=tables --enable-ttl
```

Lock down client access (clients only ever reach Firestore through the API): set security rules that
deny all direct access. The runtime service account uses IAM (below), not rules.

## 3. Artifact Registry + image cleanup policy

```bash
gcloud artifacts repositories create poker \
  --repository-format=docker --location="$REGION"

# Keep storage under the 0.5 GB always-free limit: keep only the most recent few images.
cat > /tmp/cleanup.json <<'EOF'
[
  {"name":"keep-recent","action":{"type":"Keep"},"mostRecentVersions":{"keepCount":3}},
  {"name":"delete-old","action":{"type":"Delete"},"condition":{"olderThan":"604800s"}}
]
EOF
gcloud artifacts repositories set-cleanup-policies poker \
  --location="$REGION" --policy=/tmp/cleanup.json
```

## 4. Secrets (JWT + internal token)

```bash
# Signs player JWT cookies.
printf '%s' "$(openssl rand -base64 32)" | gcloud secrets create jwt-secret --data-file=-
# Shared secret Cloud Tasks sends as a header so only the queue can call /internal/* (turn-timer expiry).
printf '%s' "$(openssl rand -base64 32)" | gcloud secrets create internal-token --data-file=-
```

## 5. Service account + IAM

```bash
gcloud iam service-accounts create poker-run --display-name="poker Cloud Run runtime"
export SA="poker-run@${PROJECT_ID}.iam.gserviceaccount.com"

# Firestore read/write, enqueue Cloud Tasks (Phase 2+), read the JWT secret.
for ROLE in roles/datastore.user roles/cloudtasks.enqueuer roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA" --role="$ROLE"
done
# Cloud Tasks delivers timer callbacks with an OIDC token; that SA needs run.invoker (Phase 2).
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA" --role="roles/run.invoker"
```

## 6. Build & deploy

```bash
gcloud builds submit --tag "${REGION}-docker.pkg.dev/${PROJECT_ID}/poker/poker-server:latest"

# Edit infra/cloudrun-service.yaml: replace PROJECT_ID, confirm the image tag, then:
gcloud run services replace infra/cloudrun-service.yaml --region="$REGION" --service-account="$SA"
gcloud run services add-iam-policy-binding poker-server --region="$REGION" \
  --member=allUsers --role=roles/run.invoker   # public service
```

The manifest pins `minScale=0` / `maxScale=1` / `timeoutSeconds=3600` / `containerConcurrency=250`.
**Never set `minScale>0`** (bills 24/7). Raise `maxScale` only after Phase 4 (stateless coordination).

## 7. Cloud Tasks queue (durable turn + vote timers)

One queue serves both timers: it calls `POST /internal/timer-expire/{tableId}` when a turn clock
expires and `POST /internal/vote-expire/{tableId}/{sessionId}` when a vote times out.

```bash
gcloud tasks queues create poker-timers --location="$REGION"
```

The service reads `CLOUD_TASKS_LOCATION` (default `us-central1`), `CLOUD_TASKS_QUEUE` (default
`poker-timers`), `INTERNAL_TOKEN` (from Secret Manager, §4), and **`SERVICE_URL`** — its own public URL,
which Cloud Run assigns on first deploy. So deploy once, read the URL, then set `SERVICE_URL` and
redeploy (or map a custom domain and use that):

```bash
gcloud run services describe poker-server --region="$REGION" --format='value(status.url)'
# put that into infra/cloudrun-service.yaml (SERVICE_URL) and re-apply
```

> The runtime SA already has `roles/cloudtasks.enqueuer` (§5) to create tasks. Delivery is at-least-once;
> the handler ignores stale deliveries via the turn token, so no task de-duplication is needed.

## 8. Budget + kill-switch (makes < $5 a hard cap)

A budget alert only notifies. The Cloud Function in `billing-killswitch/` disables billing when cost
reaches the budget — the only true hard stop.

```bash
# Pub/Sub topic the budget publishes to.
gcloud pubsub topics create billing-alerts

# Deploy the kill-switch (gen2). Its runtime SA needs billing admin on the project.
gcloud functions deploy billing-killswitch \
  --gen2 --runtime=python312 --region="$REGION" \
  --source=infra/billing-killswitch --entry-point=stop_billing \
  --trigger-topic=billing-alerts \
  --set-env-vars=GCP_PROJECT_ID="$PROJECT_ID"

# Grant the function's SA permission to disable billing (scope to the billing account).
# gcloud billing accounts add-iam-policy-binding <BILLING_ACCOUNT_ID> \
#   --member="serviceAccount:<function-sa>" --role="roles/billing.admin"

# Create the $5 budget wired to that topic (via console: Billing → Budgets & alerts,
# thresholds 50/90/100%, "Connect a Pub/Sub topic" → billing-alerts), or `gcloud billing budgets create`.
```

> Re-enabling after a trip: re-attach the billing account in the console (Billing → link account).

## Cost guardrails checklist

- [ ] `maxScale` pinned (1 now; ≤3 after Phase 4)
- [ ] `minScale=0`, no `--min-instances`
- [ ] Budget + kill-switch live and tested
- [ ] Artifact Registry cleanup policy set
- [ ] Firestore TTL enabled
- [ ] Per-IP rate limiting on (app: `RATE_LIMIT_MUTATIONS`, default 30/min)
- [ ] No external Load Balancer, no Cloud Armor, no Memorystore, no VPC connector
