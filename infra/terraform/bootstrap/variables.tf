variable "region" {
  type        = string
  description = "AWS region for the state bucket and the image repositories. The demo stacks must use the same one."
  default     = "ap-south-1"
}

variable "project" {
  type        = string
  description = "Project name: the prefix of every resource name and the Project tag."
  default     = "buzzer"
}

variable "environment" {
  type        = string
  description = "Env tag. There is one environment (dev); the bootstrap serves it."
  default     = "dev"
}

variable "owner" {
  type        = string
  description = "Owner tag."
  default     = "nibin"
}

variable "services" {
  type        = set(string)
  description = "One ECR repository per service: the directory names under services/, the same names the images use."
  default     = ["gateway", "identity-service", "quiz-service", "scoring-service", "session-service"]
}

variable "images_to_keep" {
  type        = number
  description = "Images kept per repository; older ones expire. Enough to roll back a couple of deploys."
  default     = 3

  validation {
    condition     = var.images_to_keep >= 1
    error_message = "Keep at least one image, or a demo-up has nothing to run."
  }
}

variable "noncurrent_state_days" {
  type        = number
  description = "Days an overwritten state version is kept (S3 versioning), to recover from a bad apply."
  default     = 30
}

variable "github_repository" {
  type        = string
  description = "The GitHub repository (owner/name) whose workflows may assume the deploy role."
  default     = "nibinrj/buzzer"

  validation {
    condition     = can(regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$", var.github_repository))
    error_message = "github_repository must look like owner/name."
  }
}

variable "monthly_budget_usd" {
  type        = string
  description = "Monthly spend (USD) that triggers the budget emails. A few demos a month stay well below 5."
  default     = "5"
}

variable "budget_alert_email" {
  type        = string
  description = "Where the budget emails go. No default: set it in terraform.tfvars (gitignored), so no address is in the repo."

  validation {
    condition     = can(regex("^[^@ ]+@[^@ ]+[.][^@ ]+$", var.budget_alert_email))
    error_message = "budget_alert_email must be an email address."
  }
}
