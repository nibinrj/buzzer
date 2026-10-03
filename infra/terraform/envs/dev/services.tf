# The services on ECS. Phase 6: identity-service only. Phase 7 adds the other four, their data stores and secrets.

# Created by the bootstrap; read here, so a missing bootstrap fails the plan with a clear "repository not found".
data "aws_ecr_repository" "identity" {
  name = "${var.project}/identity-service"
}

module "identity" {
  source = "../../modules/ecs-service"

  name        = "identity-service"
  name_prefix = local.name

  # From the capacity-provider resource, not the cluster: the service is created only after FARGATE_SPOT is attached.
  cluster_name = aws_ecs_cluster_capacity_providers.this.cluster_name

  image            = "${data.aws_ecr_repository.identity.repository_url}:${var.image_tag}"
  container_port   = 8081
  cpu_architecture = var.cpu_architecture
  use_spot         = var.use_spot

  # 0 until Phase 7: identity-service runs Flyway against Postgres at startup and exits without it.
  desired_count = var.identity_desired_count

  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.public_subnet_ids

  ingress_security_group_ids = { alb = aws_security_group.alb.id }

  # Through the listener, not the target group directly: ECS refuses a target group that no listener uses yet, and
  # this reference makes Terraform create the listener first.
  target_group_arn = aws_lb_listener.http.default_action[0].target_group_arn

  # Placeholders until Phase 7.1 (RDS + Secrets Manager):
  #   IDENTITY_DB_URL                            jdbc:postgresql://<RDS endpoint>:5432/identity_db
  #   IDENTITY_JWT_PRIVATE/PUBLIC_KEY_LOCATION   the signing keys. Fargate has no secret volumes: 7.1 decides how
  #                                              the PEM files reach the container.
  environment = {}

  #   IDENTITY_DB_PASSWORD                       Secrets Manager ARN
  secrets = {}
}
