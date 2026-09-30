# Chaos check: kill scoring-service mid-game

**Goal (plan, Phase 5 "done when"):** kill `scoring-service` in the middle of a game, restart it, and the final scores are exactly right: **zero lost answers, zero double-counted ones.**

**What this proves**

- **No loss while scoring is down.** Answers keep being accepted, sit in Kafka, and are scored after the restart. Behind this: the outbox (4.5), and committed offsets (5.2).
- **No double counting across a crash and restart.** Behind this: `processed_events` (5.2).
- **The live leaderboard catches up by itself** after the restart. Behind this: 5.3 and 5.4.
- **An independent recomputation from `session_db` agrees with `scoring_db`**, row for row.

**What it doesn't prove:** that a crash landing *between* the score's commit and the Kafka ack is harmless. That window is a few milliseconds per record, so a manual kill almost never hits it. `ScoringEventListenerTest` covers it deterministically (a redelivered event is scored once).

Every command is PowerShell 7, run from the repo root (`D:\dev\buzzer`). About 20 minutes.

---

## 0. Once, before the first run

| Need | Check |
|---|---|
| Docker Desktop running | `docker info` answers |
| `.env` with real passwords | `Copy-Item .env.example .env`, then edit (never commit it) |
| JWT keys in `.secrets\` | `.\tasks.ps1 keys` (never overwrites) |
| `JAVA_HOME` set (for `jwebserver`) | `& "$env:JAVA_HOME\bin\java.exe" -version` shows 21+ |

## 1. Start everything

Seven terminals: the stack, five services, and a tiny web server for the test client. Start the services **one after another**: each `run` first installs the shared modules into `~\.m2`, and two installs at once can collide.

**Terminal 1: the stack** (returns when all three are healthy)
```powershell
.\tasks.ps1 up
```

**Terminals 2–6: one service each, in this order.** Wait for `Started …Application` before starting the next.
```powershell
.\tasks.ps1 run -Svc identity-service
```
```powershell
.\tasks.ps1 run -Svc quiz-service
```
```powershell
.\tasks.ps1 run -Svc session-service
```
```powershell
.\tasks.ps1 run -Svc scoring-service
```
```powershell
.\tasks.ps1 run -Svc gateway
```

**Terminal 7: serve the test client** (`file://` sends `Origin: null`, which the gateway refuses)
```powershell
& "$env:JAVA_HOME\bin\jwebserver.exe" -d (Resolve-Path .\tools).Path -p 5173
```

**Any free terminal: all five UP?**
```powershell
.\tasks.ps1 health
```
```
Service          Port Status
-------          ---- ------
gateway          8080 UP
identity-service 8081 UP
quiz-service     8082 UP
session-service  8083 UP
scoring-service  8084 UP
```

## 2. Prepare a game

```powershell
.\tools\http\chaos-game.ps1
```
```
Host email : chaos-44fd4fa5@test.dev
Password   : on your clipboard (paste it into the test client; it is not shown here)
Quiz id    : 05dda5ea-f5fd-4f91-b09e-c831a048512e  (8 questions, status PUBLISHED)
```

A fresh throwaway host every run, with a random password that only ever sits on your clipboard. The quiz has 8 questions, 4 options each, 30 s per question. The correct options are A, B, C, D, A, B, C, D (Paris, Tokyo, Rome, Ottawa, Canberra, Brasilia, Nairobi, Oslo).

## 3. Open the test client: 1 host + 3 players

Open `http://localhost:5173/test-client.html` in **four tabs**. Each tab is its own user: tokens live in the page's memory, not in cookies.

| Tab | Do this |
|---|---|
| 1, host | Paste the email, **Ctrl+V** the password, *Log in*. Paste the quiz id, *Create session*. Note the room code and the **Session id** shown under it. |
| 2 | Role **Player**, nickname `Ada`, *Get guest token*, room code, *Join* |
| 3 | Same, `Bob` |
| 4 | Same, `Cid` |

Each tab's *Players* list should show Ada, Bob, Cid (tabs that joined early show them once the first leaderboard arrives).

**How a question goes:** the host clicks *start* (question 1) or *next*. Players click an option: the note under it says `Accepted: #n in line.` Clicking again says `Refused: DUPLICATE (your first answer was #n)`. The host clicks *reveal*: the correct option turns green in every tab. Only then does the host click *next*.

**Write down who answered what, in which order.** Points per question: 1st correct = 1000, 2nd = 900, … never below 300; wrong or no answer = 0.

## 4. Questions 1–2, with scoring running

Play two questions with different orders and at least one wrong answer. After each answer the **Leaderboard** in every tab updates, and its version (`v1`, `v2`, …) goes up by one per accepted answer.

