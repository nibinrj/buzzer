output "service_name" {
  description = "The ECS service name."
  value       = aws_ecs_service.this.name
}

output "security_group_id" {
  description = "The tasks' security group: what other resources (the ALB's egress, RDS, ElastiCache) refer to."
  value       = aws_security_group.this.id
}

output "task_definition_arn" {
  description = "The current task definition revision (a deploy registers a new one)."
  value       = aws_ecs_task_definition.this.arn
}

output "log_group_name" {
  description = "CloudWatch log group of the service's containers."
  value       = aws_cloudwatch_log_group.this.name
}

output "execution_role_arn" {
  description = "The execution role (ECR pull, logs, secrets)."
  value       = aws_iam_role.execution.arn
}

output "task_role_arn" {
  description = "The task role (the app's own AWS identity; no permissions)."
  value       = aws_iam_role.task.arn
}
