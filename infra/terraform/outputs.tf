output "service_url" {
  description = "The Cloud Run URL. After the first apply, copy this into terraform.tfvars as service_url and apply again."
  value       = google_cloud_run_v2_service.poker.uri
}

output "image_repo" {
  description = "Artifact Registry path to push the image to (Step 2)."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.poker.repository_id}"
}

output "runtime_service_account" {
  description = "The SA the service runs as."
  value       = google_service_account.run.email
}
