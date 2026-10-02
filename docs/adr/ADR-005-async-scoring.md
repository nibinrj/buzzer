# ADR-005: Async scoring — at-least-once Kafka delivery, applied once by an idempotent consumer

- **Status:** Accepted
- **Date:** 2026-09-30
- **Deciders:** nibin
- **Related:** ADR-004 (buzz ordering), plan §3.4 and Phase 5, study notes 5.1–5.5, `docs/chaos-scoring.md`

## Context

session-service decides every answer (ADR-004) and writes it to Postgres together with an outbox row. The outbox publisher sends it to Kafka. scoring-service must turn those events into totals, a live top 10 and final results, **in a different process with its own database** (hard rule 4). The requirements:

1. **Zero lost answers.** A lost event is a wrong score forever, and nobody notices.
2. **Zero double-counted answers.** Duplicates will happen: the outbox can send a row twice (4.5), and a consumer rebalance or a crash before the offset commit redelivers records.
3. **Survive scoring-service being down** mid-game, then catch up with no manual step.
4. **One bad record must not stop the game.** A Postgres blip or a malformed record on one partition must not freeze scoring for every other session on it.
5. **No ordering guarantee to lean on.** Answers and lifecycle events are on different topics, several outbox publishers run in parallel, and retries move records out of line. `SessionEnded` can be read before a session's last answers.
6. **Players see the leaderboard move within about a second**, on whichever session-service instance holds their WebSocket.

## Decision

