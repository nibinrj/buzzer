# ADR-004: Buzz ordering — one atomic Redis Lua script decides, Postgres records

- **Status:** Accepted
- **Date:** 2026-09-29
- **Deciders:** nibin
- **Related:** ADR-003 (real-time transport), ADR-005 (async scoring)

## Context

When a question opens, up to **500 players** answer within a few milliseconds of each other. Their answers can reach **different session-service tasks** (ADR-003). The server must produce one result everyone agrees on:

1. **One answer per player per question.** A retry, a double click or a second tab must not count twice.
2. **Nothing after the deadline**, and nothing after the host reveals the answer.
3. **One global arrival order** across all instances: no ties, no gaps, the same for everyone.
4. **A rank among correct answers** (1st correct, 2nd correct…), because points depend on it (ADR-005).
5. **Fast.** The player's ack must come back in milliseconds. The race must not queue 200 answers behind each other for hundreds of ms, or the p99 collapses.
6. **App-server clocks can't be trusted to agree.** Each ECS task has its own clock, off by about a millisecond from the others, and 200 answers can arrive inside 10 ms.

## Decision

1. **A single Redis Lua script** (`lua/submit_answer.lua`) decides every answer. Redis runs one command at a time, and a script is one command, so its checks and writes can't interleave with any other answer, from any instance.
2. **Order comes from counting, not from clocks.** Inside the script:
   - `seq = INCR buzz:{S}:Q:seq` gives the place in line: 1, 2, 3… with no ties.
   - `correctRank = INCR buzz:{S}:Q:correct`, only for correct answers, gives the rank among correct ones.
   - The sorted set `buzz:{S}:Q` (member = player, score = seq) is the "already answered" record.
3. **Every check before the first write.** Redis has no rollback. The script checks, in this order: session running → right question → question open → not a duplicate → not late. Only then does it write. A rejected answer leaves no trace and takes no number, so accepted seqs stay gapless.
4. **The deadline uses Redis's own clock on both sides.** `RunSession` sets the deadline from Redis `TIME` + time limit; the script compares against `TIME` on the same primary. The deadline is inclusive.
5. **`DUPLICATE` returns the original seq**, and is checked before `LATE`, so a retry learns that its first attempt counted.
6. **The app decides correctness, not the client.** Before calling the script, session-service reads the frozen question snapshot in Postgres and passes `"1"`/`"0"`. The client only says "I picked option 2". The ack says nothing about correctness, so no one knows before the reveal.
7. **Postgres is the record; Redis is the referee.** After `ACCEPTED`, the answer row (with its seq, rank and Redis time) and its `AnswerSubmitted` outbox row are written in **one** transaction. Unique constraints `(session, question, player)` and `(session, question, seq)` back up the script.
8. **Reveal closes the question in the same Redis hash** (`questionOpen = "0"`). Redis's single thread orders reveal against answers: a script that ran before the close counts, one after gets `CLOSED`.
9. **All of a session's keys share a hash tag** (`{<sessionId>}`), so the script's keys sit in one Redis Cluster slot.

### Sequence (redraw this by hand for the gate)

```mermaid
sequenceDiagram
    autonumber
    participant P as Player (browser)
    participant S as session-service (any task)
    participant PG as Postgres session_db
    participant R as Redis (primary)
    participant K as Kafka

    P->>S: STOMP SEND /app/sessions/S/answer {questionId, optionId}
    S->>S: interceptor: is sender a PLAYER of S?
    S->>PG: read player + frozen question → index, correct?
    S->>R: EVALSHA submit_answer (4 keys, player, index, correct, ttl)
    Note over R: one step, nothing interleaves:<br/>running? right question? open?<br/>ZSCORE → DUPLICATE?<br/>TIME > deadline → LATE?<br/>INCR seq, ZADD, INCR correct
    R-->>S: {ACCEPTED, seq, correctRank, answeredAtMs}
    S->>PG: BEGIN; INSERT answer; INSERT outbox; COMMIT
    S-->>P: /user/queue/answer-ack {accepted, seq} (this tab only)
    Note over S,K: later, the outbox publisher (every 300 ms)
    S->>K: AnswerSubmitted → session.answer-submitted (key = sessionId)
```

## Alternatives considered

