# The demo environment: created by demo-up (terraform apply), removed by demo-down (terraform destroy).
# Everything long-lived (state bucket, image repositories) is in infra/terraform/bootstrap.
terraform {
  # 1.11: write-only arguments, which keep the generated passwords out of the state (modules/postgres). Also the
  # release where use_lockfile (backend.tf) became GA.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
