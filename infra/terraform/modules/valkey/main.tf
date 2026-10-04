# One ElastiCache node running Valkey: the open-source fork of Redis 7.2 that AWS prices below Redis OSS on
# ElastiCache. Everything the services use is in Valkey: strings and hashes, sorted sets (leaderboards), EVAL/EVALSHA
# Lua scripts (the buzz race, the gateway's rate limiter) and pub/sub (the broadcast relay). Nothing uses Redis
# Stack modules, the only Redis-only features.
#
# Node-based, not ElastiCache Serverless. Serverless Valkey is cheap when idle (it bills per GB stored, with a small
# minimum, plus per request) and creates in about a minute, but:
#   - it bills every command: the buzz race and the broadcast relay run thousands per second during a load test,
#     so a demo's price would depend on the traffic. One cache.t4g.micro is a flat hourly price;
#   - its endpoint speaks cluster mode, so every client would need Spring's cluster configuration, which nothing
#     else here (compose, kind) uses. The keys already carry {sessionId} hash tags, so the scripts would still work.
#
# TLS on (the services set SPRING_DATA_REDIS_SSL_ENABLED) and an AUTH token, kept out of the Terraform state the
# same way as the database passwords (modules/postgres).

resource "aws_elasticache_subnet_group" "this" {
  name        = var.name
  description = "Private subnets for ${var.name}"
  subnet_ids  = var.subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.name}-valkey"
  description = "Valkey: only from the given ECS tasks"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.name}-valkey"
  }
}

resource "aws_vpc_security_group_ingress_rule" "from" {
  for_each = var.client_security_group_ids

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "From ${each.key}"
}

resource "aws_secretsmanager_secret" "auth" {
  name        = "${var.name}/valkey-auth-token"
  description = "AUTH token of the Valkey node"

  # Deleted at once on demo-down, so the next demo-up can reuse the name.
  recovery_window_in_days = 0
}

ephemeral "aws_secretsmanager_random_password" "auth" {
  # ElastiCache allows 16-128 printable characters but not @, " or /. Letters and digits avoid all of them.
  password_length     = 64
  exclude_punctuation = true
}

# The same ephemeral value goes to the secret (for the services) and to the node (to check them against). Both are
# written in the same run, so they match; later runs generate a new value that neither uses (the versions below).
resource "aws_secretsmanager_secret_version" "auth" {
  secret_id                = aws_secretsmanager_secret.auth.id
  secret_string_wo         = ephemeral.aws_secretsmanager_random_password.auth.random_password
  secret_string_wo_version = 1
}

# A "replication group" of one node: the resource that supports TLS and an AUTH token (a bare cache cluster doesn't).
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Live game state, rate limits and the broadcast relay"

  # No engine_version and no parameter group: ElastiCache uses its current default Valkey version and that
  # version's default parameters.
  engine    = "valkey"
  node_type = var.node_type
  port      = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false # needs a replica to fail over to
  multi_az_enabled           = false

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
  auth_token_wo              = ephemeral.aws_secretsmanager_random_password.auth.random_password
  auth_token_wo_version      = 1

  # Demo: no snapshots to keep or pay for.
  snapshot_retention_limit = 0
  apply_immediately        = true
}
