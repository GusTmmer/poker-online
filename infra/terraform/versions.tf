# Provider + Terraform version pins, and a lookup of the project's number (the budget filter needs it).
#
# State: this defaults to LOCAL state (a terraform.tfstate file next to these configs). Fine for a
# solo portfolio. For a shared/long-lived setup, uncomment the gcs backend below and create the bucket
# first. State is how Terraform remembers what it created so `plan` can diff desired-vs-actual.
terraform {
  required_version = ">= 1.5"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }

  # backend "gcs" {
  #   bucket = "YOUR-TF-STATE-BUCKET"
  #   prefix = "poker"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

data "google_project" "this" {
  project_id = var.project_id
}
