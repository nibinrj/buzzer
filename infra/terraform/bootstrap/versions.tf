# The long-lived stack: what must outlive every demo. Applied once by hand, never destroyed by demo-down.
# Its own state stays LOCAL (terraform.tfstate here, gitignored): the bucket that holds every other stack's state is
# created by this stack, so this one can't keep its state in it.
terraform {
  # use_lockfile (S3-native state locking, in envs/dev/backend.tf) arrived in 1.10 and is GA since 1.11.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
