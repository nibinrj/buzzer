# The demo environment: created by demo-up (terraform apply), removed by demo-down (terraform destroy).
# Everything long-lived (state bucket, image repositories) is in infra/terraform/bootstrap.
terraform {
  # use_lockfile (backend.tf) arrived in 1.10 and is GA since 1.11.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
