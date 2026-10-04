# ADR-007: The full AWS demo, and what it costs per hour

- **Status:** Accepted (measured cost and the first full demo, below, are still owed)
- **Date:** 2026-10-04
- **Deciders:** nibin
- **Related:** ADR-002 (the gateway is the only way in), ADR-004 (Redis orders the buzzes), ADR-005 (Kafka for scoring),
  ADR-006 (network topology), ADR-009 (kind locally). Code: `infra/terraform/{modules/postgres, modules/valkey,
  modules/ecs-service, envs/dev}`, `tasks.ps1` (`demo-up`, `demo-down`, `demo-seed`).

## Context

ADR-006 put one service on ECS behind an ALB. Running the whole game in AWS needs the rest: Postgres, Redis, Kafka,
the other four services, the way they find each other, and their secrets. The constraints are unchanged:

1. **Well under $1 per demo, nothing billable between demos** but the bootstrap (state bucket, images).
2. **Fast to create and destroy:** `demo-up` and `demo-down` run before and after every demo.
3. **No secret in the repo, and none in the Terraform state** where that can be avoided: the state lives in S3 and
   is readable by anyone who can read the bucket.
4. **The same images and the same configuration names** as docker-compose and kind: no AWS-only code path to test.
5. **Show the interesting parts working in AWS:** two session-service tasks behind one game (the Redis relay, ADR-003),
   scoring over Kafka (ADR-005), the buzz race in Redis (ADR-004).

## Decision

### Data stores (private subnets, reachable only from the services that use them)
1. **RDS PostgreSQL 16, db.t4g.micro, single-AZ, 20 GB gp3, encrypted.** One instance, **four databases, four login
   roles**: every service still owns its own database (the rule the local setup follows). Demo settings, each
   commented in the code: no backups, no final snapshot, no deletion protection, changes applied immediately.
2. **ElastiCache Valkey, one cache.t4g.micro node, TLS and an AUTH token.** Valkey runs everything the services use:
   EVAL/EVALSHA scripts, sorted sets, pub/sub. Node-based, not Serverless: a flat hourly price under load-test
   traffic, and no cluster-mode client configuration (Serverless speaks cluster mode; our keys already carry
   `{sessionId}` hash tags, so that door stays open).
3. **Kafka: one Redpanda task on Fargate Spot** (the compose image, dev mode, no persistent storage) by default;
   **Amazon MSK** (2 × kafka.t3.small, TLS) behind `kafka_mode = "msk"`. Losing the Redpanda task loses records the
   broker had accepted and scoring-service hadn't read yet: acceptable for a demo, not for real games.

### Secrets, kept out of the state
4. **The RDS master password is managed by RDS** (`manage_master_user_password`): created and stored in Secrets
   Manager by AWS, never seen by Terraform.
5. **Service database passwords and the Valkey token** come from an **ephemeral** `aws_secretsmanager_random_password`
   and are written through **write-only arguments** (`secret_string_wo`, `auth_token_wo`). Terraform sends them and
   forgets them: they appear in neither the state nor a saved plan. This needs Terraform ≥ 1.11 (`required_version`).
6. **identity-service's signing key** goes from `.secrets/jwt-private.pem` to Secrets Manager the same write-only
   way, as a Spring Boot **`base64:` resource location**. Fargate has no secret volumes to mount a PEM file from;
   `base64:` lets the existing `IDENTITY_JWT_PRIVATE_KEY_LOCATION` setting carry the file's content instead of a
   path, with no code change (a test proves Boot resolves it). The public key is public: a plain variable.
7. Every secret has `recovery_window_in_days = 0`, so the next `demo-up` can create one with the same name. ECS
   injects each into only the task that needs it; each execution role may read only its own.

