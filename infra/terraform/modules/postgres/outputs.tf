output "address" {
  description = "The instance's host name inside the VPC."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "The Postgres port."
  value       = aws_db_instance.this.port
}

output "master_username" {
  description = "The administrator role."
  value       = aws_db_instance.this.username
}

output "master_password_secret" {
  description = "ECS secret reference to the master password: the JSON key \"password\" of the secret RDS manages."
  value       = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::"
}

output "app_password_secret_arns" {
  description = "Secret ARN of each service's database password, keyed by service."
  value       = { for service, secret in aws_secretsmanager_secret.app : service => secret.arn }
}

output "security_group_id" {
  description = "The database's security group."
  value       = aws_security_group.this.id
}
