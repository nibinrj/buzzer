# Kafka for the three topics (session.answer-submitted, session.lifecycle, scoring.score-updated), one of two ways:
#
#   redpanda (default)  one Redpanda task on Fargate Spot, the same image and dev mode as docker-compose. No
#                       persistent storage: its data lives in the task and dies with it (or with a Spot reclaim).
#                       Acceptable for a demo, not for real games: answers still in session-service's outbox wait
#                       and are published once the broker is back, but records the broker had already accepted and
#                       scoring-service hadn't read yet are gone, so those answers are never scored.
#   msk                 Amazon MSK, two brokers in two AZs, TLS. What production would use. About 25 minutes to
#                       create and billed per broker-hour, so it's opt-in.

locals {
  kafka_clients = {
    session = module.service["session-service"].security_group_id
    scoring = module.service["scoring-service"].security_group_id
  }

  msk_bootstrap_servers = join(",", [for cluster in aws_msk_cluster.this : cluster.bootstrap_brokers_tls])

  # What every Kafka client gets (services.tf).
  kafka_environment = var.kafka_mode == "msk" ? {
    KAFKA_BOOTSTRAP_SERVERS        = local.msk_bootstrap_servers
    SPRING_KAFKA_SECURITY_PROTOCOL = "SSL"
    # Two brokers: each partition on both, so losing one broker loses nothing.
    KAFKA_TOPIC_REPLICAS = "2"
    } : {
    # Redpanda's Service Connect name. It advertises the same name (below), so clients keep using it after the
    # first metadata request.
    KAFKA_BOOTSTRAP_SERVERS        = "redpanda:9092"
    SPRING_KAFKA_SECURITY_PROTOCOL = "PLAINTEXT"
    KAFKA_TOPIC_REPLICAS           = "1"
  }
}

# --- redpanda -------------------------------------------------------------------------------------------------------

module "redpanda" {
  source   = "../../modules/ecs-service"
  for_each = var.kafka_mode == "redpanda" ? toset(["redpanda"]) : toset([])

  name         = "redpanda"
  name_prefix  = local.name
  cluster_name = aws_ecs_cluster_capacity_providers.this.cluster_name

  image            = var.redpanda_image
  container_port   = 9092
  cpu              = 1024
  memory           = 2048
  cpu_architecture = var.cpu_architecture
  use_spot         = var.use_spot

  # Started even in demo-up's first apply, so it's ready before the services that use it.
  desired_count = 1

  command = [
    "redpanda", "start",
    # Development settings (no fsync per write, relaxed resource checks): right for a throwaway broker.
    "--mode=dev-container",
    "--smp=1",
    # Below the task's 2 GB, leaving room for the Service Connect proxy.
    "--memory=1G",
    "--kafka-addr=0.0.0.0:9092",
    # The address clients are told to use after their first contact: the Service Connect name.
    "--advertise-kafka-addr=redpanda:9092",
  ]
  # Redpanda writes its data under /var/lib/redpanda inside the container.
  read_only_root_filesystem = false

  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.public_subnet_ids

  ingress_security_group_ids = local.kafka_clients
  service_connect_namespace  = aws_service_discovery_http_namespace.this.arn
}

# --- msk ------------------------------------------------------------------------------------------------------------

resource "aws_security_group" "msk" {
  for_each = var.kafka_mode == "msk" ? toset(["msk"]) : toset([])

  name        = "${local.name}-msk"
  description = "MSK brokers: TLS listener only, only from the Kafka clients"
  vpc_id      = module.network.vpc_id

  tags = {
    Name = "${local.name}-msk"
  }
}

# 9094 is MSK's TLS listener for clients. Plaintext (9092) is disabled below.
resource "aws_vpc_security_group_ingress_rule" "msk_from" {
  for_each = var.kafka_mode == "msk" ? local.kafka_clients : {}

  security_group_id            = aws_security_group.msk["msk"].id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 9094
  to_port                      = 9094
  description                  = "From ${each.key}"
}

resource "aws_msk_cluster" "this" {
  for_each = aws_security_group.msk

  cluster_name           = local.name
  kafka_version          = var.msk_kafka_version
  number_of_broker_nodes = length(module.network.private_subnet_ids) # one per AZ

  broker_node_group_info {
    instance_type   = "kafka.t3.small" # the smallest MSK broker
    client_subnets  = module.network.private_subnet_ids
    security_groups = [each.value.id]

    storage_info {
      ebs_storage_info {
        volume_size = 10 # GB per broker; the demo writes a few MB
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS" # clients must use TLS (SPRING_KAFKA_SECURITY_PROTOCOL=SSL)
      in_cluster    = true
    }
  }
}
