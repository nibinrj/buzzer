resource "aws_ecs_cluster" "this" {
  name = local.name

  # Container Insights bills per metric (cost model): the demo uses free basic ECS metrics and the logs instead.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

# Both Fargate capacity providers attached, so any service may choose Spot or on-demand. The default strategy is for
# tasks started without one; every service in modules/ecs-service sets its own.
resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = var.use_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }
}
