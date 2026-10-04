# Two roles per service, as ECS defines them:
#   execution role  used by ECS itself, BEFORE the app runs: pull the image from ECR, create the log stream, read the
#                   secrets it injects as environment variables.
#   task role       the app's own AWS identity. Our services call no AWS API, so it has no permissions at all.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

# Only ECS tasks may assume these roles, and only on behalf of this account (aws:SourceAccount closes the
# "confused deputy" gap: another account's ECS can't be tricked into using them).
data "aws_iam_policy_document" "ecs_tasks_assume" {
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

resource "aws_iam_role" "execution" {
  name               = "${local.full_name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# AWS's managed policy for exactly this job: ECR pull and CloudWatch Logs writes.
resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Read access to this service's own secrets, and nothing else. Created only when the service has secrets
# (for_each over a map with one or zero entries: this repo uses for_each rather than count).
data "aws_iam_policy_document" "read_secrets" {
  for_each = length(var.secrets) > 0 ? { secrets = true } : {}

  statement {
    actions = ["secretsmanager:GetSecretValue"]
    # A reference may name one JSON key of a secret (<secret arn>:password::); IAM wants the secret's own ARN, which
    # ends at its name: arn:aws:secretsmanager:<region>:<account>:secret:<name>.
    resources = distinct([
      for reference in values(var.secrets) : regex("^arn:[^:]+:secretsmanager:[^:]+:[0-9]+:secret:[^:]+", reference)
    ])
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  for_each = data.aws_iam_policy_document.read_secrets

  name   = "read-own-secrets"
  role   = aws_iam_role.execution.id
  policy = each.value.json
}

resource "aws_iam_role" "task" {
  name               = "${local.full_name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}
