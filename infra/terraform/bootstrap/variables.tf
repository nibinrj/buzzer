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
