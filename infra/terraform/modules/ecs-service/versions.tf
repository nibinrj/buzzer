# A module states which providers it needs; the version is chosen by the stack that uses it (envs/dev).
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
  }
}
