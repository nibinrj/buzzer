variable "region" {
  type        = string
  description = "AWS region. Must match the bootstrap's (its state bucket and image repositories)."
  default     = "ap-south-1"
}

variable "project" {
  type        = string
  description = "Project name: the prefix of every resource name and the Project tag."
  default     = "buzzer"
}

variable "environment" {
  type        = string
  description = "Environment name: part of every resource name, and the Env and Stack tags."
  default     = "dev"
}

variable "owner" {
  type        = string
  description = "Owner tag."
  default     = "nibin"
}

variable "vpc_cidr" {
  type        = string
  description = "The VPC's address range (a /16; subnets are /24s inside it)."
  default     = "10.0.0.0/16"
}

variable "az_count" {
  type        = number
  description = "Availability Zones to spread over (2 is the minimum an ALB accepts)."
  default     = 2
}

variable "use_spot" {
  type        = bool
  description = "Run tasks on Fargate Spot (spare capacity, much cheaper, can be reclaimed with a 2-minute warning). false = on-demand Fargate, e.g. for a recorded demo."
  default     = true
}

variable "cpu_architecture" {
  type        = string
  description = "Task CPU architecture. ARM64 (Graviton) is cheaper and supported on Fargate Spot (6.1); the images must be built for it (tasks.ps1 push)."
  default     = "ARM64"

  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "cpu_architecture must be ARM64 or X86_64."
  }
}

variable "image_tag" {
  type        = string
  description = "Image tag to deploy: the short git commit that tasks.ps1 push built and pushed. No default: every apply names exactly what it runs."

  validation {
    condition     = can(regex("^[0-9a-f]{7,40}$", var.image_tag))
    error_message = "image_tag must be a git commit id (7-40 hex characters), as tasks.ps1 push tags the images."
  }
}

variable "identity_desired_count" {
  type        = number
  description = "Running identity-service tasks. 0 until its database exists (Phase 7): without Postgres it can't start."
  default     = 0
}
