output "vpc_id" {
  description = "The VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "The VPC's address range, for security group rules that allow \"anything inside the VPC\"."
  value       = aws_vpc.this.cidr_block
}

output "azs" {
  description = "The Availability Zones used, in order."
  value       = local.azs
}

output "public_subnet_ids" {
  description = "Public subnets, one per AZ in azs order: for the ALB and the ECS tasks."
  value       = [for az in local.azs : aws_subnet.public[az].id]
}

output "private_subnet_ids" {
  description = "Private subnets, one per AZ in azs order: for RDS and ElastiCache. No route to the internet."
  value       = [for az in local.azs : aws_subnet.private[az].id]
}
