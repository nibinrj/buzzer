# Chaos check on Kubernetes: delete pods mid-game

**Goal:** play a game through the cluster's edge (`127.0.0.1:8000`) and delete pods while it runs:
- a **session-service** pod: its players reconnect, the other pod's players don't notice
- a **scoring-service** pod: the other one takes over its Kafka partitions

The final scores must still be exactly right: **`MATCH`**, the same SQL check as [docs/chaos-scoring.md](chaos-scoring.md).

**What this proves**
- **A deleted pod is replaced by Kubernetes**, and the game carries on. Behind this: the Deployment, the probes, preStop + graceful shutdown.
- **WebSocket players survive losing their pod.** The test client reconnects and redraws from `/state`, and live state is in Redis, not in the pod (3.3, 3.5).
- **Players on another pod are unaffected**, because every broadcast goes through the Redis relay.
- **The Kafka consumer group hands partitions over** when a scoring pod leaves, and back when the replacement joins. No answer is lost or counted twice.
- **Scaling by hand works both ways.** A new session pod gets broadcasts as soon as it's ready, and removing one moves its players.

**What it doesn't prove**
- **Losing a node:** the cluster has one.
- **A crash between the score's commit and the Kafka ack:** too narrow to hit by hand, and covered by `ScoringEventListenerTest`.
- **Automatic scaling:** see §12.

**Why the steps move memory around:** the cluster runs **one copy of every service** to leave room for Prometheus and Grafana, with ~0.45 GiB of allocatable memory free. A second copy of a 640Mi JVM doesn't fit, so each step **borrows another service's slot**:
- §4: quiz-service is idle once the session exists, so its slot goes to a second session pod.
- §8: that slot then goes to a second scoring pod.
- §11: `kubectl apply -k` puts the declared one-of-each back.

If a scale-up doesn't fit, the new pod stays `Pending` (`Insufficient memory`). It doesn't take the node down (the memory reservation in kind-config.yaml).

Every command is PowerShell 7, run from the repo root (`D:\dev\buzzer`). About 25 minutes.

---

## 0. Once, before the first run

| Need | Check |
|---|---|
| Images built | `.\tasks.ps1 images` |
| The cluster up | `.\tasks.ps1 kind-up` ends with `Cluster 'buzzer' is up` |
| Compose stack **stopped** | `.\tasks.ps1 down`: Docker's memory goes to the cluster |
| `JAVA_HOME` set (for `jwebserver`) | `& "$env:JAVA_HOME\bin\java.exe" -version` shows 21+ |

## 1. Terminals

**Terminal 1: dashboards** (Grafana `127.0.0.1:3001`, Prometheus `127.0.0.1:9091`, until Ctrl+C)
```powershell
.\tasks.ps1 kind-obs
```
In Grafana (admin / `GRAFANA_ADMIN_PASSWORD` from `.env`), open **Buzzer → Buzzer Game** and **Buzzer Service RED**, and set the time range to *Last 15 minutes*.

