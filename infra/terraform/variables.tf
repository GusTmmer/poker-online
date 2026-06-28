# All the knobs. Set them in terraform.tfvars (copy terraform.tfvars.example).

variable "project_id" {
  description = "GCP project to deploy into (must already exist)."
  type        = string
}

variable "region" {
  description = "Region for Cloud Run, Firestore, Cloud Tasks, Artifact Registry."
  type        = string
  default     = "us-central1"
}

variable "image" {
  description = <<-EOT
    Full container image reference the Cloud Run service runs, e.g.
    us-central1-docker.pkg.dev/PROJECT/poker/poker-server:latest.
    Built and pushed OUTSIDE Terraform (see README "Step 2"); passed in here.
  EOT
  type        = string
}

variable "service_url" {
  description = <<-EOT
    The service's own public https URL, which Cloud Tasks calls back for timer/vote
    expiry. Cloud Run assigns this only AFTER the service exists, so this is a
    two-phase value: leave it as the placeholder for the first apply, then set it to
    `terraform output service_url` and apply again. (See README "Step 4".)
  EOT
  type        = string
  default     = "https://placeholder.invalid"
}

variable "max_instances" {
  description = "Cost ceiling. The app is stateless, so this bounds spend, not correctness."
  type        = number
  default     = 3
}

variable "memory" {
  description = <<-EOT
    Per-instance memory. 512Mi keeps you in the cheapest tier (~100 free instance-hours/mo), but the JVM
    can OOM on cold start under load — bump to "1Gi" if you see OOMKilled in the logs (halves free hours).
  EOT
  type        = string
  default     = "512Mi"
}

variable "billing_account" {
  description = "Billing account id (XXXXXX-XXXXXX-XXXXXX) the budget + kill-switch attach to."
  type        = string
}

variable "budget_amount" {
  description = "Monthly budget in USD; the kill-switch disables billing when actual cost reaches it."
  type        = number
  default     = 5
}
