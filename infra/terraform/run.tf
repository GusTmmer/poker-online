# The Cloud Run service — the BE itself. This is the declarative twin of infra/cloudrun-service.yaml.
resource "google_cloud_run_v2_service" "poker" {
  name                = "poker-server"
  location            = var.region
  deletion_protection = false
  ingress             = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.run.email

    scaling {
      min_instance_count = 0                 # scale-to-zero: $0 at idle (never raise this)
      max_instance_count = var.max_instances # cost ceiling (stateless app — safe to scale out)
    }

    max_instance_request_concurrency = 250    # pack many WebSockets per instance → fewer instances
    timeout                          = "3600s" # max WS lifetime before Cloud Run drops the request

    containers {
      image = var.image

      ports {
        container_port = 8080
      }

      resources {
        limits            = { cpu = "1", memory = var.memory }
        cpu_idle          = true # request-based billing: CPU only while handling a request
        startup_cpu_boost = true
      }

      env {
        name  = "GCP_PROJECT_ID"
        value = var.project_id
      }
      env {
        name  = "SERVICE_URL" # two-phase: placeholder on first apply, real URL on the second
        value = var.service_url
      }
      env {
        name  = "CLOUD_TASKS_LOCATION"
        value = var.region
      }
      env {
        name  = "CLOUD_TASKS_QUEUE"
        value = google_cloud_tasks_queue.timers.name
      }
      env {
        name  = "ALLOWED_ORIGIN"
        value = "*" # SPA is served same-origin by this service, so CORS is moot
      }
      env {
        name = "JWT_SECRET"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.jwt.secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "INTERNAL_TOKEN"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.internal.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  depends_on = [
    google_secret_manager_secret_version.jwt,
    google_secret_manager_secret_version.internal,
    google_secret_manager_secret_iam_member.jwt_access,
    google_secret_manager_secret_iam_member.internal_access,
  ]
}

# Make the service public. Cloud Tasks calls /internal/* over the public URL with the shared-secret
# header (so no per-request IAM/OIDC is needed); players hit the public REST + WS endpoints.
resource "google_cloud_run_v2_service_iam_member" "public" {
  name     = google_cloud_run_v2_service.poker.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}
