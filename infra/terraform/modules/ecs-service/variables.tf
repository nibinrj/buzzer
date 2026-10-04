variable "name" {
  type        = string
  description = "Service name, as under services/ (e.g. identity-service). Also the container name."
}

variable "name_prefix" {
  type        = string
  description = "Prefix for AWS resource names (e.g. buzzer-dev), so two environments never collide."
}

variable "cluster_name" {
  type        = string
  description = "The ECS cluster. Pass it from the cluster's capacity-provider resource, so the providers are attached before this service asks for them."
}

variable "image" {
  type        = string
  description = "Full image reference: <registry>/<repository>:<tag>."
}

variable "container_port" {
  type        = number
  description = "The port the service listens on (its server.port). Traffic, the ALB health check and /readyz all use it."
}

variable "cpu" {
  type        = number
  description = "Task CPU units (1024 = 1 vCPU). Fargate allows fixed CPU/memory pairs."
  default     = 512
}

variable "memory" {
  type        = number
  description = "Task memory in MiB. The JVM takes 60% of it as heap (MaxRAMPercentage in the image)."
  default     = 1024
}

variable "cpu_architecture" {
  type        = string
  description = "ARM64 or X86_64. Must match the image's platform."
  default     = "X86_64"

  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "cpu_architecture must be ARM64 or X86_64."
  }
}

variable "desired_count" {
  type        = number
  description = "Tasks to keep running."
}

variable "use_spot" {
  type        = bool
  description = "Fargate Spot (true) or on-demand Fargate (false)."
  default     = true
}

variable "vpc_id" {
  type        = string
  description = "VPC for the service's security group."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Subnets the tasks run in. Public subnets here (no NAT): see assign_public_ip."
}

variable "assign_public_ip" {
  type        = bool
  description = "Give each task a public IP: its way out to ECR, CloudWatch Logs and Secrets Manager without a NAT gateway. It does NOT make the task reachable: the security group decides that."
  default     = true
}

variable "ingress_security_group_ids" {
  type        = map(string)
  description = "Who may connect to container_port: label => security group id (e.g. { alb = sg-… }). Static labels, because for_each keys must be known when planning."
  default     = {}
}

variable "target_group_arn" {
  type        = string
  description = "ALB target group to register tasks in, or null for a service the ALB doesn't reach."
  default     = null
}

variable "health_check_grace_period_seconds" {
  type        = number
  description = "How long ECS ignores failing ALB health checks after a task starts. Spring Boot on 0.5 vCPU needs a while."
  default     = 120
}

variable "environment" {
  type        = map(string)
  description = "Plain environment variables. Nothing secret here: these show in the task definition."
  default     = {}
}

variable "secrets" {
  type        = map(string)
  description = "Secret environment variables: name => Secrets Manager secret ARN, optionally with a JSON key (<arn>:password::). ECS reads them when the task starts; their values never appear in the task definition."
  default     = {}
}

variable "service_connect_namespace" {
  type        = string
  description = "ARN of the Cloud Map namespace for ECS Service Connect, or null to stay out of it. Every service that calls or is called by another must be in it."
  default     = null
}

variable "publish_service_connect" {
  type        = bool
  description = "With a namespace: make this service reachable by others at <name>:<container_port>. false = it only calls others (the gateway, which only the ALB reaches)."
  default     = true
}

variable "command" {
  type        = list(string)
  description = "Container command, overriding the image's CMD. null = the image's own (the Spring Boot services)."
  default     = null
}

variable "read_only_root_filesystem" {
  type        = bool
  description = "Mount the container's root filesystem read-only (/tmp stays writable). false only for a container that writes its data inside the image's filesystem."
  default     = true
}

variable "log_retention_days" {
  type        = number
  description = "Days CloudWatch keeps the logs. 1: a demo's logs are read during the demo (and ingestion, not storage, is what costs)."
  default     = 1
}

variable "stop_timeout" {
  type        = number
  description = "Seconds between SIGTERM and SIGKILL. Covers Spring's graceful shutdown; at most 120 (a Fargate Spot reclaim gives 2 minutes)."
  default     = 30

  validation {
    condition     = var.stop_timeout >= 2 && var.stop_timeout <= 120
    error_message = "stop_timeout must be between 2 and 120 seconds."
  }
}
