variable "name" {
  type        = string
  description = "Prefix for the node and everything around it, e.g. buzzer-dev."
}

variable "vpc_id" {
  type        = string
  description = "VPC for the security group."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnets for the subnet group."
}

variable "client_security_group_ids" {
  type        = map(string)
  description = "Who may connect on 6379: label => security group id. Static labels: for_each keys must be known when planning."
}

variable "node_type" {
  type        = string
  description = "Node size. cache.t4g.micro (Graviton, 0.5 GiB) is the cheapest; the demo's live state is a few MB."
  default     = "cache.t4g.micro"
}
