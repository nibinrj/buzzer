# ADR-006: The AWS demo network: public subnets for tasks, no NAT gateway, private subnets for data

- **Status:** Accepted (the first apply/destroy, below, is still owed)
- **Date:** 2026-10-03
- **Deciders:** nibin
- **Related:** ADR-002 (gateway responsibilities), ADR-009 (kind locally, ECS in AWS). ADR-007 (cost model) follows in Phase 7. Code: `infra/terraform/{bootstrap, modules/network, modules/ecs-service, envs/dev}`.

## Context

The AWS deployment is an **on-demand demo environment**: `demo-up` creates it, someone looks at it for an hour or two,
`demo-down` destroys it. Region: **ap-south-1 (Mumbai)**. The requirements:

1. **Cents per demo hour, nothing between demos** except the long-lived bootstrap (state bucket, image repositories).
2. **Data stores unreachable from the internet:** Postgres and Redis/Valkey hold everything the game knows.
3. **Services reachable only through the load balancer**, never directly.
4. **Fast to create and destroy:** minutes, not half an hour, every demo.
5. **The tasks can still reach what they need** at startup and while running: ECR (images), CloudWatch Logs, Secrets
   Manager, and the data stores.
6. **No secrets, account ids or hardcoded AZs in the repo**; everything as code, tagged, reproducible.
7. **An honest production story:** what this design gives up, and what would change for real users.

## Decision

### Two stacks
1. **`bootstrap`** (applied once, never destroyed by `demo-down`): the Terraform state bucket and one ECR repository per
   service. Its own state stays local (gitignored): it creates the bucket the other stacks keep their state in.
   - State bucket: versioned, SSE-S3, all public access blocked, ACLs disabled, **a policy denying any request that
     isn't TLS**, old versions expire after 30 days, `prevent_destroy`. The name is
     `buzzer-tfstate-<account>-<region>`, computed, not written in the code.
   - ECR: **immutable tags** (a tag is a commit, forever), basic scan on push (free), keep the last 3 images.
2. **`envs/dev`** (the demo): everything else. State in the bucket with **S3-native locking** (`use_lockfile`, no
   DynamoDB table). The bucket and region are passed at `init` (partial backend configuration), so the account id
   never lands in the repo.
3. **Tags** on everything through `default_tags`: Project, Env, ManagedBy, Owner, plus **`Stack`** (`bootstrap` or
   `dev`). `demo-down` can then check that nothing tagged `Project=buzzer, Stack=dev` is left.

### The network (`modules/network`)
4. **One VPC, a /16, over two Availability Zones** taken from the region's list (regular AZs only, none hardcoded).
   Two is the minimum an ALB and the RDS/ElastiCache subnet groups accept.
5. **Public subnets** (one /24 per AZ, from `.0`): the **ALB and the ECS tasks**. Default route to the internet gateway.
   Nothing gets a public IP automatically; each ECS task asks for one (`assign_public_ip`).
6. **Private subnets** (one /24 per AZ, from `.100`): **RDS and ElastiCache** (Phase 7). **No route out at all.**
7. **No NAT gateway and no interface endpoints.** Tasks reach AWS APIs over their own public IP.
8. **One S3 gateway endpoint** on both route tables. It's free and keeps S3 traffic (including ECR image layers) on
   AWS's network.
9. **The VPC's default security group is emptied**, so anything that forgets to name a security group gets no access.