**Terminal 2: watch the pods, and keep a copy** (the batch's "save the output of each kill")
```powershell
kubectl --context kind-buzzer -n buzzer get pods -w | Tee-Object -FilePath "$env:TEMP\chaos-k8s-pods.log"
```

**Terminal 3: serve the test client** (`file://` sends `Origin: null`, which the gateway refuses)
```powershell
& "$env:JAVA_HOME\bin\jwebserver.exe" -d (Resolve-Path .\tools).Path -p 5173
```

**Terminal 4: commands.** Every `kubectl` below starts with this, so it can't act on another cluster:
```powershell
$k = @('--context', 'kind-buzzer', '-n', 'buzzer')
```

## 2. Prepare a game

```powershell
.\tools\http\chaos-game.ps1 -BaseUrl http://127.0.0.1:8000
```
Same as chaos-scoring §2: a throwaway host whose password goes to your clipboard, and an 8-question quiz. **`127.0.0.1`, not `localhost`**: the cluster's port is IPv4-only, and `localhost` costs ~2 s per request from PowerShell.

## 3. The host creates the session

Open `http://localhost:5173/test-client.html`. In the page, set **Base URL** to `http://127.0.0.1:8000`; it's remembered per browser. CORS checks the page's origin (`localhost:5173`), so that's fine.

Host tab: paste the email, **Ctrl+V** the password, *Log in*, paste the quiz id, *Create session*. Note the room code and the **Session id**.

## 4. Make room for a second session-service pod

The session already holds its snapshot of the quiz, so **quiz-service isn't needed again until someone edits a quiz**. Its slot goes to a second session pod:

```powershell
kubectl @k scale deployment/quiz-service --replicas=0
kubectl @k wait --for=delete pod -l app.kubernetes.io/name=quiz-service --timeout=120s
kubectl @k scale deployment/session-service --replicas=2
kubectl @k rollout status deployment/session-service --timeout=300s
```
Terminal 2 shows quiz-service `Terminating`, then a new `session-service-…` going `0/1` → `1/1` (~15 s in the dry run).

`wait --for=delete` first: the scheduler counts a terminating pod's memory until it's gone, and the second session pod would otherwise be `Pending` for a moment.

📸 **Buzzer Service RED → Up:** quiz-service gone, two session-service rows (`instance` = pod name).

## 5. Players join: 4 tabs, spread over both pods

Four more tabs: role **Player**, nicknames `Ada`, `Bob`, `Cid`, `Dee`, *Get guest token*, the room code, *Join*.

**Which pod is each WebSocket on?** Kubernetes picks a session pod per **connection**, not per player. Open this in Prometheus:

`http://127.0.0.1:9091/graph?g0.expr=buzzer_websocket_connections&g0.tab=1`

It shows one row per pod (`instance`), with its connection count (host included). **Both pods need at least one.** If every connection landed on one pod, open one more player tab (`Eve`); a new connection is a new pick. (Don't reload a tab: tokens live in the page's memory, so a reload logs it out.)

## 6. Questions 1–2

Play two questions, as in chaos-scoring §3–4: different orders, at least one wrong answer. **Write down who answered what, in which order** (1st correct = 1000, 2nd = 900, … never below 300).

## 7. Delete a session-service pod mid-game

Between two questions (after a *reveal*), delete the pod with **more** connections (from the Prometheus table):
```powershell
kubectl @k get pods -l app.kubernetes.io/name=session-service
kubectl @k delete pod <that pod's name>
```

This is what a rollout, a node drain or an eviction does. Expect:

| Where | What |
|---|---|
| The tabs on that pod | *reconnecting…* for ~5 s (the client's `reconnectDelay`), then connected again, redrawn from `/state`: status, current question, roster |
| The other tabs | Nothing. They stay connected and keep getting every broadcast |
| Terminal 2 | the old pod `Terminating` (5 s preStop + graceful shutdown), a replacement `0/1` → `1/1` |
| 📸 Buzzer Game → **Connected WebSockets** | dips while they're away, back to the full count after the reconnect |
| Prometheus table (§5) | all connections on the surviving pod; the replacement starts at 0 (existing WebSockets don't move to a new pod, only new ones land there) |

Why reconnecting players land on the **surviving** pod: during the ~15 s the replacement starts, it isn't *ready*, so it isn't in the Service's endpoints.

Play **question 3**. Both groups answer, and every tab gets the reveal.

## 8. Make room for a second scoring-service pod

Back to one session pod. **This is a scale-in:** the players on the pod that goes get exactly §7's reconnect.
```powershell
kubectl @k scale deployment/session-service --replicas=1
# Until the removed pod is really gone: a Terminating pod still reports phase Running, so count pods instead.
while (@(kubectl @k get pods -l app.kubernetes.io/name=session-service --no-headers).Count -gt 1) { Start-Sleep 2 }
kubectl @k scale deployment/scoring-service --replicas=2
kubectl @k rollout status deployment/scoring-service --timeout=300s
```
Why wait: the scheduler counts the terminating pod's memory until it's gone, and the second scoring pod would sit `Pending` until then.

Now the group has two members:
```powershell
kubectl @k exec redpanda-0 -- rpk group describe scoring-service
```
```
STATE                  Stable
BALANCER               range
MEMBERS                2
TOTAL-LAG              0
TOPIC                     PARTITION  CURRENT-OFFSET  ...  LAG  MEMBER-ID              CLIENT-ID                   HOST
session.answer-submitted  0          -                    -    consumer-...-3fa96826  consumer-scoring-service-4  10.244.0.27
session.answer-submitted  1          -                    -    consumer-...-3fa96826  consumer-scoring-service-4  10.244.0.27
session.answer-submitted  2          6                    0    consumer-...-9777de5c  consumer-scoring-service-4  10.244.0.25
```
- **Your game's partition** is the one with offsets (`CURRENT-OFFSET` not `-`): one session's records all go to one partition, keyed by session id.
- **`HOST`** is the owning pod's IP.
- The `range` balancer gives each member a contiguous block of partitions per topic: here 0–1 to one pod, 2 to the other.

## 9. Delete the scoring pod that owns your game's partition

Find it by the IP in `HOST`, delete it, and sample the group every 2 s for 30 s:
```powershell
$partition = 2   # your game's partition, from §8
$ip = ((kubectl @k exec redpanda-0 -- rpk group describe scoring-service | Select-String "session\.answer-submitted\s+$partition\s").Line -split '\s+')[-1]
$owner = kubectl @k get pods -l app.kubernetes.io/name=scoring-service -o jsonpath="{range .items[?(@.status.podIP==`"$ip`")]}{.metadata.name}{end}"
"deleting $owner ($ip)"
kubectl @k delete pod $owner --wait=false
foreach ($i in 1..15) {
    $d = kubectl @k exec redpanda-0 -- rpk group describe scoring-service
    $state = ($d | Select-String '^STATE').Line -replace 'STATE\s+', ''
    $members = ($d | Select-String '^MEMBERS').Line -replace 'MEMBERS\s+', ''
    $p = ((($d | Select-String "session\.answer-submitted\s+$partition\s").Line -split '\s+') | Select-Object -Last 1)
    '{0,3}s  {1,-18} members={2}  partition {3} -> {4}' -f ($i * 2), $state, $members, $partition, $p
    Start-Sleep 2
}
```
From the dry run (2026-10-03):
```
  6s  Stable             members=2  partition 2 -> 10.244.0.28    still owned: preStop (5 s) + graceful shutdown
  8s  PreparingRebalance members=1  partition 2 ->                it left the group: a rebalance starts
 10s  Stable             members=1  partition 2 -> 10.244.0.27    the survivor owns ALL partitions
 14s  PreparingRebalance members=2  partition 2 ->                the replacement joins: another rebalance
 16s  Stable             members=2  partition 2 -> 10.244.0.27    split again
