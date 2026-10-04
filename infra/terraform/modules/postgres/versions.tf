# A module states which providers it needs; the version is chosen by the stack that uses it (envs/dev).
terraform {
  # Ephemeral resources (1.10) and write-only arguments (1.11).
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
  }
}
