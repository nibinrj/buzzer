# One Spring Boot service on ECS Fargate: its logs, security group, task definition and service.
# The images come from the bootstrap's ECR repositories (one Dockerfile, at the repo root, for all five).

data "aws_region" "current" {}

locals {
  full_name = "${var.name_prefix}-${var.name}"
  port_name = "main"
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

# In: only the given security groups (the ALB, or the services that call this one), only on the service port. A
# public IP alone opens nothing.
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
# endpoints (HTTPS), and RDS, ElastiCache, Kafka and the other services inside the VPC. Terraform removes AWS's default
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

      # Named, because Service Connect refers to a port by name (port_name below).
      portMappings = [
        {
          name          = local.port_name
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # null = the image's own ENTRYPOINT/CMD (the five services). Redpanda takes its start flags here.
      command = var.command

      # Sorted by name (map iteration order), so the JSON is stable and plans show no false changes.
      environment = [for name, value in var.environment : { name = name, value = value }]
      secrets     = [for name, arn in var.secrets : { name = name, valueFrom = arn }]

      # The services' image runs as uid 10001 (Dockerfile) and can't modify its own files either. Only a container
      # that must write outside /tmp (Redpanda's data directory) turns this off.
      readonlyRootFilesystem = var.read_only_root_filesystem
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

  # ECS Service Connect: a proxy (Envoy) that ECS adds to every task in the namespace. Clients call another service
  # by name, http://quiz-service:8082, and their proxy picks a healthy task of it; no load balancer between services.
  # With publish_service_connect, this service is one of those names; without it, it only calls the others.
  # No appProtocol on the port mapping: the proxy forwards plain TCP, which carries HTTP, WebSocket upgrades and
  # the Kafka protocol alike.
  dynamic "service_connect_configuration" {
    for_each = var.service_connect_namespace == null ? [] : [var.service_connect_namespace]

    content {
      enabled   = true
      namespace = service_connect_configuration.value

      dynamic "service" {
        for_each = var.publish_service_connect ? [var.name] : []

        content {
          port_name      = local.port_name
          discovery_name = service.value

          client_alias {
            dns_name = service.value
            port     = var.container_port
          }
        }
      }
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
