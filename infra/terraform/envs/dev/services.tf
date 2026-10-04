# The five services on ECS. Only the gateway is behind the ALB; the services call each other by name through ECS
# Service Connect (http://quiz-service:8082), never through the ALB or the internet.
#
# Configuration is environment variables only, the same names docker-compose and kind set (each service's
# application.yml reads them), so the images run unchanged and there is no AWS-specific Spring profile.

# Created by the bootstrap; read here, so a missing bootstrap fails the plan with a clear "repository not found".
data "aws_ecr_repository" "service" {
  for_each = local.services

  name = "${var.project}/${each.key}"
}

# The names Service Connect serves (quiz-service, redpanda, …). An HTTP namespace: names exist only for tasks in
# the namespace, through their proxy, never in the VPC's DNS.
resource "aws_service_discovery_http_namespace" "this" {
  name        = local.name
  description = "Service Connect names of ${local.name}"
}

locals {
  # Static facts per service: for_each keys and values that are known before anything is created.
  services = {
    "gateway"          = { port = 8080, desired_count = 1 }
    "identity-service" = { port = 8081, desired_count = 1 }
    "quiz-service"     = { port = 8082, desired_count = 1 }
    "session-service"  = { port = 8083, desired_count = var.session_min_count }
    "scoring-service"  = { port = 8084, desired_count = 1 }
  }

  # Who calls whom: one security group rule per pair, on the callee's port. JWKS: every service that checks tokens
  # fetches identity-service's public keys. /internal: session-service reads quiz snapshots (the gateway never
  # forwards /internal/**, so this is the only caller). A security group can't see paths, only the port.
  calls = {
    "gateway-to-identity" = { from = "gateway", to = "identity-service" }
    "gateway-to-quiz"     = { from = "gateway", to = "quiz-service" }
    "gateway-to-session"  = { from = "gateway", to = "session-service" }
    "gateway-to-scoring"  = { from = "gateway", to = "scoring-service" }
    "quiz-to-identity"    = { from = "quiz-service", to = "identity-service" }
    "session-to-identity" = { from = "session-service", to = "identity-service" }
    "scoring-to-identity" = { from = "scoring-service", to = "identity-service" }
    "session-to-quiz"     = { from = "session-service", to = "quiz-service" }
  }

  jwt_private_key_file = coalesce(var.jwt_private_key_file, "${path.root}/../../../../.secrets/jwt-private.pem")
  jwt_public_key_file  = coalesce(var.jwt_public_key_file, "${path.root}/../../../../.secrets/jwt-public.pem")

  database_url = "jdbc:postgresql://${module.postgres.address}:${module.postgres.port}"

  redis_environment = {
    REDIS_HOST = module.valkey.host
    REDIS_PORT = tostring(module.valkey.port)
    # ElastiCache has TLS on (modules/valkey); Lettuce trusts its certificate through the JDK's default trust store.
    SPRING_DATA_REDIS_SSL_ENABLED = "true"
  }

  # load_test = true: a k6 machine is one client IP, so the per-IP buckets would throttle the whole test.
  load_test_rate_limits = {
    GATEWAY_RATE_LIMIT_PER_IP_REPLENISH_RATE   = "1000"
    GATEWAY_RATE_LIMIT_PER_IP_BURST_CAPACITY   = "2000"
    GATEWAY_RATE_LIMIT_PER_USER_REPLENISH_RATE = "1000"
    GATEWAY_RATE_LIMIT_PER_USER_BURST_CAPACITY = "2000"
  }

  jwks_environment = {
    IDENTITY_JWKS_URI = "http://identity-service:8081/.well-known/jwks.json"
  }

  # Per service: the plain environment variables (visible in the task definition: nothing secret)...
  environment = {
    "gateway" = merge(local.redis_environment, local.jwks_environment, {
      IDENTITY_URI = "http://identity-service:8081"
      QUIZ_URI     = "http://quiz-service:8082"
      SESSION_URI  = "http://session-service:8083"
      SCORING_URI  = "http://scoring-service:8084"
      # The ALB appends the client's address to X-Forwarded-For: exactly one proxy to trust.
      GATEWAY_CLIENTIP_TRUSTEDPROXIES = "1"
    }, var.load_test ? local.load_test_rate_limits : {})
    "identity-service" = {
      # sslmode=require: RDS for PostgreSQL 16 accepts only encrypted connections.
      IDENTITY_DB_URL = "${local.database_url}/identity_db?sslmode=require"
      # Spring Boot's base64: resource prefix: the PEM file's bytes, base64-encoded, instead of a file path.
      # Fargate has no secret volumes to mount a key file from. A public key is public: a plain variable.
      IDENTITY_JWT_PUBLIC_KEY_LOCATION = "base64:${filebase64(local.jwt_public_key_file)}"
    }
    "quiz-service" = merge(local.jwks_environment, {
      QUIZ_DB_URL = "${local.database_url}/quiz_db?sslmode=require"
    })
    "session-service" = merge(local.redis_environment, local.jwks_environment, local.kafka_environment, {
      SESSION_DB_URL   = "${local.database_url}/session_db?sslmode=require"
      QUIZ_SERVICE_URI = "http://quiz-service:8082"
      # One line per answer (SubmitAnswer, with sessionId and playerId). Each task logs to its own stream, so Logs
      # Insights can show one game's answers arriving on both tasks (docs/performance.md, AWS). A package, not the
      # class: Spring reads logger names from environment variables in lower case.
      LOGGING_LEVEL_DEV_NIBIN_BUZZER_SESSION_APPLICATION = "DEBUG"
    })
    "scoring-service" = merge(local.redis_environment, local.jwks_environment, local.kafka_environment, {
      SCORING_DB_URL = "${local.database_url}/scoring_db?sslmode=require"
    })
  }

  # ...and the secret ones: environment variable => Secrets Manager reference. Each service gets only its own.
  secrets = {
    "gateway" = {
      REDIS_PASSWORD = module.valkey.auth_token_secret_arn
    }
    "identity-service" = {
      IDENTITY_DB_PASSWORD              = module.postgres.app_password_secret_arns["identity"]
      IDENTITY_JWT_PRIVATE_KEY_LOCATION = aws_secretsmanager_secret.jwt_private_key.arn
    }
    "quiz-service" = {
      QUIZ_DB_PASSWORD = module.postgres.app_password_secret_arns["quiz"]
    }
    "session-service" = {
      SESSION_DB_PASSWORD = module.postgres.app_password_secret_arns["session"]
      REDIS_PASSWORD      = module.valkey.auth_token_secret_arn
    }
    "scoring-service" = {
      SCORING_DB_PASSWORD = module.postgres.app_password_secret_arns["scoring"]
      REDIS_PASSWORD      = module.valkey.auth_token_secret_arn
    }
  }
}

