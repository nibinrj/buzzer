# Performance

Load tests play whole games with `tools/load/game.js` (k6, STOMP over WebSocket, through the gateway). How the
script works and what each metric means: the comments at the top of `game.js`.
Microbenchmarks of single functions are in `docs/benchmarks.md`.

## Local

### Conditions

- **Machine:** laptop, Intel i5-12500H (16 threads), 15.7 GB RAM, Windows 11. Docker Desktop (WSL 2 VM, 7.6 GiB)
  runs Postgres, Redis, Redpanda, Prometheus, Grafana, Jaeger. The five services run as JVMs on Windows
  (`.\tasks.ps1 run`), and k6 runs on the same machine. So these numbers compare runs with each other; they are not
  capacity figures. The kind cluster was stopped.
- **Gateway:** per-IP rate limits raised for the run (one k6 machine = one IP; see `run-game.ps1`). No 429s.
- **Game:** 20 questions, the host reveals 3 s after each question and moves on 1 s later. Players answer 0–300 ms
  after receiving a question, 70% correct. Joins spread over max(5 s, players / 10).
- **Server metrics:** Prometheus (scrape every 15 s) over each game's window, plus Jaeger traces. Gauges sampled
  every 15 s miss short bursts; histograms and counters don't.

### Baseline: 2026-10-03, 50 → 100 → 200 players

Run back to back (`run-game.ps1 -Players 50,100,200`). Every player saw every question and the end in every game;
answers acknowledged: 1,000 / 2,000 / 4,000 of 1,000 / 2,000 / 4,000.

**What players see (k6):**

| Players | ack p50 | ack p95 | ack p99 | question_broadcast p95 | push p50 | push p95 | push max |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 23 ms | 39 ms | 210 ms | 51 ms | 464 ms | 889 ms | 1.22 s |
| 100 | 21 ms | 47 ms | 81 ms | 46 ms | 617 ms | 1.17 s | 1.88 s |
| **200** | **112 ms** | **266 ms** ✗ | 344 ms | 41 ms | 1.01 s | **2.37 s** ✗ | 3.30 s |

✗ = threshold crossed (ack p95 < 250 ms, push p95 < 2 s). `question_fanout` also crossed, but it isn't valid
locally (it compares two clocks that drift apart). The two `stomp_errors` at 100 players were identified in the
H1 runs below, where stderr was kept: `socket error websocket: close 1002 (protocol error)` as players leave after
the game ended. That's a closing race in the script (DISCONNECT, then close), not a server fault. No service logged a
WARN or ERROR during the ladder.

**What the servers saw (Prometheus, Jaeger):**

| Players | answer handling p50 / p95 | server ack p50 / p95 | Hikari acquire avg (session) | inbound STOMP threads busy (max sampled) | scoring delay p50 / p95 | outbox backlog (max sampled) |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 19 / 44 ms | 21 / 47 ms | 0.4 ms | 2 of 32 | 0.49 / 2.1 s | 30 |
| 100 | 19 / 46 ms | 21 / 49 ms | 0.5 ms | 0 of 32 | 0.79 / 2.3 s | 35 |
| **200** | **66 / 145 ms** | **114 / 303 ms** | **13 ms** | **32 of 32** | **1.68 / 3.9 s** | 68 |

**Ruled out at this load:**
- **CPU.** session-service peaked at 3.4% of the machine (about half a core), system CPU at 36%.
- **GC.** Under 0.1 s of pauses per game in every service, longest pause 21 ms.
- **Kafka lag.** `records-lag-max` read 0 throughout. That is misleading, not good news (see H2).

### What broke first, and the three hypotheses

The first thing to break is the **synchronous answer path in session-service, between 100 and 200 players**.
Answer handling p50 tripled (19 → 66 ms) while the load only doubled. The p95 ack crossed 250 ms. Push latency
crossed 2 s for a different reason, further down the asynchronous path (H2, H3).

#### H1: each answer reads Postgres twice before Redis, and 32 STOMP threads share 10 connections

**Mechanism.** `SubmitAnswer.submit` runs these in order:
1. `players.find(sessionId, userId)`
2. `sessions.findById(sessionId)`: the whole session with all 20 questions and their options, on every answer
3. the Redis script
4. a transaction (answer row + outbox row)

That's three pool acquisitions per answer. A question arrives at all 200 players at once, so about 200 answers
land within 300 ms. Spring's inbound STOMP executor runs them on 32 threads (2 × CPU threads), and all of them
reach for the 10-connection Hikari pool at the same time.

**Evidence:**
- Hikari acquire time averaged 0.4 → 0.5 → **13 ms** (max 237 ms). How long each connection was held stayed flat
  (4–5 ms), so the queries didn't get slower. The wait for a connection did.
