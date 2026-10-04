output "state_bucket" {
  description = "The state bucket. envs/dev gets it at init time: -backend-config=\"bucket=<this>\"."
  value       = aws_s3_bucket.state.id
}

output "region" {
  description = "The region everything lives in."
  value       = var.region
}

output "ecr_registry" {
  description = "The registry host, for docker login and image names (<registry>/buzzer/<service>:<tag>)."
  value       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "ecr_repository_urls" {
  description = "Repository URL per service."
  value       = { for service, repository in aws_ecr_repository.service : service => repository.repository_url }
}

output "github_deploy_role_arn" {
  description = "The role deploy.yml assumes through GitHub OIDC. Store it as the repository variable AWS_DEPLOY_ROLE_ARN (a variable, not a secret: an ARN grants nothing by itself)."
  value       = aws_iam_role.deploy.arn
}