```
- **A graceful delete** lets the consumer commit and **leave** the group, so the rebalance starts at once.
- **A hard kill** (`--grace-period=0 --force`) wouldn't leave. The group would wait for the session timeout (45 s by default) before handing over.

**Right away, play question 4.** Answers are accepted as always. The leaderboard may pause for a moment during the rebalance, then catch up.

📸 Buzzer Game → **Scoring lag at the broker:** a short bump that returns to 0. Also **Events applied vs skipped as duplicates:** the "duplicate" line shows whether any record was redelivered across the hand-over and safely skipped (`processed_events`, 5.2).

## 10. Finish the game, then check the scores

Play questions 5–8, including one player who **doesn't answer** a question and one **double click** (refused as DUPLICATE). The host clicks **end**.

Take the session id from the host tab. `[guid]` checks it before it goes into SQL:
```powershell
$sessionId = [guid]'<the session id>'
```

`psql` runs **inside the postgres pod** as each service's own role. The image trusts local socket connections, so no password is needed (same as compose).

### 10a. Expected (from `session_db.answers`) vs actual (`scoring_db.player_scores`)

The same two queries as chaos-scoring §9a, through `kubectl exec` instead of `docker compose exec`:
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
$expected = kubectl @k exec postgres-0 -- psql -U session_app -d session_db -At -F ',' -c $expectedSql
$actual = kubectl @k exec postgres-0 -- psql -U scoring_app -d scoring_db -At -F ',' -c $actualSql
'expected:'; $expected; 'actual:'; $actual
$diff = Compare-Object -ReferenceObject @($expected) -DifferenceObject @($actual)
if ($expected.Count -gt 0 -and -not $diff) { 'MATCH' } else { 'MISMATCH'; $diff }
```

### 10b. Readable, with names and every question