### Services
8. **All five services on Fargate Spot, ARM64, 0.5 vCPU / 1 GB**; session-service runs **2 tasks**, autoscaling to 3
   on 60% average CPU (target tracking on ECS's free CPU metric). Redpanda gets 1 vCPU / 2 GB.
9. **The ALB forwards everything to the gateway only** (ADR-002), idle timeout 120 s for WebSockets. The gateway
   trusts exactly one proxy hop for the client IP (`GATEWAY_CLIENTIP_TRUSTEDPROXIES=1`), as behind Traefik on kind.
10. **ECS Service Connect** for service-to-service calls: `http://quiz-service:8082`, `redpanda:9092`, the same names
    compose and kind use. Each task gets a proxy that picks a healthy task of the callee; no internal load balancer,
    no DNS to manage. TCP mode, so HTTP, WebSocket upgrades and the Kafka protocol all pass.
11. **One security group rule per caller → callee pair**, on the callee's port only: the gateway reaches all four,
    every service reaches identity-service (JWKS), session-service reaches quiz-service (internal snapshot) and
    Kafka. A security group can't see paths: `/internal/**` stays protected by the gateway never routing it.
12. **Configuration is environment variables only**, with the names every `application.yml` already reads (as in
    the kind manifests). No `application-aws.yml` profile: the AWS-specific values (endpoints, TLS on Redis and
    Postgres, the proxy count) are data, not code.

### Demo lifecycle (`tasks.ps1`)
13. **`demo-up`**: apply with the services at 0 tasks → run a **one-off ECS task** that creates the four roles and
    databases (`envs/dev/sql/db-init.sql`, psql from the official postgres:16 image, passwords from Secrets
    Manager) → apply with the services running → wait until every service is stable → print the URL and the time.
    A task, not documented psql steps: RDS has no route from the internet, so anything outside the VPC would need
    a bastion host. The script is idempotent.
14. **`demo-down`**: destroy, then ask the tagging API for anything still tagged `Project=buzzer, Stack=dev` and
    **fail loudly** if something is (stopped tasks, inactive task definitions and secrets already scheduled for
    deletion don't count: they're free).
15. **`demo-seed`**: a throwaway host account and a published quiz on the running demo.

### The cost model (ap-south-1 list prices, 2026-10-03; Spot rates unconfirmed)

| While a demo runs | On-demand $/h | With Spot $/h |
|---|---|---|
| 6 service tasks, ARM64, 0.5 vCPU / 1 GB ($0.0145 each) | 0.087 | ≈ 0.03 |
| Redpanda, ARM64, 1 vCPU / 2 GB | 0.029 | ≈ 0.01 |
| ALB (hour + ~1 LCU) | ≈ 0.03 | ≈ 0.03 |
| RDS db.t4g.micro + 20 GB gp3 | ≈ 0.025 | ≈ 0.025 |
| ElastiCache Valkey cache.t4g.micro | 0.016 | 0.016 |
| 9 public IPv4 addresses (7 tasks + ALB in 2 AZs), $0.005 each | 0.045 | 0.045 |
| 7 secrets ($0.40/month each, prorated) | 0.004 | 0.004 |
| **Total** | **≈ 0.24** | **≈ 0.16** |
| CloudWatch Logs | $0.67 per GB ingested | |

A two-hour demo is roughly **$0.30–0.50**. `kafka_mode = "msk"` adds two broker-hours per hour and about 25 minutes
to every `demo-up`. Between demos: S3 state and a few images per repository in ECR, cents a month.

## Alternatives considered

| Option | Why not |
|---|---|
| **One RDS instance per service** | Four instances: 4× the database line and 4× the creation time. Database-per-service is about ownership and boundaries, which separate databases and roles on one instance keep. |
| **Aurora Serverless v2** | Scales to zero only with long resume times, minimum capacity bills more than a t4g.micro, and creation is slower. |
| **Generated passwords in the state** (`random_password`) | The state bucket would hold every password in plain text. Write-only arguments cost nothing and keep them out. |
| **Secrets in the bootstrap** (outlive demos) | $0.40/month each between demos, and one more thing that must be cleaned up by hand. A fresh secret per demo is free between demos. |
| **JWT key as a file baked into the image** | A private key in an image in ECR, readable by anyone who can pull it, and a new image per key rotation. |
| **A shell entrypoint that writes the key to /tmp** | Works, but replaces the image's exec-form entrypoint (signals, PID 1) with a script, for something Boot's `base64:` does natively. |
| **Internal ALB or Cloud Map DNS** between services | An internal ALB is another hourly bill. Plain DNS service discovery returns task IPs with no health-aware retry; Service Connect does that for free, under the same names as locally. |
| **`application-aws.yml` profiles** | Would duplicate what environment variables already express and add a profile no local run exercises. |
| **MSK by default** | ~25 minutes to create on every demo-up and the largest hourly line. Kept as an option and the production answer. |
| **ElastiCache Serverless** | Per-request billing makes load-test demos unpredictable, and cluster-mode clients would differ from local. Revisit below. |
| **On-demand Fargate** | Roughly 50% more for the compute lines. `use_spot = false` is one variable for a recorded demo. |

## Consequences

**Positive**
- The whole game runs in AWS from the same images and configuration names as locally; nothing AWS-specific in Java.
- No password, token or private key in the repo, the state or a plan file.
- `demo-down` proves its own work: it fails if anything billable with the demo's tags is left.
- Two session tasks behind one game, every demo: the Redis relay is exercised for real, not only in tests.

**Negative, accepted**
- **Single points of failure everywhere:** one RDS instance, one Valkey node, one Kafka broker, Spot tasks. A demo
  survives a reclaimed service task (ECS starts another); it does not survive losing Redpanda mid-game without
  losing some scores.
- **`demo-up` takes ~15 minutes**, mostly RDS and ElastiCache creation, plus a second apply.
- **No ALB health check on the internal services:** Service Connect stops sending to a task whose connections fail,
  but a task that is up and unhealthy is noticed later than behind an ALB.
- **Terraform resets session-service to 2 tasks on every apply**, even if autoscaling had added a third. Inside
  the autoscaling range, so harmless.
- **The cost table is list prices, not a bill.** Spot discounts in Mumbai aren't published per region in a form we
  could check; the measured number comes from Cost Explorer after a demo.

## Verification

| Claim | Evidence |
|---|---|
| The configuration is valid against the AWS provider | `terraform validate` in `envs/dev`: "Success! The configuration is valid." with AWS provider 6.67.0 (2026-10-04) |
| Write-only and ephemeral arguments exist in this provider version | The provider schema lists `secret_string_wo`, `auth_token_wo`, `password_wo` and the ephemeral `aws_secretsmanager_random_password` (2026-10-04) |
| Spring Boot resolves a `base64:` key location for identity-service | `JwtKeyBase64LocationTest`: a real SpringApplication loads the key pair from `base64:` locations (2026-10-04) |
| `demo-up` creates a working game | **Owed:** `demo-up`, `demo-seed`, a full game from the test client against the ALB URL |
| Players on different session tasks all see every broadcast | **Owed:** Logs Insights on session-service's log group: the answer acks of one game come from two log streams (one per task), and every player received every question |
| `demo-down` leaves nothing billable | **Owed:** `demo-down` ends with "nothing tagged Project=buzzer, Stack=dev is left" |
| Cost per demo hour | **Owed:** Cost Explorer the next day, filtered by tag `Project=buzzer`, next to the table above |

## Revisit when

- **Real games, not demos:** MSK (or Redpanda with persistent storage and three brokers), Multi-AZ RDS with backups,
  a Valkey replica with automatic failover, on-demand tasks as the base with Spot on top. Each roughly doubles its
  line of the table.
- **A NAT gateway** becomes worth it when the tasks move to private subnets (ADR-006), i.e. as soon as anyone but the
  owner uses the system.
- **ElastiCache Serverless:** if demos stay short and quiet, its per-GB-hour minimum may undercut a node; measure
  one demo's ECPU count first.
- **Measured cost differs from the table by more than 50%:** find the line that's wrong before the next demo.