### Who may talk to whom (security groups)
10. Internet → **ALB on port 80** → each service's tasks **on that service's port only** (the ALB's egress names each
    service's security group). A task's security group accepts nothing else: a public IP doesn't make it reachable.
11. Tasks → anywhere outbound (HTTPS to AWS APIs; Phase 7 adds the data stores, whose security groups will accept only
    the tasks' security groups).

### Compute (`modules/ecs-service`)
12. **ECS on Fargate, `awsvpc`** (each task its own network interface and IP), cluster with both `FARGATE` and
    `FARGATE_SPOT`; services use **Spot by default** (`use_spot`). No Container Insights (billed per metric).
13. **ARM64 (Graviton) by default** (`cpu_architecture`). AWS's Fargate pricing page lists Spot for "x86 /ARM"; in
    Mumbai ARM vCPU is $0.02383/h against $0.04256/h for x86. Images are built for arm64 by `tasks.ps1 push`.
14. Per service: 0.5 vCPU / 1 GB, read-only root filesystem with a task-local `/tmp`, logs to CloudWatch with
    **1-day retention**, an execution role (ECR, logs, its own secrets only) and a task role with **no permissions**
    (the services call no AWS API). Deployment circuit breaker with rollback.
15. **The ALB checks `/readyz`** on the service port (K.3b). ECS has no `preStop`: `stopTimeout` (30 s, at most 120)
    and the target group's **30 s deregistration delay** give the same drain (ADR-009).
16. **Plain HTTP for now.** HTTPS needs a certificate for a domain the project doesn't own. See the consequences.

### Phase 6 scope
17. One service, **identity-service, at `desired_count = 0`**: it runs Flyway against Postgres at startup, and Postgres
    arrives in Phase 7. The ALB forwards everything to it until Phase 7 puts the gateway in front (ADR-002).

## Alternatives considered

| Option | Why not |
|---|---|
| **NAT gateway** (tasks in private subnets) | The textbook layout. One NAT gateway bills every hour plus per GB processed: roughly what everything else in a demo hour costs together, and **one per AZ** to be truly highly available. It also adds minutes to create/destroy. For a demo it buys isolation the security groups already give. |
| **Interface endpoints** instead of NAT (ECR API, ECR Docker, Logs, Secrets Manager) | $0.013/h per endpoint per AZ in Mumbai: 4 × 2 ≈ $0.10/h, more than the six task IPs ($0.03/h) they would replace, and each new AWS API the services call needs another. |
| **One AZ** | An ALB requires two, and so do RDS/ElastiCache subnet groups. Two subnets per tier cost nothing. |
| **The default VPC** | Every subnet in it is public: there'd be nowhere to put the data stores without an internet route. It also isn't code. |
| **IPv6 egress** (egress-only internet gateway, IPv6-only tasks) | Avoids public IPv4 charges, but not every AWS endpoint and image path we use is IPv6-ready, and it's a lot of new moving parts for a few cents. Revisit if IPv4 charges grow. |
| **Tasks behind the ALB with `assign_public_ip = false`** and no NAT | They couldn't pull their image from ECR or write logs: the task would never start. |
| **x86 Spot** | Cheaper than x86 on-demand, but ARM is cheaper still in Mumbai and Spot supports it. The architecture stays a variable. |
| **DynamoDB lock table** for state | Deprecated in Terraform 1.11 in favour of the S3 lock file: one less resource to create and pay for. |
| **Mutable image tags** (`latest`) | A tag that moves means a task definition doesn't say what runs, and a rollback can't be trusted. |
| **HTTPS with ACM** now | Needs a domain (and its DNS) the project doesn't have yet. See "Revisit when". |

## Consequences

**Positive**
- A demo hour is cents, and nothing but S3 + ECR (~$0.10/month) exists between demos.
- The data stores have no path to or from the internet, whatever happens to a security group.
- Each service accepts traffic only from the ALB, on one port; the default security group is empty.
- No account id, AZ name or secret in the repo; the state is private, encrypted, versioned and locked.
- Spot and ARM are variables: a recorded demo can switch to on-demand (`use_spot = false`), and x86 is one flag away.

**Negative, accepted**
- **Tasks have public IPv4 addresses** ($0.005/h each). They're not reachable (security groups), but a misconfigured
  rule would expose a task directly, which a private subnet would prevent. Defence in depth is one layer thinner.
- **Plain HTTP:** during a demo, passwords and JWTs cross the internet unencrypted. Acceptable only with throwaway
  demo accounts. Must change before anyone uses a real password (see below).
- **Spot can reclaim a task** with two minutes' warning, and Fargate never falls back to on-demand by itself. A
  one-task service is down until Spot capacity returns. For a demo that's a free resilience test; for a recording,
  `use_spot = false`.
- **Fargate ARM64 Spot in Mumbai specifically** isn't confirmed by a test yet (the pricing page lists ARM Spot; its
  per-region table loads dynamically). If tasks fail to place, `cpu_architecture = "X86_64"` (and `push -Arch amd64`)
  or `use_spot = false`.
- The JWT signing keys are files today (`IDENTITY_JWT_*_KEY_LOCATION`), and Fargate has no secret volumes: Phase 7.1
  must choose how they reach the container.
- Logs cost **$0.67 per GB ingested** in Mumbai. A load test at INFO level could be the largest line of a demo.

## Verification

| Claim | Evidence |
|---|---|
| The code is well-formed | `terraform fmt -recursive -check`: clean (2026-10-03) |
| The code is valid against the AWS provider (6.x) | `terraform validate` in `bootstrap` and `envs/dev`: "Success! The configuration is valid.", no warnings, AWS provider **6.67.0** pinned in both `.terraform.lock.hcl` files (2026-10-03) |
| `push` builds working arm64 images on the amd64 dev PC | identity-service via `docker buildx build --platform linux/arm64 --load` in 18 s; image `linux/arm64`, `java -version` = Temurin 21.0.12.1, runs as uid 10001, `uname -m` = `aarch64` (2026-10-03) |
| The bootstrap creates a private, TLS-only, versioned state bucket and immutable ECR repositories | **Owed:** the owner's `apply` of `bootstrap`; console or `aws s3api get-bucket-policy` |
| `envs/dev` state is in S3 with a lock file | **Owed:** `init` with the backend config, then a `.tflock` object visible during an `apply` |
| A task in a public subnet with no NAT can pull from ECR and log to CloudWatch | **Owed:** `identity_desired_count = 1` for one apply: the task starts, pulls, logs, then exits on the missing database (expected in Phase 6) |
| The ALB answers and forwards to identity-service's target group | **Owed:** `http://<alb>/readyz` returns 503 (no healthy target: expected until Phase 7 gives identity a database) |
| Only the ALB reaches the tasks | **Owed in Phase 7** (needs a task that stays up): the task's public IP on port 8081 times out while the ALB URL answers |
| `demo-down` leaves nothing billable but the bootstrap | **Owed:** `terraform destroy`, then no resource tagged `Project=buzzer, Stack=dev` remains; apply/destroy times noted |

## Revisit when

- **Real users or real passwords:** HTTPS. Either ACM + a domain on the ALB, or CloudFront in front of the ALB (HTTPS
  on `*.cloudfront.net` with no domain, WebSockets supported, a free tier), with the ALB accepting only CloudFront.
- **Production:** tasks in **private subnets** with a NAT gateway per AZ (or interface endpoints, once there are few
  enough APIs and enough traffic to make them cheaper), `assign_public_ip = false`; Multi-AZ RDS; WAF on the entry
  point; VPC flow logs; one on-demand task per service (`base = 1`) with Spot for the rest.
- **Public IPv4 becomes a large share of the bill** (more services or tasks): IPv6 with an egress-only gateway, or
  fewer public tasks behind one egress path.
- **The account has the legacy free tier:** RDS, ElastiCache and ALB hours may be free, which changes ADR-007's numbers,
  not this topology.
- **Terraform's floor:** CLAUDE.md pins ≥ 1.10; the S3 lock file is GA from 1.11. Raise `required_version` if a 1.10
  machine ever runs this.
