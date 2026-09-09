# Infrastructure & deployment runbook

Operational steps for deploying the poker server to GCP under the Path B / cost-first constraints
(see `../docs/path-b-plan.md`).

> **Status: deployed.** Phase 5 is done — live at `https://poker-server-xz4wcwbsoq-uc.a.run.app`
> (project `pokeronline-499416`, region `us-central1`). Everything below is written as the
> once-per-environment setup sequence (useful for a second environment, or rebuilding from scratch),
> annotated with the real gotchas hit doing it for real — see the "Gotchas hit on the real deploy"
> section at the bottom before you start.

> **Prefer Terraform.** The whole stack is now described declaratively in [`terraform/`](terraform/) —
> `terraform apply` builds everything below in one shot, repeatably. Use that for a *new* environment.
> The `gcloud` steps here are what actually built the live deployment above (Terraform hadn't been
> applied yet when that happened — see `terraform/README.md`'s note on importing existing resources
> if you want to bring this environment under Terraform after the fact). They also remain the fallback
> for anything Terraform can't do (e.g. granting billing-account admin).

These `gcloud` steps run once per environment. Substitute `PROJECT_ID` and `REGION=us-central1` throughout.

```bash
export PROJECT_ID=poker-online-dev   # the live deploy used pokeronline-499416
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
  billingbudgets.googleapis.com \
  eventarc.googleapis.com \
  pubsub.googleapis.com
```

`eventarc.googleapis.com` is required for the gen2 kill-switch function's Pub/Sub trigger (§8) — easy
to miss since gen1 functions didn't need it.

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

## 5. Service accounts + IAM

Two service accounts: one the app *runs as* (`poker-run`), one Cloud Build *builds as* (`poker-build`).
Recent GCP projects no longer grant the default compute service account any project roles, so both
Cloud Build and Cloud Run need an explicit identity — you can't rely on the implicit default working.

```bash
gcloud iam service-accounts create poker-run --display-name="poker Cloud Run runtime"
export SA="poker-run@${PROJECT_ID}.iam.gserviceaccount.com"

# Firestore read/write, enqueue Cloud Tasks (Phase 2+), read the JWT secret.
for ROLE in roles/datastore.user roles/cloudtasks.enqueuer roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA" --role="$ROLE"
done
# Cloud Tasks delivers timer callbacks with an OIDC token; that SA needs run.invoker (Phase 2).
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA" --role="roles/run.invoker"

# Cloud Build's own identity — needed to submit builds and deploy gen2 functions (see gotchas below).
gcloud iam service-accounts create poker-build --display-name="poker Cloud Build"
export BUILD_SA="poker-build@${PROJECT_ID}.iam.gserviceaccount.com"
for ROLE in roles/artifactregistry.writer roles/logging.logWriter roles/storage.admin; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$BUILD_SA" --role="$ROLE"
done
```

## 6. Build & deploy

```bash
gcloud builds submit --region="$REGION" \
  --service-account="projects/${PROJECT_ID}/serviceAccounts/${BUILD_SA}" \
  --default-buckets-behavior=REGIONAL_USER_OWNED_BUCKET \
  --tag "${REGION}-docker.pkg.dev/${PROJECT_ID}/poker/poker-server:latest" .
```

`--service-account` is required (§5 — the default compute SA can't read its own source tarball out of
the Cloud Build staging bucket without extra grants). Once you pass `--service-account`, gcloud also
requires you to pick a logs-bucket behavior, hence `--default-buckets-behavior`.

```bash
# Edit infra/cloudrun-service.yaml: replace PROJECT_ID, confirm the image tag, then:
gcloud run services replace infra/cloudrun-service.yaml --region="$REGION"
gcloud run services add-iam-policy-binding poker-server --region="$REGION" \
  --member=allUsers --role=roles/run.invoker   # public service
```

`gcloud run services replace` has **no `--service-account` flag** — the runtime SA must be set
*inside* the YAML as `spec.template.spec.serviceAccountName`. The manifest already has
`serviceAccountName: poker-run@PROJECT_ID.iam.gserviceaccount.com` — just make sure the project ID
matches. Without it, the revision silently falls back to the default compute SA and every
`valueFrom.secretKeyRef` env var (`JWT_SECRET`, `INTERNAL_TOKEN`) fails with a permission-denied at
deploy time.

The manifest pins `minScale=0` / `maxScale=3` / `timeoutSeconds=3600` / `containerConcurrency=250`.
**Never set `minScale>0`** (bills 24/7).

> **New service URLs can take several minutes to propagate** through Google's edge before they resolve
> (the live deploy saw ~25 min once). During that window every path 404s with Google's generic branded
> error page, not the app's own 404 — that's the tell it's edge propagation, not an app bug. `/health`
> (see below) is the fastest way to confirm it's through.

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
>
> Verify it's actually wired up: start a round with two players, then
> `gcloud tasks list --queue=poker-timers --location="$REGION"` — you should see one scheduled task per
> pending turn. Once it fires, `gcloud logging read 'resource.type="cloud_run_revision" AND
> httpRequest.requestUrl:"/internal/timer-expire/"' --freshness=10m` shows the callback landing with a
> `200`.

