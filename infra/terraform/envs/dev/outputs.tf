output "alb_url" {
  description = "The demo's address: the test client's Base URL. Everything goes through the gateway."
  value       = "http://${aws_lb.this.dns_name}"
}

output "region" {
  description = "The region, for aws commands (tasks.ps1 demo-up)."
  value       = var.region
}

output "cluster_name" {
  description = "The ECS cluster, for aws ecs commands and the console."
  value       = aws_ecs_cluster.this.name
}

output "service_names" {
  description = "Every ECS service in the cluster, for aws ecs wait services-stable."
  value       = concat([for service in module.service : service.service_name], [for broker in module.redpanda : broker.service_name])
}

output "db_init" {
  description = "How to run the one-off database setup task (tasks.ps1 demo-up does): task definition, subnets, security group."
  value = {
    task_definition = aws_ecs_task_definition.db_init.arn
    subnets         = module.network.public_subnet_ids
    security_group  = aws_security_group.db_init.id
    log_group       = aws_cloudwatch_log_group.db_init.name
  }
}

output "log_groups" {
  description = "CloudWatch log group per service (Logs Insights: one stream per task, named after the task id)."
  value = merge(
    { for name, service in module.service : name => service.log_group_name },
    { for name, broker in module.redpanda : name => broker.log_group_name },
  )
}

output "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers the services use (kafka_mode)."
  value       = local.kafka_environment.KAFKA_BOOTSTRAP_SERVERS
}

output "postgres_address" {
  description = "RDS host name (private: reachable only from tasks in the VPC)."
  value       = module.postgres.address
}

output "valkey_host" {
  description = "ElastiCache Valkey endpoint (private, TLS)."
  value       = module.valkey.host
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
  description = "Private subnets: RDS, ElastiCache and MSK."
  value       = module.network.private_subnet_ids
}
