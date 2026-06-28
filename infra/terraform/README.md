# Deploying the BE stack with Terraform

This describes the **whole** backend as code, so `terraform apply` builds it and `terraform destroy`
tears it down — repeatably, no click-ops. If you know AWS: Terraform here ≈ CloudFormation/CDK, the
`google` provider ≈ the AWS provider, and the `.tfstate` file ≈ the CloudFormation stack's record of
what exists.

## What it can and can't capture (the honest part)

Terraform describes **almost everything**: API enablement, Firestore + its TTL policy, Artifact
Registry + cleanup policy, Cloud Tasks queue, Secret Manager secrets (values generated, never in the
repo), the runtime service account + IAM, the Cloud Run service, and the budget → Pub/Sub → kill-switch
function. Two things it genuinely *can't* do cleanly — these are real, not laziness:

1. **It doesn't build the container image.** Terraform deploys an image; it doesn't compile your code.
   So there's an external step (`gcloud builds submit`) and you pass the image ref in as `var.image`.
2. **`SERVICE_URL` is a chicken-and-egg.** Cloud Run assigns the service its public URL only *after* it
   exists, but the app needs that URL (Cloud Tasks calls it back). A resource can't reference its own
   output, so this is a **two-phase apply**: apply once with a placeholder, read the real URL from the
   output, set it, apply again. (Same two-step the gcloud runbook has — it's inherent, not a Terraform
   wart.)

Everything else is one `apply`.

## File map

| File | What it declares |
|---|---|
| `versions.tf` | Provider versions, project lookup, (optional) remote state backend |
| `variables.tf` | The inputs you set (project, region, image, billing account, …) |
| `foundation.tf` | APIs, Firestore + TTL, Artifact Registry + cleanup, Cloud Tasks queue, the two secrets |
| `iam.tf` | Runtime service account + its roles + per-secret access |
| `run.tf` | The Cloud Run service (env, scaling, secrets) + public-invoker binding |
| `budget.tf` | Pub/Sub topic, the kill-switch Cloud Function, and the $5 budget wired to it |
| `outputs.tf` | `service_url`, image repo path, runtime SA |

## How it works (Terraform concepts, briefly)

- You declare *desired state* (these `resource` blocks). `terraform plan` diffs desired vs. the
  `.tfstate` record and shows what it will create/change/destroy; `apply` executes it.
- Terraform builds a **dependency graph** from references (e.g. `run.tf` uses
  `google_secret_manager_secret.jwt.secret_id`, so the secret is created first). `depends_on` adds an
  edge where there's no direct reference (e.g. "enable the API before creating the resource").
- **State holds secrets.** The generated JWT/internal tokens live in `terraform.tfstate`, so it's
  gitignored. For a team you'd use the GCS backend (commented in `versions.tf`).

## Deploy, step by step

Prereqs: `terraform` and `gcloud` installed, an existing project, and `gcloud auth application-default
login` done (Terraform uses those credentials).

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # then edit: project_id, region, billing_account

terraform init                                  # downloads the providers
```

**Step 1 — create the registry + foundation** (so there's somewhere to push the image):
```bash
terraform apply -target=google_artifact_registry_repository.poker
```

**Step 2 — build & push the image** (the step Terraform can't do), then set `image` in tfvars:
```bash
gcloud builds submit --tag "$(terraform output -raw image_repo)/poker-server:latest" ../..
# put that tag into terraform.tfvars as `image`
```

**Step 3 — apply everything** (first phase, `SERVICE_URL` is still the placeholder):
```bash
terraform apply
```

**Step 4 — close the URL loop** (second phase):
```bash
terraform output -raw service_url        # copy this
# set it as `service_url` in terraform.tfvars, then:
terraform apply                          # redeploys the service with the correct callback URL
```

That's it — the service is live at `service_url`, scaling 0→3, with durable timers, the TTL policy, and
the budget kill-switch armed.

## Day-2

- **Ship a new build:** rebuild/push the image (Step 2) — if you keep the `:latest` tag, force a new
  revision with `terraform apply -replace=google_cloud_run_v2_service.poker`, or (cleaner) push an
  immutable `:$GIT_SHA` tag and update `var.image`.
- **Tear it all down:** `terraform destroy`. (APIs are left enabled by design.)

## Caveats to watch on `plan`

- **Billing-account admin.** `budget.tf` grants the kill-switch SA `roles/billing.admin` at the billing
  account. The identity running Terraform must itself be able to grant that. If not, comment out
  `google_billing_account_iam_member.killswitch_admin` and grant it once by hand (the function won't be
  able to disable billing until you do). The plain `gcloud` fallback for the whole kill-switch lives in
  `../README.md`.
- **Firestore is one-per-project.** If the `(default)` database already exists, `terraform import` it
  instead of letting Terraform try to create it.
- This config hasn't been `terraform validate`'d in this repo's CI — run `terraform plan` first and read
  it before applying.
