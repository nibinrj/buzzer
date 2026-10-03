output "alb_url" {
  description = "The demo's address. Phase 6: http://<this>/readyz reaches identity-service (once it has a database)."
  value       = "http://${aws_lb.this.dns_name}"
}

output "cluster_name" {
  description = "The ECS cluster, for aws ecs commands and the console."
  value       = aws_ecs_cluster.this.name
}

output "vpc_id" {
  description = "The demo VPC."
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnets: ALB and ECS tasks."
  value       = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnets: RDS and ElastiCache (Phase 7)."
  value       = module.network.private_subnet_ids
}

output "identity_log_group" {
  description = "CloudWatch log group of identity-service."
  value       = module.identity.log_group_name
}
