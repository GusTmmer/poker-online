# The identity the Cloud Run service runs as (≈ an AWS IAM role the workload assumes),
# granted exactly what it needs: read/write Firestore, enqueue Cloud Tasks, read the two secrets.
resource "google_service_account" "run" {
  account_id   = "poker-run"
  display_name = "poker Cloud Run runtime"
}

resource "google_project_iam_member" "run_roles" {
  for_each = toset([
    "roles/datastore.user",     # Firestore reads/writes
    "roles/cloudtasks.enqueuer", # create timer/vote tasks
  ])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.run.email}"
}

# Let the runtime SA read each secret it mounts as an env var (scoped per-secret, not project-wide).
resource "google_secret_manager_secret_iam_member" "jwt_access" {
  secret_id = google_secret_manager_secret.jwt.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.run.email}"
}

resource "google_secret_manager_secret_iam_member" "internal_access" {
  secret_id = google_secret_manager_secret.internal.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.run.email}"
}

# ── Continuous deployment (cloudbuild.yaml) ────────────────────────────────────
# The `build-poker-server` trigger runs as poker-build (created by hand; see infra/README.md). Besides
# pushing images, deploying needs to update the Cloud Run service and to act as its runtime identity.
data "google_service_account" "build" {
  account_id = "poker-build"
}

resource "google_project_iam_member" "build_run_developer" {
  project = var.project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${data.google_service_account.build.email}"
}

resource "google_service_account_iam_member" "build_acts_as_run" {
  service_account_id = google_service_account.run.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${data.google_service_account.build.email}"
}