## 5. Kill scoring-service, hard

Between two questions (after a *reveal*), in any free terminal:

```powershell
$scoringPid = (Get-NetTCPConnection -LocalPort 8084 -State Listen).OwningProcess
Get-Process -Id $scoringPid
Stop-Process -Id $scoringPid -Force
```

`-Force` kills the JVM on the spot: no graceful shutdown, no final offset commit, just like a container that runs out of memory or loses its host. **Ctrl+C would be a clean shutdown and prove less.** Terminal 5 (scoring) now ends with a Maven error, which is expected.

Confirm it's gone:
```powershell
.\tasks.ps1 health
```
scoring-service is no longer UP.

## 6. Questions 3–4, with scoring down

Play two more questions. Expect:

- **Players still get `Accepted: #n in line.`** Answering doesn't depend on scoring. That's the point of making it asynchronous (plan §3.2).
- **The leaderboard freezes** at the last version from step 4.
- **The answers wait in Kafka.** After question 4:

```powershell
docker compose exec redpanda rpk group describe scoring-service
```
```
STATE                  Empty
MEMBERS                0
TOTAL-LAG              6
TOPIC                     PARTITION  CURRENT-OFFSET  LOG-START-OFFSET  LOG-END-OFFSET  LAG
session.answer-submitted  0          6               0                 12              6
```

No members, and 6 records (2 questions × 3 players) not yet read by the `scoring-service` group. (All of one session's records sit on one partition: they're keyed by session id.)

## 7. Restart scoring-service

In terminal 5:
```powershell
.\tasks.ps1 run -Svc scoring-service
```

When it's up, give it a few seconds, then:
```powershell
docker compose exec redpanda rpk group describe scoring-service
```
```
STATE                  Stable
MEMBERS                1
TOTAL-LAG              0
```

It started from its last committed offset and scored the six waiting answers. **Every tab's leaderboard jumps to the new version by itself**: scoring published a ScoreUpdated for each answer it caught up on, and session-service pushed them (older versions are skipped).

## 8. Finish the game

Play questions 5–8. Include:

- one player who **doesn't answer** a question (so that player has 7 answers, not 8),
- one **double click** (the second click must be refused as DUPLICATE and not counted).

After question 8's *reveal*, the host clicks **end**. Status becomes ENDED in every tab.

## 9. Verify the scores

Copy the **Session** id from the host tab. (Or take the newest session: `docker compose exec -T postgres psql -U session_app -d session_db -At -c "SELECT id FROM sessions ORDER BY created_at DESC LIMIT 1;"`.)

`[guid]` checks it is a real id before it goes into any SQL:
```powershell
$sessionId = [guid]'fabde228-fe30-4b85-bbd7-92c53e6b06e0'
```

`psql` runs inside the postgres container as the service's own role. The container trusts local connections, so no password is needed. `-T` because the output is captured, not shown on a terminal.

### 9a. Expected (from `session_db.answers`) vs actual (`scoring_db.player_scores`)

`session_db` holds every accepted answer, with the rank Redis gave it. The first query applies the scoring formula to those rows **independently of scoring-service**. The second reads what scoring-service computed from Kafka. They are two databases, and Postgres can't join across databases without an extension, so PowerShell compares the two outputs.

```powershell
$expectedSql = @"
SELECT a.player_id,
       SUM(CASE WHEN a.correct THEN GREATEST(300, 1000 - 100 * (a.correct_rank - 1)) ELSE 0 END) AS points,
       COUNT(*) AS answers,
       COUNT(*) FILTER (WHERE a.correct) AS correct_answers
FROM answers a
WHERE a.session_id = '$sessionId'
GROUP BY a.player_id
ORDER BY points DESC, a.player_id DESC;
"@
$actualSql = @"
SELECT player_id, points, answers, correct_answers
FROM player_scores
WHERE session_id = '$sessionId'
ORDER BY points DESC, player_id DESC;
"@
$expected = docker compose exec -T postgres psql -U session_app -d session_db -At -F ',' -c $expectedSql
$actual = docker compose exec -T postgres psql -U scoring_app -d scoring_db -At -F ',' -c $actualSql
'expected:'; $expected; 'actual:'; $actual
$diff = Compare-Object -ReferenceObject @($expected) -DifferenceObject @($actual)
if ($expected.Count -gt 0 -and -not $diff) { 'MATCH' } else { 'MISMATCH'; $diff }
```
```
expected:
419d906a-90b1-4c42-b184-6a29dd5f362a,5600,8,6
c2346759-63b9-43c4-a765-2d1272421f96,5400,7,6
6fa0da47-9a74-4fe7-9751-456b3c30de69,4900,8,5
actual:
419d906a-90b1-4c42-b184-6a29dd5f362a,5600,8,6
c2346759-63b9-43c4-a765-2d1272421f96,5400,7,6
6fa0da47-9a74-4fe7-9751-456b3c30de69,4900,8,5
MATCH
```

