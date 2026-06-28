# ── Enable the APIs everything else needs ────────────────────────────────────
# google_project_service is idempotent; disable_on_destroy=false avoids tearing APIs
# down (and breaking other things) when you `terraform destroy`.
resource "google_project_service" "apis" {
  for_each = toset([
    "run.googleapis.com",
    "firestore.googleapis.com",
    "cloudtasks.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "secretmanager.googleapis.com",
    "cloudfunctions.googleapis.com",
    "cloudbilling.googleapis.com",
    "billingbudgets.googleapis.com",
    "pubsub.googleapis.com",
    "eventarc.googleapis.com",
    "storage.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

# ── Firestore (Native) + the TTL policy that deletes expired tables ──────────
resource "google_firestore_database" "db" {
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"
  depends_on  = [google_project_service.apis]
}

# Tables carry an `expiresAt` Timestamp (set 12h out by the app); this makes Firestore actually delete them.
resource "google_firestore_field" "ttl" {
  database   = google_firestore_database.db.name
  collection = "tables"
  field      = "expiresAt"
  ttl_config {}
}

# ── Artifact Registry repo + cleanup policy (stay under the 0.5 GB free tier) ─
resource "google_artifact_registry_repository" "poker" {
  repository_id = "poker"
  location      = var.region
  format        = "DOCKER"
  depends_on    = [google_project_service.apis]

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 3
    }
  }
}

# ── Cloud Tasks queue for durable turn + vote timers ─────────────────────────
resource "google_cloud_tasks_queue" "timers" {
  name       = "poker-timers"
  location   = var.region
  depends_on = [google_project_service.apis]
}

# ── Secrets (random-generated, so they never live in your repo or tfvars) ────
resource "random_password" "jwt" {
  length  = 32
  special = false
}

resource "random_password" "internal" {
  length  = 32
  special = false
}

resource "google_secret_manager_secret" "jwt" {
  secret_id  = "jwt-secret"
  replication { auto {} }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "jwt" {
  secret      = google_secret_manager_secret.jwt.id
  secret_data = random_password.jwt.result
}

resource "google_secret_manager_secret" "internal" {
  secret_id  = "internal-token"
  replication { auto {} }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "internal" {
  secret      = google_secret_manager_secret.internal.id
  secret_data = random_password.internal.result
}
