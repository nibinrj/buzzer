# Performance

Load tests play whole games with `tools/load/game.js` (k6, STOMP over WebSocket, through the gateway). How the
script works and what each metric means: `docs/study/batch-B.2-k6-game.md` and the top of `game.js`.
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
locally (two drifting clocks; see the B.2 study note). Two `stomp_errors` at 100 players weren't captured (k6
printed them to stderr, which the run didn't keep); no service logged a WARN or ERROR during the ladder.

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

<!-- B.3 fix batch: before/after table goes here. -->
