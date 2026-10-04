# Diagrams

C4-style views of Buzzer, drawn from the code and Terraform as they are (Mermaid, rendered by GitHub).

| Diagram | Shows |
|---|---|
| [1. System context](#1-system-context) | Who uses Buzzer and what it depends on |
| [2. Containers](#2-containers) | The five services, their stores, and every call or event between them |
| [3. One answer, end to end](#3-one-answer-end-to-end) | The buzz race and asynchronous scoring as a sequence |
| [4. AWS deployment](#4-aws-deployment) | The demo environment: network, ECS, data stores |
| [5. Delivery](#5-delivery) | CI, images and deploys |

## 1. System context

```mermaid
flowchart TB
    host["<b>Host</b><br/>[Person]<br/>Writes quizzes, runs live games"]
    player["<b>Player</b><br/>[Person]<br/>Joins with a room code, buzzes in"]
    buzzer["<b>Buzzer</b><br/>[Software system]<br/>Real-time multiplayer quiz:<br/>live questions, fair buzz order, scores"]
    owner["<b>Operator</b><br/>[Person]<br/>Runs demos, deploys"]
    aws["<b>AWS</b><br/>[External system]<br/>ECS Fargate, RDS, ElastiCache,<br/>Secrets Manager, CloudWatch"]
    gh["<b>GitHub Actions</b><br/>[External system]<br/>CI, image builds"]

    host -- "HTTP + WebSocket (STOMP)" --> buzzer
    player -- "HTTP + WebSocket (STOMP)" --> buzzer
    owner -- "tasks.ps1 demo-up / demo-down" --> aws
    gh -- "OIDC: push images, roll out" --> aws
    buzzer -. "runs on (demo)" .-> aws
```

## 2. Containers

Kafka carries exactly three topics. Everything else between services is a REST call.

```mermaid
flowchart LR
    client["Browser / test client / k6<br/>[Container: HTML + JS]"]

    subgraph buzzer [Buzzer]
        gw["<b>gateway</b> :8080<br/>[Spring Cloud Gateway, WebFlux]<br/>routes, edge JWT check, rate limits,<br/>CORS, blocks /internal/**"]
        id["<b>identity-service</b> :8081<br/>[Spring Boot, MVC]<br/>register, login, RS256 tokens,<br/>refresh rotation, guests, JWKS"]
        qz["<b>quiz-service</b> :8082<br/>[Spring Boot, MVC]<br/>quiz authoring, publish,<br/>internal snapshot"]
        ss["<b>session-service</b> :8083 ×N<br/>[Spring Boot, MVC + STOMP]<br/>rooms, joins, live state,<br/>buzz race, outbox"]
        sc["<b>scoring-service</b> :8084<br/>[Spring Boot, MVC]<br/>points, leaderboards, results"]

        iddb[("identity_db<br/>[PostgreSQL 16]")]
        qzdb[("quiz_db<br/>[PostgreSQL 16]")]
        ssdb[("session_db<br/>[PostgreSQL 16]")]
        scdb[("scoring_db<br/>[PostgreSQL 16]")]
        redis[("Redis / Valkey<br/>live state, Lua scripts,<br/>pub/sub relay, rate limits,<br/>leaderboards")]
        kafka{{"Kafka API<br/>[Redpanda]"}}
    end

    client -- "REST /api/**, WebSocket /ws" --> gw
    gw -- "/api/auth/**, /.well-known/**" --> id
    gw -- "/api/quizzes/**" --> qz
    gw -- "/api/sessions/**, /ws/**" --> ss
    gw -- "/api/results/**" --> sc
    gw -- "token buckets" --> redis

    qz & ss & sc & gw -. "JWKS (public keys)" .-> id
    ss -- "GET /internal/quizzes/{id}/snapshot" --> qz

    id --- iddb
    qz --- qzdb
    ss --- ssdb
    sc --- scdb

    ss -- "submit_answer.lua, state,<br/>broadcast relay" --> redis
    sc -- "leaderboard ZSETs" --> redis

    ss -- "session.answer-submitted<br/>session.lifecycle<br/>(outbox)" --> kafka
    kafka -- "consumed by" --> sc
    sc -- "scoring.score-updated" --> kafka
    kafka -- "consumed by<br/>(leaderboard push)" --> ss
```

## 3. One answer, end to end

```mermaid
sequenceDiagram
    autonumber
    participant P as Player
    participant GW as gateway
    participant SS as session-service (any task)
    participant R as Redis
    participant DB as session_db
    participant K as Kafka
    participant SC as scoring-service
    participant SDB as scoring_db

    P->>GW: STOMP SEND /app/sessions/{id}/answer
    GW->>SS: same WebSocket, proxied
    SS->>R: EVALSHA submit_answer.lua (open? deadline? duplicate?)
    R-->>SS: accepted, seq, correctRank
    SS->>DB: one transaction: answer row + outbox row
    SS-->>P: ack (seq, accepted)
    Note over SS,K: OutboxPublisher, every 300 ms
    SS->>K: session.answer-submitted (key = sessionId)
    K->>SC: record
    SC->>SDB: one transaction: score + processed_events row
    SC->>R: leaderboard ZADD
    SC->>K: scoring.score-updated
    SC->>K: commit the offset (only now)
    K->>SS: scoring.score-updated
    SS->>R: PUBLISH relay
    R-->>SS: every session task receives it
    SS-->>P: leaderboard push (STOMP)
```

## 4. AWS deployment

The demo environment in ap-south-1, created by `.\tasks.ps1 demo-up` and destroyed by `demo-down`.

```mermaid
flowchart TB
    internet((Internet))

    subgraph vpc ["VPC 10.0.0.0/16, 2 AZs"]
        subgraph public ["Public subnets (route to the internet gateway)"]
            alb["ALB :80<br/>idle timeout 120 s"]
            subgraph ecs ["ECS cluster buzzer-dev: Fargate Spot, ARM64, Service Connect"]
                gw[gateway ×1]
                id[identity-service ×1]
                qz[quiz-service ×1]
                ss["session-service ×2–3<br/>(CPU autoscaling)"]
                sc[scoring-service ×1]
                rp[redpanda ×1]
                init["db-init<br/>(one-off task)"]
            end
        end
        subgraph private ["Private subnets (no route out)"]
            rds[("RDS PostgreSQL 16<br/>db.t4g.micro<br/>4 databases, 4 roles")]
            vk[("ElastiCache Valkey<br/>cache.t4g.micro, TLS + AUTH")]
        end
        s3ep["S3 gateway endpoint"]
    end

    sm["Secrets Manager<br/>DB passwords, Valkey token,<br/>JWT key, RDS master"]
    ecr["ECR buzzer/*<br/>(bootstrap)"]
    cw["CloudWatch Logs<br/>1-day retention"]

    internet --> alb --> gw
    gw --> id & qz & ss & sc
    ss --> qz
    ss & sc --> rp
    id & qz & ss & sc --> rds
    init --> rds
    gw & ss & sc --> vk
    ecs -. "public IPs: pull, logs, secrets" .-> ecr & cw & sm
```

Security groups allow each arrow above and nothing else: the ALB accepts port 80 from anywhere and reaches only the
gateway; each service accepts only its callers on its own port; RDS and Valkey accept only the services that use them.

## 5. Delivery

```mermaid
flowchart LR
    pr[Pull request] --> ci
    main[Merge to main] --> ci & img
    ci["ci.yml<br/>mvnw verify (Testcontainers) ·<br/>image build · Trivy · gitleaks<br/>no AWS access"]
    img["Images job<br/>arm64 build → ECR :commit"]
    dep["Manual deploy (environment dev)<br/>new revision → roll out →<br/>smoke test → roll back on failure"]
    img & dep -- "OIDC → STS<br/>1-hour credentials" --> role["buzzer-github-deploy role<br/>(main + dev only)"]
    role --> ecrr[(ECR)] & ecsr[ECS buzzer-dev]
    local["Operator: tasks.ps1 demo-up -Tag commit"] --> ecsr
```

`.github/workflows/deploy.yml` runs both jobs. Until its `AWS_DEPLOY_ROLE_ARN` variable is set, `.\tasks.ps1 push`
builds the images and the operator pushes them.