# identity-service's signing key, as a base64: location (above). Written once per demo through a write-only argument:
# the key reaches Secrets Manager but never the Terraform state or a plan file.
resource "aws_secretsmanager_secret" "jwt_private_key" {
  name        = "${local.name}/identity-jwt-private-key"
  description = "identity-service's JWT signing key (base64: resource location)"

  # Deleted at once on demo-down, so the next demo-up can reuse the name.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "jwt_private_key" {
  secret_id                = aws_secretsmanager_secret.jwt_private_key.id
  secret_string_wo         = "base64:${filebase64(local.jwt_private_key_file)}"
  secret_string_wo_version = 1
}

module "service" {
  source   = "../../modules/ecs-service"
  for_each = local.services

  name        = each.key
  name_prefix = local.name

  # From the capacity-provider resource, not the cluster: a service is created only after FARGATE_SPOT is attached.
  cluster_name = aws_ecs_cluster_capacity_providers.this.cluster_name

  image            = "${data.aws_ecr_repository.service[each.key].repository_url}:${var.image_tag}"
  container_port   = each.value.port
  cpu_architecture = var.cpu_architecture
  use_spot         = var.use_spot

  # 0 during demo-up's first apply, while the database roles don't exist yet (variables.tf, run_services).
  desired_count = var.run_services ? each.value.desired_count : 0

  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.public_subnet_ids

  # Only the gateway is reachable from the ALB. Service-to-service rules are below (local.calls).
  ingress_security_group_ids = each.key == "gateway" ? { alb = aws_security_group.alb.id } : {}

  # Through the listener, not the target group directly: ECS refuses a target group that no listener uses yet, and
  # this reference makes Terraform create the listener first.
  target_group_arn = each.key == "gateway" ? aws_lb_listener.http.default_action[0].target_group_arn : null

  service_connect_namespace = aws_service_discovery_http_namespace.this.arn
  # Nobody calls the gateway by name: only the ALB, by task IP.
  publish_service_connect = each.key != "gateway"

  environment = local.environment[each.key]
  secrets     = local.secrets[each.key]
}

moved {
  from = module.identity
  to   = module.service["identity-service"]
}

# Outside the module: a rule refers to two instances of module.service, which an argument of module.service itself
# can't (it would depend on itself).
resource "aws_vpc_security_group_ingress_rule" "service_call" {
  for_each = local.calls

  security_group_id            = module.service[each.value.to].security_group_id
  referenced_security_group_id = module.service[each.value.from].security_group_id
  ip_protocol                  = "tcp"
  from_port                    = local.services[each.value.to].port
  to_port                      = local.services[each.value.to].port
  description                  = "From ${each.value.from}"
}

# --- session-service autoscaling -----------------------------------------------------------------------------------
#
# Target tracking on average CPU: Application Auto Scaling adds a task when the service runs above 60% and removes
# one when it's well below, within min and max. ECS's basic CPU metric is free; no Container Insights needed.
# Only while the services run: a minimum of 2 would otherwise start tasks during demo-up's first apply.
# An apply resets desired_count to session_min_count, which is inside the range, so the two never fight for long.

resource "aws_appautoscaling_target" "session" {
  for_each = var.run_services ? toset(["session-service"]) : toset([])

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${module.service[each.key].service_name}"
  min_capacity       = var.session_min_count
  max_capacity       = var.session_max_count
}

resource "aws_appautoscaling_policy" "session_cpu" {
  for_each = aws_appautoscaling_target.session

  name               = "${local.name}-session-cpu-60"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = each.value.service_namespace
  scalable_dimension = each.value.scalable_dimension
  resource_id        = each.value.resource_id

  target_tracking_scaling_policy_configuration {
    target_value = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    # Seconds between scaling steps: out quickly (a game is short), in slowly (players stay connected to a task).
    scale_out_cooldown = 60
    scale_in_cooldown  = 300
  }
}
