# Credentials come from the usual chain (AWS_PROFILE, SSO, environment), never from this repo.
provider "aws" {
  region = var.region

  # Stack = dev marks everything demo-down must remove. After a destroy, nothing tagged Project=buzzer, Stack=dev
  # should be left (the bootstrap's resources carry Stack=bootstrap).
  default_tags {
    tags = {
      Project   = var.project
      Env       = var.environment
      ManagedBy = "terraform"
      Owner     = var.owner
      Stack     = var.environment
    }
  }
}

locals {
  # Prefix of resource names: buzzer-dev-…
  name = "${var.project}-${var.environment}"
}
