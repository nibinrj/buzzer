# The data stores, in the private subnets. Each accepts connections only from the security groups of the services
# that use it (and Postgres also from the one-off setup task below).

module "postgres" {
  source = "../../modules/postgres"

  name       = local.name
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.private_subnet_ids

  # The services with a database of their own. The gateway has none.
  databases = toset(["identity", "quiz", "session", "scoring"])

  client_security_group_ids = {
    identity = module.service["identity-service"].security_group_id
    quiz     = module.service["quiz-service"].security_group_id
    session  = module.service["session-service"].security_group_id
    scoring  = module.service["scoring-service"].security_group_id
    db-init  = aws_security_group.db_init.id
  }
}

module "valkey" {
  source = "../../modules/valkey"

  name       = local.name
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.private_subnet_ids

  # The gateway's rate limiter, session-service's live state and relay, scoring-service's leaderboards.
  client_security_group_ids = {
    gateway = module.service["gateway"].security_group_id
    session = module.service["session-service"].security_group_id
    scoring = module.service["scoring-service"].security_group_id
  }
}

# --- One-off database setup ---------------------------------------------------------------------------------------
#
# Creates the four service roles and databases (sql/db-init.sql), as docker-compose's init script does locally.
# A task definition that demo-up runs once (aws ecs run-task), not documented psql steps: RDS is in a private subnet
# with no route from the internet, so nothing outside the VPC can reach it, and a task inside it needs no bastion
# host, no SSH and no port forwarding. It reads every password from Secrets Manager like the services do, and the
# script is idempotent, so running it twice changes nothing.

resource "aws_cloudwatch_log_group" "db_init" {
  name              = "/ecs/${local.name}/db-init"
  retention_in_days = 1
}

resource "aws_security_group" "db_init" {
  name        = "${local.name}-db-init"
  description = "One-off database setup task: no inbound traffic"
  vpc_id      = module.network.vpc_id

  tags = {
    Name = "${local.name}-db-init"
  }
}

# Out: Postgres inside the VPC, plus the image pull (ECR Public) and Secrets Manager over its public IP.
resource "aws_vpc_security_group_egress_rule" "db_init_all" {
  security_group_id = aws_security_group.db_init.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Out to Postgres, the image registry and Secrets Manager"
}

data "aws_iam_policy_document" "db_init_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

# Execution role only (pull, logs, the secrets below). No task role: psql calls no AWS API.
resource "aws_iam_role" "db_init_execution" {
  name               = "${local.name}-db-init-execution"
  assume_role_policy = data.aws_iam_policy_document.db_init_assume.json
}

resource "aws_iam_role_policy_attachment" "db_init_execution" {
  role       = aws_iam_role.db_init_execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

locals {
  # Environment variable => secret reference. The master password is one JSON key of the secret RDS manages.
  db_init_secrets = merge(
    { PGPASSWORD = module.postgres.master_password_secret },
    { for service, arn in module.postgres.app_password_secret_arns : "${upper(service)}_DB_PASSWORD" => arn },
  )
}

data "aws_iam_policy_document" "db_init_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    # The secret's own ARN, without the ":password::" key suffix ECS uses (same as modules/ecs-service).
    resources = distinct([
      for reference in values(local.db_init_secrets) : regex("^arn:[^:]+:secretsmanager:[^:]+:[0-9]+:secret:[^:]+", reference)
    ])
  }
}

resource "aws_iam_role_policy" "db_init_secrets" {
  name   = "read-database-secrets"
  role   = aws_iam_role.db_init_execution.id
  policy = data.aws_iam_policy_document.db_init_secrets.json
}

resource "aws_ecs_task_definition" "db_init" {
  family                   = "${local.name}-db-init"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.db_init_execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = "db-init"
      image     = var.postgres_client_image
      essential = true

      # The script travels as an environment variable (it holds no secret) and is piped into psql. psql's own
      # PG* variables say where to connect. sslmode=require: RDS for PostgreSQL 16 refuses unencrypted connections.
      entryPoint = ["sh", "-c"]
      command    = ["printf '%s' \"$DB_INIT_SQL\" | psql --no-psqlrc --set ON_ERROR_STOP=1"]

      environment = [
        { name = "DB_INIT_SQL", value = file("${path.module}/sql/db-init.sql") },
        { name = "PGDATABASE", value = "postgres" },
        { name = "PGHOST", value = module.postgres.address },
        { name = "PGPORT", value = tostring(module.postgres.port) },
        { name = "PGSSLMODE", value = "require" },
        { name = "PGUSER", value = module.postgres.master_username },
      ]
      secrets = [for name, reference in local.db_init_secrets : { name = name, valueFrom = reference }]

      readonlyRootFilesystem = true

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.db_init.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])
}