- About 4.7 acquires per answer: 18,818 acquires for 4,000 answers. The outbox poller and joins are in that count
  too, but the per-answer reads dominate.
- The slowest traced answer at 200 players spent **68 ms before reaching Redis** (`evalsha` started at +68 ms).
  At that point only the two reads have run, and JDBC isn't traced, so that gap is database time plus pool waiting.
- The inbound executor had **32 of 32 threads busy** at 200 players, against 0–2 at lower loads. The server ack p50
  (114 ms) minus the handling p50 (66 ms) means about 50 ms of waiting for a thread before handling starts.
- CPU was at about half a core: the threads were waiting, not computing.

**Cheapest experiment (config only):** rerun 200 players with session-service's pool at 32
(`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=32`). If handling p50 falls back toward 20 ms, the pool is the limit.
If it stays near 66 ms, Postgres itself is (connections queue inside Postgres instead). Then check with
`pg_stat_activity` during a burst, or count the statements per answer with Hibernate statistics
(`spring.jpa.properties.hibernate.generate_statistics=true`, a log setting).

**Likely fix, if confirmed:** don't read immutable data per answer. A started session's questions never change, and
neither does a player's membership. Keep them in memory or Redis, so an answer needs one connection (the insert
transaction) instead of three.

#### H2: scoring handles one session's events one at a time (~13 ms each)

**Mechanism.** All of a session's events are keyed by sessionId, so they land on **one partition**, read by **one
consumer thread**. The ordering guarantee costs exactly that. Each event:
- a transaction: dedup row + score
- the Redis leaderboard script
- sometimes a ScoreUpdated send

The `process` span takes ~13 ms (p50 at 200 players; 9.7 ms at 100). A question's ~200 answers then need about
200 × 13 ms ≈ **2.6 s** to drain, and the next question comes 4 s later.

**Evidence:**
- Scoring delay p50 went 0.49 → 0.79 → **1.68 s**, and p95 reached 3.9 s.
- Players in the top 10 (the fastest correct answers, so the first in each queue) saw pushes sooner than the
  median scoring delay: push p50 1.01 s against scoring delay p50 1.68 s. That's the shape of a FIFO queue.
- In traces, the gap between `session.answer-submitted send` and `process` was about 1.0 s at 100 players and
  1.2 s at 200.
- **`records-lag-max` stays 0.** The consumer fetches up to 500 records per poll, so the queue sits in the
  listener's batch, not in Kafka, and Kafka's lag never sees it. Watch `buzzer_scoring_delay_seconds` instead.

**Cheapest experiment (no code):** shorten the game's pace at 200 players (`QUESTION_MS=1500`, `REVEAL_MS=500`,
2 s per question). If scoring delay climbs question after question instead of draining between them, the
consumer is saturated. Its capacity is 1 / 13 ms ≈ 75 answers per second per session. At the current pace that
predicts the wall at about **300 players** (300 × 13 ms ≈ 3.9 s per 4 s question).

#### H3: the outbox publishes at most 100 events per run, one run every 300 ms

**Mechanism.** `OutboxPublisher` takes the oldest 100 unsent rows, sends them, waits for the acks, and the next run
starts 300 ms after this one ends (`session.outbox.poll-interval-ms: 300`, `batch-size: 100`). A 200-answer burst
needs at least two runs, so the last answers wait 600 ms or more before they even reach Kafka.

**Evidence:**
- In the slowest traced answer at 200 players, `outbox publish` came at **+809 ms**; at 100 players, at +246 ms.
- Outbox backlog peaked at 68 rows at 200 players, against 30–35 at lower loads (sampled, so the real peak was
  higher).
- Publishing itself is fast: under 0.1 ms per row on average. The cadence adds the waiting, not the work.

**Cheapest experiment (config only):** at 200 players, set `SESSION_OUTBOX_BATCH_SIZE=500`, then separately
`SESSION_OUTBOX_POLL_INTERVAL_MS=100`. Compare where `outbox publish` lands in the traces and the scoring delay p50.
Expected gain: up to ~0.5 s of push latency. It doesn't help H2: a faster outbox only fills scoring's queue sooner.

### Order of the experiments

H1 first: it's the player-facing break (ack p95 over 250 ms), and it's one environment variable. H3 next (two
variables). H2 needs a design change to fix (more parallelism inside a session while keeping per-player order, or
cheaper events), so measure it but fix it last.

### Experiment H1: session-service pool 10 → 32 (2026-10-03)

