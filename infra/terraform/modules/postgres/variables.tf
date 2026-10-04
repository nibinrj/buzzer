variable "name" {
  type        = string
  description = "Prefix for the instance and everything around it, e.g. buzzer-dev."
}

variable "vpc_id" {
  type        = string
  description = "VPC for the security group."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnets in at least two AZs (an RDS subnet group requires two, even for a single-AZ instance)."
}

variable "client_security_group_ids" {
  type        = map(string)
  description = "Who may connect on 5432: label => security group id. Static labels: for_each keys must be known when planning."
}

variable "databases" {
  type        = set(string)
  description = "One database role (and password secret) per service: the service names whose *_DB_PASSWORD this module provides."
}

variable "engine_version" {
  type        = string
  description = "PostgreSQL major version. RDS picks the newest minor of it, as the local postgres:16 image does."
  default     = "16"
}

variable "instance_class" {
  type        = string
  description = "Instance size. db.t4g.micro (Graviton, 2 vCPU burstable, 1 GiB) is the cheapest RDS offers."
  default     = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  type        = number
  description = "gp3 storage in GB. 20 is the gp3 minimum for RDS PostgreSQL."
  default     = 20
}

variable "master_username" {
  type        = string
  description = "The administrator role. Used only by the one-off setup task that creates the service roles and databases."
  default     = "buzzer_admin"
}