```powershell
kubectl @k exec postgres-0 -- psql -U session_app -d session_db -c @"
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
Compare it with what you wrote down, and with the final leaderboard in the tabs.

### 10c. Nothing left behind

```powershell
kubectl @k exec postgres-0 -- psql -U scoring_app -d scoring_db -c "SELECT question_count, ended_at_ms IS NOT NULL AS ended, version FROM scoring_sessions WHERE session_id = '$sessionId';"
kubectl @k exec postgres-0 -- psql -U session_app -d session_db -At -c "SELECT 'unsent outbox rows: ' || count(*) FROM outbox WHERE sent_at IS NULL;"
kubectl @k exec redpanda-0 -- rpk group describe scoring-service
kubectl @k exec redpanda-0 -- rpk topic describe session.answer-submitted-dlt -p
kubectl @k exec redpanda-0 -- rpk topic describe session.lifecycle-dlt -p
```
Expected exactly as in chaos-scoring §9c: `ended = t`, `version` = the number of answers, 0 unsent outbox rows, `TOTAL-LAG 0`, both DLTs `HIGH-WATERMARK 0` on every partition.

## 11. Put the declared state back

```powershell
kubectl --context kind-buzzer apply -k infra/k8s/overlays/kind
kubectl @k get deployments
```
Every service is back at 1 replica, quiz-service included.
- `kubectl scale` is **imperative**: it changes the live object, and nothing in git knows.
- `apply -k` is **declarative**: the cluster is made to match the files again.

`.\tasks.ps1 kind-up` does the same apply.

## 12. Not in this check (the production answers)

| What | Why not here | In production |
|---|---|---|
| **HPA** (automatic scaling) | needs metrics-server and ~0.6 GiB per extra session pod the machine doesn't have | `autoscaling/v2` HPA, min 2. CPU is a weak signal for WebSockets: better to scale on connections per pod, via KEDA or prometheus-adapter on `buzzer_websocket_connections` |
| **session-service 2 → 3** | a third 640Mi JVM doesn't fit, even borrowing quiz's slot | same commands as §4 with `--replicas=3`; works with Docker at 9 GB |
| **PodDisruptionBudget** | one copy per service: a PDB of `minAvailable: 1` would block every drain | `minAvailable: 1` per service with ≥ 2 replicas, so a node drain never takes the last copy |
| **Several nodes** | one-node kind | `topologySpreadConstraints` so two copies never share a node |

## 13. Result of a run

**Dry run of the mechanics (2026-10-03, scripted, no test client):**
- quiz → 0 / session → 2: second pod ready in **15 s**
- session → 1 / scoring → 2: two members, `range` split 0–1 / 2
- deleting partition 2's owner: the survivor took it at **10 s**, and the replacement joined at **14 s**
- `apply -k` restored 1-of-each
- a scripted 6-player game, then 10a through `kubectl exec`: **`MATCH`**

**Your run (fill in):**

| Player | Answers | Points: by hand = SQL expected = scoring_db |
|---|---|---|
| | | |

Session pod deleted after question …; players reconnected in … s; the other pod's players unaffected: yes / no. Scoring pod deleted after question …; hand-over at … s. `MATCH` / `MISMATCH`. Screenshots: Up (§4), Connected WebSockets (§7), Scoring lag at the broker (§9). Pod log: `$env:TEMP\chaos-k8s-pods.log`.

## If something goes wrong

| Symptom | Look at |
|---|---|
| A scaled-up pod stays `Pending` | `kubectl @k describe pod <name>`: `Insufficient memory` means the slot you freed isn't free yet (a pod still `Terminating`). Wait for it, or check §4/§8's `wait`. |
| Tabs stay *reconnecting…* | `kubectl @k get pods`: is a session pod `1/1`? Then check the gateway pod's log. The handshake goes Traefik → gateway → session. |
| All connections on one session pod | §5: open another player tab |
| A partition shows no owner for long | the group is rebalancing; a hard-killed consumer is only dropped after the session timeout (~45 s) |
| `MISMATCH` | as in chaos-scoring "If it doesn't match": DLTs, unsent outbox rows, `answers` higher in actual = double counting |

## Clean up

Ctrl+C in terminals 1–3, then §11 if you skipped it. The cluster keeps running. `.\tasks.ps1 kind-down` deletes it and all its data.