**Method.** One change, same session, same order for both variants: start session-service fresh, play a 50-player
warm-up game (so the JIT has compiled the hot paths), then the measured 200-player game. Control with the default
pool (10), then session-service restarted with `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=32` (Prometheus confirmed
`hikaricp_connections_max` = 32). Everything else stayed up and unchanged.

| 200 players | ack p50 | ack p95 | ack p99 | handling p50 / p95 | Hikari acquire avg | connection held avg | push p50 | push p95 | scoring delay p50 / p95 | outbox backlog (max sampled) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| pool 10 (control) | 88 ms | 233 ms | 332 ms | 59 / 137 ms | 10.8 ms | 5.0 ms | 890 ms | 1.91 s | 0.62 / 2.3 s | 0 |
| **pool 32** | **51 ms** | **91 ms** | **105 ms** | 37 / 83 ms | **0.45 ms** | **9.0 ms** | 1.28 s | **3.46 s** ✗ | 1.88 / 4.7 s | 139 |

**Verdict: H1 confirmed, and it isn't the whole story.**
- **The pool was a limit.** The wait for a connection dropped from 10.8 ms to 0.45 ms, and the ack p95 more than
  halved (233 → 91 ms).
- **Postgres is the next limit behind it.** With 32 connections working at once, each was held almost twice as
  long (5.0 → 9.0 ms), and all 32 were busy at one sample. Handling p50 fell to 37 ms, not back to the 19 ms seen
  at 100 players. The queue moved from the pool into the database. That points at the real fix: an answer that
  needs one connection instead of three (stop re-reading the session and the player). A bigger pool only trades a
  wait in Java for contention in Postgres. In AWS it would also eat into a small RDS instance's connection limit,
  shared by four databases (ADR-007).
- **The queue moved downstream, as H2/H3 predicted.** Answers reached the outbox faster, so its backlog rose (0 →
  139 sampled), scoring delay p50 tripled (0.62 → 1.88 s), and push p95 crossed its threshold (1.91 → 3.46 s).
  Scoring applied the same ~43 events/s in both runs: it runs at a fixed rate, whatever arrives.

**Run-to-run variance is large.** The control's numbers differ from the baseline run 40 minutes earlier at the same
load: ack p95 233 vs 266 ms, scoring delay p50 0.62 vs 1.68 s. So compare only runs made in the same session, back to
back, in the same order, and make one change at a time.

### Fix: an answer reads nothing from Postgres before Redis (SessionFacts, 2026-10-03)

**Change (session-service only).** `SessionFacts` keeps the facts that never change once written in two bounded
in-memory caches (Caffeine): a session's host and frozen questions, and who joined. `SubmitAnswer` and the STOMP
destination check (`SessionAccess`) read them from there instead of Postgres. Only memberships that exist are
cached, so a player who joins after a miss is found. Pool unchanged at 10.

**Method.** As for H1: same session, the whole stack restarted fresh, warm-up 50 then measured 200. Control on the
committed code first; then only session-service restarted on the new code.

| 200 players | before (control) | after (SessionFacts) | change |
|---|---:|---:|---|
| **ack p50** (k6) | 73 ms | **12 ms** | ÷6 |
| **ack p95** (k6) | 191 ms | **111 ms** | −42% |
| ack p99 (k6) | 244 ms | 152 ms | −38% |
| pool connections per answer | 4.35 | **1.19** | the target: one transaction |
| answer handling p50 / p95 (server) | 46 / 98 ms | 9.9 / 85 ms | |
| server ack p50 / p95 | 74 / 232 ms | 11 / 153 ms | |
| connection held avg | 4.6 ms | 10.5 ms | the one left is the busier one (below) |
| session-service CPU (max) | 3.1% | 1.9% | fewer queries to build and map |
| cache hit ratio (memberships) | – | 98% (8,877 / 9,066) | misses = each player's first lookup |
| leaderboard push p50 / p95 | 838 ms / 1.74 s | 969 ms / **2.39 s** ✗ | worse: see below |
| scoring delay p50 / p95 | 0.78 / 2.3 s | 1.32 / 3.9 s | |
| scoring events applied | 45.5 /s | 43.2 /s | scoring's fixed rate |

At 50 players the ack went from 14 to 8 ms (p50) and from 32 to 12 ms (p95). Every player saw every question in
all four games; `question_broadcast` stayed at 25 ms (p50) throughout.

**What it did and didn't fix.**
- **Fixed: the queue for connections.** One acquisition per answer instead of ~4.4, so the typical answer no longer
  waits. The ack p50 (12 ms) is back to the 50-player level.