Columns: player id, points, answers, correct answers. `answers` catches double counting that happens to leave points unchanged (a wrong answer counted twice adds 0 points but 1 answer).

### 9b. The same, readable, with names and every question

```powershell
docker compose exec -T postgres psql -U session_app -d session_db -c @"
SELECT p.display_name AS player,
       SUM(CASE WHEN a.correct THEN GREATEST(300, 1000 - 100 * (a.correct_rank - 1)) ELSE 0 END) AS points,
       COUNT(*) AS answers,
       string_agg(CASE WHEN a.correct THEN '#' || a.correct_rank ELSE 'x' END, ' ' ORDER BY q.position) AS per_question
FROM answers a
JOIN players p ON p.id = a.player_id
JOIN session_questions q ON q.session_id = a.session_id AND q.question_id = a.question_id
WHERE a.session_id = '$sessionId'
GROUP BY p.display_name, a.player_id
ORDER BY points DESC, a.player_id DESC;
"@
```
```
 player | points | answers |     per_question
--------+--------+---------+-----------------------
 Cid    |   5600 |       8 | x #1 #2 #1 x #1 #3 #2
 Ada    |   5400 |       7 | #2 #2 #3 #2 #2 #1 x
 Bob    |   4900 |       8 | #1 x #1 x #1 x #2 #1
```

`#n` = correct, n-th correct on that question; `x` = wrong. A missing question isn't listed (Ada skipped question 6). Check it against what you wrote down in step 3. It should also equal the final leaderboard in the tabs.

### 9c. Nothing left behind

```powershell
docker compose exec -T postgres psql -U scoring_app -d scoring_db -c "SELECT question_count, ended_at_ms IS NOT NULL AS ended, version FROM scoring_sessions WHERE session_id = '$sessionId';"
docker compose exec -T postgres psql -U session_app -d session_db -At -c "SELECT 'unsent outbox rows: ' || count(*) FROM outbox WHERE sent_at IS NULL;"
docker compose exec redpanda rpk group describe scoring-service
docker compose exec redpanda rpk topic describe session.answer-submitted-dlt -p
docker compose exec redpanda rpk topic describe session.lifecycle-dlt -p
```

| Check | Expected | Why |
|---|---|---|
| `ended` | `t` | SessionEnded was consumed |
| `version` | = the total number of answers (23 in the run below) | bumped once per scored answer, and a redelivery doesn't bump it |
| unsent outbox rows | `0` | session-service published everything |
| `TOTAL-LAG` | `0` | scoring read everything |
| both DLTs, every partition | `HIGH-WATERMARK 0` | nothing failed for good |

## 10. Result of the first run (2026-09-30)

1 host and 3 players. scoring-service was killed with `Stop-Process -Force` after question 2 and restarted after question 4. Lag was 6 while it was down, and 0 within seconds of the restart.

| Player | Answers | Points: by hand = SQL expected = scoring_db |
|---|---|---|
| Cid | 8 | 5600 |
| Ada | 7 (skipped Q6; one DUPLICATE refused on Q1) | 5400 |
| Bob | 8 | 4900 |

`MATCH`, version 23 = 23 answers, 0 unsent outbox rows, lag 0, both DLTs empty.

## If it doesn't match

| Symptom | Look at |
|---|---|
| A player's row is missing in `actual`, or lag > 0 | Is scoring-service up and in the group (`MEMBERS 1`)? Its terminal log. |
| A DLT has records | scoring-service's log line `Dead-lettered: … exception=… cause=…` names the failure. Those answers are **not** scored: that's a real bug. |
| `answers` higher in `actual` than `expected` | Double counting: `processed_events` failed its job. Stop and investigate. |
| Unsent outbox rows > 0 | session-service can't reach Kafka; its log says `not acknowledged by Kafka`. |
| A player shows fewer answers than they clicked | Only if one was refused (DUPLICATE, LATE, CLOSED), or acked `NOT_RECORDED`: Redis accepted it but Postgres failed. That's a known gap (ADR-004), and then it's missing on **both** sides, so the comparison still matches. |

## Clean up

Ctrl+C in terminals 2–7. Then:
```powershell
.\tasks.ps1 down
```
Data volumes are kept; `.\tasks.ps1 nuke` deletes them (it asks first).