## 8. Budget + kill-switch (makes < $5 a hard cap)

A budget alert only notifies. The Cloud Function in `billing-killswitch/` disables billing when cost
reaches the budget — the only true hard stop. It runs as its own dedicated service account
(`billing-killswitch`), not the default compute SA, so the `billing.admin` grant is scoped as tightly
as possible.

```bash
# Pub/Sub topic the budget publishes to.
gcloud pubsub topics create billing-alerts

# Dedicated identity for the function — keeps billing.admin off every other service account.
gcloud iam service-accounts create billing-killswitch --display-name="Budget kill-switch"
export KS_SA="billing-killswitch@${PROJECT_ID}.iam.gserviceaccount.com"

# Deploy the kill-switch (gen2). --build-service-account is required for the same reason as §6 (the
# default compute SA can't build); --service-account is the identity the function itself RUNS as.
gcloud functions deploy billing-killswitch \
  --gen2 --runtime=python312 --region="$REGION" \
  --source=infra/billing-killswitch --entry-point=stop_billing \
  --trigger-topic=billing-alerts \
  --service-account="$KS_SA" \
  --build-service-account="projects/${PROJECT_ID}/serviceAccounts/${BUILD_SA}" \
  --set-env-vars=GCP_PROJECT_ID="$PROJECT_ID"

# Grant the function's SA permission to disable billing (scope to the billing account). This is a
# genuinely sensitive grant (that SA can now detach billing from the project) — worth doing by hand
# rather than scripting blindly.
gcloud billing accounts add-iam-policy-binding <BILLING_ACCOUNT_ID> \
  --member="serviceAccount:${KS_SA}" --role="roles/billing.admin"
```

Then the budget itself:

```bash
# IMPORTANT: --budget-amount's currency must match the billing account's own currency
# (gcloud billing accounts describe <BILLING_ACCOUNT_ID> shows currencyCode), or the create call
# fails with a bare "Request contains an invalid argument" — no mention of currency in the error.
# Omit a currency suffix (just a bare number, e.g. --budget-amount=25) to use the account's currency
# unconditionally, rather than guessing at an ISO code.
gcloud billing budgets create \
  --billing-account=<BILLING_ACCOUNT_ID> \
  --display-name="poker-budget" \
  --budget-amount=5 \
  --filter-projects="projects/$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')" \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.9 \
  --threshold-rule=percent=1.0 \
  --notifications-rule-pubsub-topic="projects/${PROJECT_ID}/topics/billing-alerts"
```

(`terraform/budget.tf` has the same USD-hardcoded currency assumption — fix there too if your billing
account isn't USD.)

> Re-enabling after a trip: re-attach the billing account in the console (Billing → link account).

## Gotchas hit on the real deploy

These cost real time; read before you start rather than after you hit them.

- **`/healthz` is a reserved path — Cloud Run's own infrastructure intercepts it and never forwards it
  to the container.** Requests to it 404 at Google's edge (a branded Google error page, not the app's),
  even though every other path reaches the app fine. The app's probe route is `/health`. If you add a
  new health-check-style endpoint, don't name it `/healthz`.
- **The default compute service account has no project roles on recent GCP projects.** Both
  `gcloud builds submit` and `gcloud functions deploy` (gen2) default to it and fail with
  permission-denied on the Cloud Build staging bucket unless you pass an explicit `--service-account`
  (builds) / `--build-service-account` (functions) pointing at an SA that actually has
  `storage.admin`/`logging.logWriter`/`artifactregistry.writer`.
- **`gcloud run services replace` has no service-account flag.** Set `serviceAccountName` inside the
  YAML itself, or the revision runs as the (roleless) default compute SA and every Secret Manager env
  var fails closed at deploy time.
- **Billing account currency isn't necessarily USD.** `--budget-amount=5USD` fails outright if the
  billing account's own currency differs — match it or omit the currency suffix.
- **New Cloud Run URLs can take a long time (tens of minutes) to become routable** the very first time
  a service is created, independent of whether the revision itself is `Ready`. Don't debug the app
  before ruling this out.

## Cost guardrails checklist

- [x] `maxScale` pinned (3 — stateless coordination landed, so this bounds spend, not correctness)
- [x] `minScale=0`, no `--min-instances`
- [x] Budget + kill-switch live and tested
- [x] Artifact Registry cleanup policy set
- [x] Firestore TTL enabled
- [x] Per-IP rate limiting on (app: `RATE_LIMIT_MUTATIONS`, default 30/min)
- [x] No external Load Balancer, no Cloud Armor, no Memorystore, no VPC connector
