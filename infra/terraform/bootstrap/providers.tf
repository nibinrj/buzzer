# Credentials come from the usual chain (AWS_PROFILE, SSO, environment), never from this repo.
provider "aws" {
  region = var.region

  # On every resource that supports tags. Stack tells the long-lived resources apart from a demo's: demo-down's
  # "is anything left?" check looks for Project=buzzer AND Stack=dev, so it never flags these.
  default_tags {
    tags = {
      Project   = var.project
      Env       = var.environment
      ManagedBy = "terraform"
      Owner     = var.owner
      Stack     = "bootstrap"
    }
  }
}

data "aws_caller_identity" "current" {}
