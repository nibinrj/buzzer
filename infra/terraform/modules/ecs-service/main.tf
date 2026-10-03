# One Spring Boot service on ECS Fargate: its logs, security group, task definition and service.
# The images come from the bootstrap's ECR repositories (one Dockerfile for all five, K.2).

data "aws_region" "current" {}

locals {
  full_name = "${var.name_prefix}-${var.name}"
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name_prefix}/${var.name}"
  retention_in_days = var.log_retention_days
}

# --- Network access ---------------------------------------------------------------------------------------------------

resource "aws_security_group" "this" {
  name        = local.full_name
  description = "${var.name} tasks"
  vpc_id      = var.vpc_id

  tags = {
    Name = local.full_name
  }
}

# In: only the given security groups (the ALB for now), only on the service port. A public IP alone opens nothing.
resource "aws_vpc_security_group_ingress_rule" "from" {
  for_each = var.ingress_security_group_ids

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = var.container_port
  to_port                      = var.container_port
  description                  = "From ${each.key}"
}

# Out: anywhere. With no NAT gateway, the task reaches ECR, CloudWatch Logs and Secrets Manager on their public
# endpoints (HTTPS), and later RDS, ElastiCache and Kafka inside the VPC. Terraform removes AWS's default
# allow-all egress rule from a new security group, so it's written out here.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Out to AWS APIs (public endpoints, no NAT) and the data stores"
}

# --- Task definition ----------------------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "this" {
  family                   = local.full_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # the only mode Fargate has: each task gets its own network interface and IP
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  # Task-local scratch space for /tmp (Tomcat's work directory): the root filesystem is read-only, as on kind.
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name      = var.name
      image     = var.image
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # Sorted by name (map iteration order), so the JSON is stable and plans show no false changes.
      environment = [for name, value in var.environment : { name = name, value = value }]
      secrets     = [for name, arn in var.secrets : { name = name, valueFrom = arn }]

      # The image already runs as uid 10001 (Dockerfile). The app can't modify its own files either.
      readonlyRootFilesystem = true
      mountPoints = [
        {
          sourceVolume  = "tmp"
          containerPath = "/tmp"
          readOnly      = false
        }
      ]

      # ECS has no preStop hook: SIGTERM starts Spring's graceful shutdown, stopTimeout bounds it (ADR-009).
      stopTimeout = var.stop_timeout

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = data.aws_region.current.region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])
}

# --- Service ------------------------------------------------------------------------------------------------------------------

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_name
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count

  # Spot or on-demand, per service. No launch_type: a capacity provider strategy replaces it.
  capacity_provider_strategy {
    capacity_provider = var.use_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = var.assign_public_ip
  }

  # Registered in the ALB's target group (by task IP), when the ALB reaches this service.
  dynamic "load_balancer" {
    for_each = var.target_group_arn == null ? [] : [var.target_group_arn]

    content {
      target_group_arn = load_balancer.value
      container_name   = var.name
      container_port   = var.container_port
    }
  }

  # Only meaningful with a load balancer; AWS rejects it otherwise.
  health_check_grace_period_seconds = var.target_group_arn == null ? null : var.health_check_grace_period_seconds

  # A deployment whose tasks keep failing is stopped and rolled back to the last working task definition, instead
  # of replacing tasks forever.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Tasks carry the service's tags (Project, Stack…), so demo-down's leftover check sees them too.
  propagate_tags          = "SERVICE"
  enable_ecs_managed_tags = true

  # apply returns once ECS accepted the service; it doesn't wait for tasks to become healthy.
  wait_for_steady_state = false
}
