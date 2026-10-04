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
  description = "Task CPU architecture. ARM64 (Graviton) is cheaper and supported on Fargate Spot (ADR-006); the images must be built for it (tasks.ps1 push)."
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

variable "run_services" {
  type        = bool
  description = "false = create everything with the five services at 0 tasks. demo-up applies false first, runs the one-off database setup task, then applies true: the services' Flyway needs their roles and databases to exist."
  default     = true
}

variable "session_min_count" {
  type        = number
  description = "session-service tasks at rest. 2: players are spread over two tasks, so every game shows the Redis broadcast relay at work."
  default     = 2
}

variable "session_max_count" {
  type        = number
  description = "Most session-service tasks autoscaling may run (target: 60% average CPU)."
  default     = 3

  validation {
    condition     = var.session_max_count >= var.session_min_count
    error_message = "session_max_count must be at least session_min_count."
  }
}

variable "kafka_mode" {
  type        = string
  description = "redpanda = one Redpanda task on ECS (cheap, minutes to create, data dies with the task). msk = Amazon MSK, 2 brokers (about 25 minutes to create, billed per broker-hour)."
  default     = "redpanda"

  validation {
    condition     = contains(["redpanda", "msk"], var.kafka_mode)
    error_message = "kafka_mode must be redpanda or msk."
  }
}

variable "redpanda_image" {
  type        = string
  description = "Redpanda image: the same version docker-compose.yml runs locally. Multi-architecture (arm64 and amd64)."
  default     = "docker.redpanda.com/redpandadata/redpanda:v26.2.3"
}

variable "msk_kafka_version" {
  type        = string
  description = "Apache Kafka version for MSK (kafka_mode = msk). Must be one MSK offers: aws kafka list-kafka-versions."
  default     = "3.7.x"
}

variable "jwt_private_key_file" {
  type        = string
  description = "identity-service's PEM private key (PKCS#8). null = .secrets/jwt-private.pem at the repo root, where tasks.ps1 keys writes it. Its content goes to Secrets Manager only, never to the state."
  default     = null
}

variable "jwt_public_key_file" {
  type        = string
  description = "identity-service's PEM public key. null = .secrets/jwt-public.pem at the repo root."
  default     = null
}

variable "postgres_client_image" {
  type        = string
  description = "Image of the one-off database setup task: the official postgres:16 image (it has psql) from the ECR Public mirror of Docker's library, so Fargate pulls it without Docker Hub's rate limits."
  default     = "public.ecr.aws/docker/library/postgres:16"
}

variable "load_test" {
  type        = bool
  description = "Raise the gateway's rate limits for a k6 run: every virtual user comes from the one machine running k6, i.e. one client IP. Same values as tools/load/run-game.ps1 uses locally. Never for a demo with real players."
  default     = false
}