- **Left: the tail.** The remaining transaction (answer row + outbox row) is now the only Postgres work, and a
  question's 200 commits arrive within ~300 ms. Each connection is held twice as long as before (4.6 → 10.5 ms), and
  the acquire wait stayed ~8 ms on average. Postgres' write path, under a burst, is the next limit for the slowest
  acks. It's still within the threshold (p95 111 ms < 250 ms).
- **Exposed: the asynchronous path.** Answers now reach the outbox and scoring sooner, scoring still applies ~44
  events/s per session (H2), so its queue is longer and push p95 crossed 2 s. The pool-32 experiment showed the same.

## Summary: what broke first locally, and why

At 200 players the first thing to break was **the synchronous answer path in session-service**. It wasn't CPU,
memory or GC: it was a queue. Each answer took about 4.4 Postgres connections. The STOMP permission check and the use
case each re-read the player and the session's frozen questions before Redis, then a transaction recorded the
answer. A question reaches everyone at once, so ~200 answers arrived within 300 ms, and 32 STOMP threads queued for
10 pool connections. Answer handling went from 19 ms to 66 ms while the load only doubled. A bigger pool confirmed
the diagnosis (ack p95 233 → 91 ms) but moved the queue into Postgres and would cost connections a small RDS instance
doesn't have. Caching the facts that never change brought an answer down to one connection, and the ack p50 from 73
to 12 ms with the same pool. The next limits are already measured: the burst of commits sets the ack tail, and
scoring's one-consumer-per-session design (~44 events/s, about 13 ms each) sets the leaderboard's push latency,
which crossed 2 s at 200 players.

## AWS

Not run yet. This section says how the AWS run is measured, so the numbers can be compared with the local ones above.

### Conditions

- **Deployment:** `.\tasks.ps1 demo-up` (ADR-007): five services on Fargate Spot, ARM64, 0.5 vCPU / 1 GB each,
  session-service 2–3 tasks, RDS db.t4g.micro, Valkey cache.t4g.micro, one Redpanda task, ALB in front of the gateway.
- **Load:** the same `tools/load/game.js` from the dev PC to the ALB URL, ramping 50 → 100 → 200 → 500 players
  (`run-game.ps1 -BaseUrl <alb_url>`). The gateway's per-IP limits must be raised for the run, as locally: one k6 machine
  is one client IP (`demo-up` with `load_test = true` in terraform.tfvars sets the same values as `run-game.ps1` does locally).
- **Network:** k6 crosses the internet to Mumbai, so every client-side latency includes the round trip from the dev
  PC. Measure it once (`curl.exe -w "%{time_connect}"` to `/readyz`) and read the results against it.

### What is measured, and with what

| Question | Source | Cost |
|---|---|---|
| Ack latency, question broadcast, leaderboard push | k6's own metrics (`ack_latency`, `question_broadcast`, `leaderboard_push_latency`), as locally | free |
| Question fan-out across tasks | `question_fanout` works here: the server and k6 clocks are both NTP-synced (locally the WSL clock drifts) | free |
| CPU and memory per service | ECS basic metrics in CloudWatch (`AWS/ECS` CPUUtilization, MemoryUtilization per service, 1-minute) | free |
| Autoscaling | session-service's desired count over the run (ECS service events) | free |
| Which task handled which player | CloudWatch Logs Insights over session-service's JSON logs (below) | $0.0076 per GB scanned |
| Errors, retries, outbox warnings | Logs Insights, `log.level` = WARN/ERROR | same |

**What AWS doesn't show that local runs do:** the Prometheus histograms (answer handling, Hikari acquire time, STOMP
executor busy threads, scoring delay) and the Jaeger traces. Shipping them would mean Container Insights or custom
CloudWatch metrics (billed per metric; our histograms are hundreds of series) or a collector task. That's a cost
choice, not a gap in the code: the services expose exactly the same metrics, and the local runs above are where the
server-side explanation comes from. In AWS the question is narrower: does the deployed system meet the same targets
over a real network, and where does it break first?

### Logs Insights queries

session-service logs one line per answer at DEBUG in AWS (ADR-007), with `sessionId` and `playerId` from the MDC.
Every ECS task writes its own log stream, named after the task id.

Answers of one game per task (two streams = players spread over both session tasks):

```
fields @logStream, sessionId, playerId
| filter message like /^Answer / and sessionId = "<session id>"
| stats count(*) as answers, count_distinct(playerId) as players by @logStream
```

Warnings and errors during the run, by service and message:

```
fields @timestamp, @log, log.level, message
| filter log.level in ["WARN", "ERROR"]
| stats count(*) as lines by @log, message
| sort lines desc
```

One request across services, by trace id (from a k6 failure or a WARN line):

```
fields @timestamp, @log, message
| filter traceId = "<trace id>"
| sort @timestamp asc
```
