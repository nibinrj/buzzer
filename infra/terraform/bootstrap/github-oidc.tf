# How GitHub Actions deploys without an AWS access key anywhere. A workflow asks GitHub for a short-lived OIDC token
# ("this run is repo nibinrj/buzzer, branch main"), hands it to AWS STS, and gets temporary credentials for the role
# below, valid for one hour. Nothing long-lived to leak, rotate or store as a repository secret.
#
# In the bootstrap, not envs/dev: deploy.yml pushes images on every merge to main, also while no demo is running.

# AWS trusts tokens signed by GitHub's issuer. No thumbprint: AWS checks GitHub's certificate against its own trusted
# certificate authorities.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"] # the audience configure-aws-credentials asks GitHub for
}

locals {
  # The token's "sub" claim names the repository and where the run comes from. Exactly two are accepted:
  #   a push to main                         -> repo:<owner>/<repo>:ref:refs/heads/main
  #   a job that runs in the "dev" environment -> repo:<owner>/<repo>:environment:dev
  # A pull request (repo:<owner>/<repo>:pull_request), any other branch and any fork get nothing.
  deploy_subjects = [
    "repo:${var.github_repository}:ref:refs/heads/main",
    "repo:${var.github_repository}:environment:${var.environment}",
  ]

  demo_cluster = "${var.project}-${var.environment}"
}

data "aws_iam_policy_document" "deploy_trust" {
  statement {
    # TagSession: configure-aws-credentials tags the session with the run's details (repository, workflow, actor),
    # which then show up in CloudTrail next to everything the run did.
    actions = ["sts:AssumeRoleWithWebIdentity", "sts:TagSession"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.deploy_subjects
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.project}-github-deploy"
  description          = "GitHub Actions deploys of ${var.github_repository} (main and the ${var.environment} environment only)"
  assume_role_policy   = data.aws_iam_policy_document.deploy_trust.json
  max_session_duration = 3600
}

# Least privilege: what deploy.yml does, on this project's resources, and nothing else.
data "aws_iam_policy_document" "deploy" {
  # docker login to ECR. GetAuthorizationToken is account-wide in AWS's design: it can't name a repository.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Push (and check before pushing) to the five buzzer/* repositories only.
  statement {
    sid = "EcrPushBuzzerImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
    ]
    resources = [for repository in aws_ecr_repository.service : repository.arn]
  }

  # Read the current task definition and register its next revision (new image tag). Neither action can be limited
  # to a resource in IAM; what a new revision may do is still bounded by the PassRole statement below.
  statement {
    sid       = "EcsTaskDefinitions"
    actions   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
    resources = ["*"]
  }

  # Is a demo running? (deploy.yml stops politely if the cluster doesn't exist.)
  statement {
    sid       = "EcsDemoCluster"
    actions   = ["ecs:DescribeClusters"]
    resources = ["arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:cluster/${local.demo_cluster}"]
  }

  # Point a service at the new revision, watch it roll out, and roll back: only services in the demo cluster.
  statement {
    sid       = "EcsDemoServices"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = ["arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${local.demo_cluster}/*"]
  }

  # A task definition names an execution role and a task role; registering one means handing those roles to ECS.
  # Only the demo's own roles, and only to ECS tasks: the deploy role can't use this to give itself more.
  statement {
    sid       = "PassDemoTaskRoles"
    actions   = ["iam:PassRole"]
    resources = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${local.demo_cluster}-*"]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # The smoke test finds the demo's address by the load balancer's name. Describe calls can't be resource-limited.
  statement {
    sid       = "FindDemoLoadBalancer"
    actions   = ["elasticloadbalancing:DescribeLoadBalancers"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy-buzzer"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
