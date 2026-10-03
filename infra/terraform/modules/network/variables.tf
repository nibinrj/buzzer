variable "name" {
  type        = string
  description = "Prefix for the Name tag of everything here, e.g. buzzer-dev."
}

variable "cidr" {
  type        = string
  description = "The VPC's address range. A /16 or larger: every subnet is a /24 carved out of it."

  validation {
    # can() turns an error into false. Terraform evaluates both sides of &&, so each side must be safe on its own.
    condition     = can(cidrhost(var.cidr, 0)) && can(regex("/([0-9]|1[0-6])$", var.cidr))
    error_message = "cidr must be a valid IPv4 CIDR of /16 or larger, e.g. 10.0.0.0/16."
  }
}

variable "az_count" {
  type        = number
  description = "Availability Zones to spread over. At least 2: an ALB and the RDS/ElastiCache subnet groups require two."
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 6
    error_message = "az_count must be between 2 and 6."
  }
}
