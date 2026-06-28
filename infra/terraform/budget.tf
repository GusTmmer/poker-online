# ── The cost hard-stop: budget → Pub/Sub → Cloud Function that disables billing ──
# A budget alert only NOTIFIES. The function below is what actually caps spend.

resource "google_pubsub_topic" "billing" {
  name       = "billing-alerts"
  depends_on = [google_project_service.apis]
}

# Identity the kill-switch function runs as. It needs billing admin to detach the billing account.
resource "google_service_account" "killswitch" {
  account_id   = "billing-killswitch"
  display_name = "Budget kill-switch"
}

# Billing admin is granted at the BILLING ACCOUNT scope — the Terraform runner must itself have
# billing-account admin for this to apply (otherwise grant it once by hand; see README).
resource "google_billing_account_iam_member" "killswitch_admin" {
  billing_account_id = var.billing_account
  role               = "roles/billing.admin"
  member             = "serviceAccount:${google_service_account.killswitch.email}"
}

# Zip the existing Python source (infra/billing-killswitch) and upload it for the function build.
data "archive_file" "killswitch" {
  type        = "zip"
  source_dir  = "${path.module}/../billing-killswitch"
  output_path = "${path.module}/build/billing-killswitch.zip"
}

resource "google_storage_bucket" "function_source" {
  name                        = "${var.project_id}-fn-source"
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = true
}

resource "google_storage_bucket_object" "killswitch" {
  name   = "billing-killswitch-${data.archive_file.killswitch.output_md5}.zip"
  bucket = google_storage_bucket.function_source.name
  source = data.archive_file.killswitch.output_path
}

resource "google_cloudfunctions2_function" "killswitch" {
  name       = "billing-killswitch"
  location   = var.region
  depends_on = [google_project_service.apis]

  build_config {
    runtime     = "python312"
    entry_point = "stop_billing"
    source {
      storage_source {
        bucket = google_storage_bucket.function_source.name
        object = google_storage_bucket_object.killswitch.name
      }
    }
  }

  service_config {
    available_memory      = "256M"
    service_account_email = google_service_account.killswitch.email
    environment_variables = {
      GCP_PROJECT_ID = var.project_id
    }
  }

  event_trigger {
    trigger_region = var.region
    event_type     = "google.cloud.pubsub.topic.v1.messagePublished"
    pubsub_topic   = google_pubsub_topic.billing.id
    retry_policy   = "RETRY_POLICY_DO_NOT_RETRY"
  }
}

# The $5 budget, wired to the topic. GCP grants the budget service agent publish rights on the topic
# automatically when the budget is created with a pubsub_topic.
resource "google_billing_budget" "budget" {
  billing_account = var.billing_account
  display_name    = "poker-budget"

  budget_filter {
    projects = ["projects/${data.google_project.this.number}"]
  }

  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.budget_amount)
    }
  }

  threshold_rules { threshold_percent = 0.5 }
  threshold_rules { threshold_percent = 0.9 }
  threshold_rules { threshold_percent = 1.0 }

  all_updates_rule {
    pubsub_topic = google_pubsub_topic.billing.id
  }
}