| Option | Why not |
|---|---|
| **App-server timestamps** (`System.currentTimeMillis()` as the sorted-set score) | Different tasks have different clocks: 1 ms of skew reorders a large share of 200 answers in 10 ms, so *which task you landed on* picks the winner. Stamping and writing aren't one step, wall clocks jump under NTP, and equal millisecond scores are tie-broken by the player's UUID bytes. |
| **Postgres row lock** (`SELECT … FOR UPDATE` on the session or question) | Durable and simple, but all 200 answers queue on **one row**, each holding it for a round trip plus a commit with a WAL fsync (~1–5 ms). The 200th waits hundreds of ms, each waiter holds one of 10 pooled connections, and the lock is shared with joins and host commands. |
| **Postgres without a lock** (unique constraint + `SEQUENCE` + `now()`) | `nextval` is handed out at call time, not commit time, so seq order isn't commit order, and rollbacks leave gaps. `correctRank` needs a count of committed correct answers, which brings the hot row back. (We keep the unique constraint as a safety net.) |
| **Redlock / a distributed lock** | Solves mutual exclusion, but the problem is **ordering**: a counter is still needed. It adds round trips across N nodes, a holder paused past its lease can write alongside the next holder, and the fix for that (fencing tokens) is just an increasing number from one authority, which is what `INCR` already is. |
| **Separate Redis commands from Java** (`ZSCORE`, then `INCR`, `ZADD`, `INCR`) | Each command is atomic alone, but the sequence isn't. Measured in 4.4: one player sending 50 times was accepted **50 times**, and the first correct answer by seq got **rank 26**. |
| **`MULTI`/`EXEC`, or `WATCH` + retry** | `MULTI` can't branch on a value it read ("if already answered, stop"). `WATCH` works optimistically, but under 200-way contention almost every attempt retries. |
| **Redis Functions** (`FUNCTION LOAD` + `FCALL`) | Named, persisted and replicated, so no `NOSCRIPT` after a restart. A good upgrade, but scripts are simpler, and Spring Data Redis already falls back from `EVALSHA` to `EVAL` on `NOSCRIPT`. |

## Consequences

**Positive**
- **One round trip** to decide an answer. No lock to lease, lose or leave held by a dead process.
- Any number of session-service tasks can take answers for the same question; Redis's single thread is the one line they all stand in.
- Seqs are unique and gapless; ranks are contiguous. Scoring can use `correctRank` straight from the event and doesn't depend on the order events arrive in (ADR-005).
- A retry is harmless: `DUPLICATE` with the original seq.

**Negative, accepted on purpose**
- **"Arrival order" is the order scripts ran on the Redis primary**, not "who clicked first". The player's network, the gateway, and the Postgres correctness read all happen before it. It is fair in the sense that one authority decides and everyone sees the same result. Nobody can know "who clicked first" without trusting client clocks, which are trivial to cheat.
- **Redis → Postgres gap.** If Redis accepts and the Postgres write then fails, Redis counts the answer but there is no row, and a retry is a `DUPLICATE`. The answer is lost for scoring. It is logged (ids only) and the player is acked `NOT_RECORDED`, so they are told the truth. A rare failure, documented rather than engineered away.
- **Redis durability.** Replication is asynchronous. If the primary dies right after replying, the accepted buzz can vanish from Redis. The Postgres row is the record, so this only matters for answers still between steps 5 and 6 of the diagram.
- **A slow script would pause all of Redis** (live state, rate limiting, the pub/sub relay). Ours runs a handful of O(1)/O(log n) commands: microseconds.
- **Deadline weaknesses:** a clock step on the Redis host, or a failover to a primary with a slightly different clock, moves the deadline by a few ms. Players also get slightly less than the full limit, because the question is broadcast after the deadline is set. Small next to limits measured in seconds, and the same for everyone on one question.
- **All answers of one session go to one Redis node** (one hash slot). A few hundred commands against a server doing ~100k/s.

## Verification

| Claim | Evidence |
|---|---|
| 200 players released together (spread 1–6 ms) through 4 independent connections get seq exactly 1..200, one rank 1, contiguous ranks | `SubmitAnswerConcurrencyTest.twoHundredPlayersAtOnce…` — **20 of 20 runs passed** on 2026-09-28 |
| One player sending 50 times at once is accepted exactly once | `SubmitAnswerConcurrencyTest.onePlayerSendingFiftyTimesAtOnce…` |
| A wave after the deadline is all `LATE` and takes no numbers | `SubmitAnswerConcurrencyTest.aWaveAfterTheDeadline…` |
| The tests catch the bug they exist for | Red run against separate Java commands: 50/50 accepted, first correct had rank 26 |
| Duplicate/late/closed/wrong-question/not-running rules, TTLs, `NOSCRIPT` recovery, one cluster slot | `RedisAnswerRegistryTest` (14 tests, Testcontainers Redis) |
| Correctness comes from the snapshot; refusals write nothing; Postgres failure → `NOT_RECORDED` | `SubmitAnswerTest` |
| The answer row and its event commit together; the event reaches Kafka exactly once in the test | `OutboxTest`, `OutboxPublisherTest` (Testcontainers Postgres + Redpanda) |
| Ack goes to the sending tab only; reveal broadcasts the correct option | `SessionWebSocketTest` |

## Revisit when

- **Load tests show Redis CPU or latency** as the bottleneck during a buzz storm. Then measure the script's cost first; move to Redis Functions, or split sessions across shards (the hash tag already allows it).
- **The Redis → Postgres gap shows up in practice** (`NOT_RECORDED` in logs). Then make the Postgres write retryable, or write a reconciliation job that compares `buzz:{S}:Q` to the answer rows before the question's keys expire.
- **Losing an acknowledged buzz on failover becomes unacceptable** (for example, prizes). Then use `WAIT 1 <ms>` after the script (wait for a replica), accepting the extra latency.
- **Players need "who clicked first"** in a legal or competitive sense. Then this design is the wrong tool: you need signed client timestamps and a trust model, a different product.
