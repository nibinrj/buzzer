output "host" {
  description = "The node's endpoint inside the VPC (TLS on port 6379)."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "The Valkey port."
  value       = aws_elasticache_replication_group.this.port
}

output "auth_token_secret_arn" {
  description = "Secret ARN of the AUTH token: the services' REDIS_PASSWORD."
  value       = aws_secretsmanager_secret.auth.arn
}

output "security_group_id" {
  description = "The node's security group."
  value       = aws_security_group.this.id
}