1. **At-least-once delivery.** The listener processes a record, commits its Postgres transaction, and only then calls `ack.acknowledge()` (`AckMode.MANUAL_IMMEDIATE`, `enable-auto-commit: false`). A crash between the two steps causes a redelivery, never a skip.
2. **Idempotency in the same transaction as the score.** `ApplyScoringEvent` runs `INSERT INTO processed_events … ON CONFLICT (event_id) DO NOTHING`. If 0 rows are inserted, the record is a redelivery and the score is left alone. Otherwise, it adds the points with an atomic upsert (`points = player_scores.points + EXCLUDED.points`) and bumps the session's version, all in one commit. "Seen" and "scored" can't disagree.
3. **Order-free scoring.** Points depend only on the event itself: `correct ? max(300, 1000 − 100·(correctRank − 1)) : 0`. The race was already decided by Redis at write time (ADR-004). A total is a sum, so any arrival order gives the same result. The schema has no foreign keys and nullable session columns, so an answer read before its `SessionStarted` still counts.
4. **Non-blocking retries, then a DLT.** `@RetryableTopic(attempts = "3", backOff = 1 s × 2, numPartitions = "3")` forwards a failing record to `<topic>-retry-1000`, then `-retry-2000`, then `<topic>-dlt`. The main partition carries on meanwhile. `UnreadableEventException` (bad JSON, unknown header, unsupported schema version, contradicting fields) is excluded from retries and goes straight to the DLT. Each retry/DLT endpoint has its own consumer group, so a DLT consumer restart doesn't rebalance the main group.
5. **Retry and DLT topics are part of their parent topic**, not new event streams. They don't break hard rule 15 ("Kafka only for three topics").
6. **"Ended" is a status, not a frozen copy.** `SessionEnded` sets `ended_at_ms`. `/final` reads the same live `player_scores`, so an answer that arrives late from a retry topic still lands in the final results.
7. **`ScoreUpdated` without an outbox.** After the commit, scoring-service writes the player's *absolute* total to Redis (`ZADD GT`, rebuilt from Postgres if the key is missing). If the player is now in the top 10, it publishes the whole top 10 with the session's Postgres version, and waits for the broker ack before acking the input. This step also runs for redeliveries, so a failed publish is retried by the retry topic while the score is still counted once.
8. **session-service drops older snapshots.** One consumer group reads `scoring.score-updated`. A Lua compare-and-set on `session:{S}:leaderboard-version` (Redis, shared by all instances) drops older versions, and the existing Redis pub/sub relay fans the push out. Failures there are logged and skipped: the next snapshot replaces it.
9. **`auto-offset-reset: earliest`** for scoring (a first deploy doesn't skip published answers) and `latest` for the session-service leaderboard push (a stale snapshot is worthless).

### Sequence (redraw this by hand for the gate)

```mermaid
sequenceDiagram
    autonumber
    participant SS as session-service
    participant K as Kafka
    participant SC as scoring-service
    participant PG as Postgres scoring_db
    participant R as Redis

    SS->>K: AnswerSubmitted (outbox publisher, key = sessionId)
    K->>SC: record (group scoring-service)
    SC->>PG: BEGIN; processed_events ON CONFLICT DO NOTHING
    alt new event
        SC->>PG: player_scores += points; version += 1
    else redelivery
        Note over SC,PG: 0 rows inserted, score untouched
    end
    SC->>PG: COMMIT
    SC->>R: update_leaderboard.lua (ZADD GT absolute total)
    R-->>SC: position, top 10 (or MISSING → rebuild from PG)
    opt position < 10
        SC->>K: ScoreUpdated{top10, version} (wait for broker ack)
    end
    SC->>K: commit offset (ack)
    Note over SC,K: anything throws → no ack → -retry-1000 → -retry-2000 → -dlt
    K->>SS: ScoreUpdated (one instance)
    SS->>R: accept_leaderboard_version.lua (older? drop)
    SS->>R: PUBLISH relay → every instance → /topic/sessions/S/leaderboard
```

## What goes to the DLT, and who drains it

| Lands in `…-dlt` | Why | What it means |
|---|---|---|
| Unreadable record (poison) | Bad JSON, missing/unknown `eventType`, unsupported `schemaVersion`, missing id, `correct` contradicts `correctRank` | A producer bug. The score is **missing**. |
| Scoring failed 3 times | Postgres down or refusing for more than ~3 s | The score is **missing**. |
| Scoring succeeded, `ScoreUpdated` failed 3 times | Kafka refusing the publish for more than ~3 s | The score is **fine**. Only a push was lost; the next answer's push or `GET /leaderboard` repairs it. |

- **Detection:** the DLT handler counts `buzzer.scoring.events.dead.lettered{topic}`, logs ids and exception types only (never the value or exception message, which can quote data), and acks.
- **Owner:** the service owner (nibin). Nothing reads a DLT automatically, so a record there is a problem until a human looks.
- **Draining:** read the record's `kafka_original-topic` and `kafka_exception-*` headers, fix the cause, then replay the value to the original topic with the same key. Replaying is always safe: `processed_events` skips it if it was actually scored. Discard only a record that can never be valid, and write down why.
- **Today** this is a manual `rpk` procedure. Phase O adds the alert (DLT > 0). A replay tool is a revisit item.

## Alternatives considered

| Option | Why not |
|---|---|
| **Kafka exactly-once** (transactions, `isolation.level=read_committed`) | It makes *Kafka-to-Kafka* processing atomic: consume, produce and commit offsets in one Kafka transaction. Our side effect is a **Postgres** write, which a Kafka transaction can't include. A crash after the Postgres commit and before the Kafka commit still redelivers, so the dedup table is needed anyway. It would add transactional producers and fencing, and remove none of our code. |
| **At-most-once** (commit the offset first, or client auto-commit) | A crash after the commit skips the record forever: a lost answer, a wrong score, and nothing tells anyone. Auto-commit makes it worse: every 5 s it commits whatever `poll()` returned, finished or not. |
| **Dedup in Redis** (`SET NX eventId`) instead of Postgres | Redis and the score would be two stores with no shared transaction. "Marked seen, then crashed before scoring" loses the answer. And Redis can lose data (async replication, restarts). |
| **Catch the duplicate-key exception** instead of `ON CONFLICT DO NOTHING` | In Postgres, any error aborts the whole transaction. The code can't catch it and continue in the same transaction. It would need a rollback, catch outside, then ack: more code for the same result. |
| **JPA read-modify-write** of the total | The retry-topic consumers run alongside the main one. Two threads read 1000 and both write 1000 + x: a lost update. The SQL upsert adds inside Postgres, under its row lock. |
| **Blocking retries** (`DefaultErrorHandler` + back-off) | Keeps order, but the whole partition waits. A 30-second Postgres blip freezes scoring for a third of all games. We don't need the order (decision 3). |
| **Order-dependent scoring** ("the first correct event scoring reads gets 1000") | Retries, rebalances and parallel outbox publishers would change who won, and the leaderboard would disagree with `session_db`. Scoring must add up decisions, not make them. |
| **Freeze `final_results` on `SessionEnded`** (with or without a grace period) | `SessionEnded` can overtake answers still in a retry topic. The frozen copy would be wrong, and a grace period only makes it wrong less often. |
| **Outbox for `ScoreUpdated`** | It's a snapshot, not a fact: the next one replaces it, and it can be recomputed from Postgres at any time. The unacked input record already gives us a retry. |
| **`ZINCRBY`** for the Redis leaderboard | Not idempotent: the publish step runs again on redelivery and would add the points twice in Redis. Absolute totals with `ZADD GT` can be written any number of times, in any order. |
| **Version from Redis `INCR`** | It restarts at 1 when Redis loses its data, and session-service would then drop every update until the end of the game. The version lives in Postgres, in the score's own transaction. |

## Consequences

**Positive**
- Every answer is scored exactly once in effect: duplicates enter at two places (outbox resend, consumer redelivery) and are removed at one (`processed_events`, inside the scoring transaction).
- scoring-service can be down for a whole game. The answers wait in the outbox and the topic, and it catches up from its committed offset. Proven by `docs/chaos-scoring.md`.
- A slow or failing record costs its own session a few seconds, not a whole partition.
- Any number of scoring tasks (up to the 3 partitions) can run. Each session's events meet in one consumer because they're keyed by `sessionId`.
- The live leaderboard heals itself: a lost push, a lost Redis key or a stale snapshot are all fixed by the next answer.

**Negative, accepted on purpose**
- **Scoring is eventually consistent.** The leaderboard is typically behind the ack by one outbox tick (300 ms) plus a Kafka hop. `/final` can change after `SessionEnded` if a record is still retrying.
- **A DLT record is a missing score until a human acts.** At 1 s + 2 s back-off, a Postgres outage longer than ~3 s sends everything in flight to the DLT. Replay is safe but manual.
- **The DLT counter can't tell a missing score from a missing push** (the table above). Someone draining it has to check the headers.
- **`processed_events` grows forever**: a few hundred rows per game. Rows only need to outlive Kafka's retention.
- **The partition count is fixed once live.** Changing it moves a live session's key to a different partition. 3 partitions caps scoring parallelism at 3 consumers.
- **Retry topics give up Kafka's order.** That's harmless only because of decision 3. Any future order-dependent logic in scoring would be wrong.
- **session-service drops leaderboard failures silently**, apart from a log line. Acceptable for a snapshot, not for anything else.

## Verification

| Claim | Evidence |
|---|---|
| A redelivered answer is scored once | `ScoringEventListenerTest.aRedeliveredAnswerIsScoredOnce` (Testcontainers Redpanda + Postgres; the marker trick) |
| A failed write is retried on `-retry-1000` and scored exactly once | `ScoringEventListenerTest.anAnswerWhoseWriteFailsOnceIsScoredOnItsRetryExactlyOnce` |
| Poison skips the retries, reaches the DLT, is counted, and its offset moves on | `ScoringEventListenerTest.aPoisonRecordSkipsTheRetriesReachesTheDltIsCountedAndItsOffsetMovesOn` |
| Each retry topic and the DLT has its own consumer group | `ScoringEventListenerTest.eachRetryTopicAndTheDltIsReadByAConsumerGroupOfItsOwn` (red without the fix) |
| A failed `ScoreUpdated` is sent again from the retry topic while the score counts once | `ScoringEventListenerTest.aScoreUpdateThatFailsToPublishIsSentAgainFromTheRetryTopicWhileTheScoreCountsOnce` (red when publishing only new scores) |
| Order doesn't matter: answer before start, end before start, answer after end | `…anAnswerBeforeItsSessionStartsStillCountsAndTheSessionEnds`, `…anEndReadBeforeItsStartKeepsBoth`, `…anAnswerReadAfterTheEndStillCountsInTheFinalResults` |
| Formula, floor at rank 8, no overflow, contradictions refused | `PointsTest`, `EventReaderTest` |
| `ZADD GT` never lowers a total; a lost Redis key is rebuilt from Postgres | `RedisLeaderboardTest.aLowerTotalNeverReplacesAHigherOne` (red without `GT`), `LeaderboardFlowTest.aLostRedisLeaderboardIsRebuiltFromPostgresOnTheNextAnswer` |
| Only top-10 changes are published | `LeaderboardFlowTest` (red with the check disabled) |
| session-service drops older snapshots, compared as numbers | `LeaderboardPushTest.aLeaderboardOlderThanTheLastPushedOneIsNotPushed`, `RedisLeaderboardVersionsTest` |
| Kill scoring mid-game, restart: no loss, no duplicate | `docs/chaos-scoring.md`, run 2026-09-30: lag 6 → 0 after restart, 23 answers, SQL comparison `MATCH`, DLTs empty |

## Revisit when

- **The DLT is ever non-empty in practice.** Build a replay tool (read the DLT, re-send to the original topic with the same key) rather than a longer `rpk` procedure.
- **Postgres failovers take longer than the retry budget** (for example, RDS Multi-AZ failover, 60–120 s, Phase 7). Lengthen the back-off or add a blocking retry before the retry topics, so a failover doesn't fill the DLT.
- **`processed_events` gets large.** Add a cleanup job that deletes rows older than Kafka's retention plus a margin.
- **Phase B/9 shows scoring lagging** in a buzz storm. More partitions only help new topics, so decide before going live. Measure first.
- **Scoring needs order-dependent logic** (for example, streak bonuses that depend on the previous answer). Then the retry topics and parallel outbox publishers become a correctness problem: redesign before adding it.
