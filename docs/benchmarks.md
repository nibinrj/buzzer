# Microbenchmarks (JMH)

`benchmarks/` measures four hot paths in isolation, without Spring, Kafka or a database:

| Benchmark | What it measures | Where it runs in the game |
|---|---|---|
| `ScoringDomainBenchmark.pointsOf` | `Points.of`: points for one answer | scoring-service, once per answer |
| `ScoringDomainBenchmark.rank` | `Ranking.rank` over 10 / 200 / 500 sorted lines | 10: `PublishLeaderboard`, after every answer. 200/500: `GetResults`, the full results |
| `EventReaderBenchmark.read` | `EventReader.read` on one `AnswerSubmitted` record: type header, Jackson 3 parse, field checks | scoring-service, once per Kafka record |
| `EventReaderBenchmark.jacksonOnly` | `JsonMapper.readValue` alone, the same JSON | reference point for `read` |
| `SubmitAnswerBenchmark.script` | `submit_answer.lua` via EVALSHA, one accepted answer | session-service, once per answer |
| `SubmitAnswerBenchmark.separateCommands` | The script's 9 commands sent one by one from Java | Nowhere. This is what the script replaced (ADR-004) |

`SubmitAnswerEquivalenceTest` checks that the script and the separate commands give the same replies for every
outcome (accepted, duplicate, wrong question, closed, late, not running). Without that check, comparing their speed
would mean nothing.

## Running it

```powershell
.\tasks.ps1 bench                      # everything, ~3 min
.\tasks.ps1 bench -Only SubmitAnswer   # one class (a regex over benchmark names)
```

Docker must be running: each `SubmitAnswerBenchmark` fork starts its own `redis:7` container. Results go to the
console and to `benchmarks\target\jmh-result.json`. That JSON file can be pasted into
[jmh.morethan.io](https://jmh.morethan.io) to see charts.

For numbers you'll quote, close what you can (browser, IDE indexing) and run with the kind cluster down. Two runs on
the same machine differ by a few percent anyway. A result counts only if it holds up across runs.

## Machine

| | |
|---|---|
| CPU | Intel Core i5-12500H: 12 cores (4 performance + 8 efficiency), 16 threads, laptop |
| RAM | 15.7 GB |
| OS | Windows 11 Home 10.0.26200 |
| JVM | Oracle JDK 21.0.10 (HotSpot), JMH defaults (no extra flags) |
| Docker | Docker Desktop 29.6.1, WSL 2 VM (kernel 6.6.87), 16 CPUs / 7.6 GiB |
| JMH | 1.37. Per benchmark: 2 forks × (3 warm-up + 5 measured iterations); 1 s each, 2 s for Redis |

A hybrid CPU is a source of noise: Windows may run one fork on a performance core and the next on an efficiency
core. That's one reason there are 2 forks, and a reason the error bars are wider than on a server.

## Results — 2026-10-03, first run

Conditions: the kind clusters were still running in Docker (idle), so take these as a first baseline.

| Benchmark | Players | Score | ± Error (99.9%) | Unit |
|---|---:|---:|---:|---|
| `pointsOf` | | 0.800 | 0.029 | ns/op |
| `rank` | 10 | 73.1 | 2.6 | ns/op |
| `rank` | 200 | 1,284 | 29 | ns/op |
| `rank` | 500 | 3,145 | 146 | ns/op |
| `EventReader.read` | | 1,509 | 59 | ns/op |
| `EventReader.jacksonOnly` | | 1,457 | 107 | ns/op |
| `SubmitAnswer.script` | | 634 | 53 | µs/op |
| `SubmitAnswer.separateCommands` | | 5,361 | 520 | µs/op |

Note the units: the Redis results are in **microseconds**, everything else in **nanoseconds**. One accepted answer
in Redis (634 µs) costs about as much as 420 `EventReader.read` calls.

### What the numbers say

1. **The script is ~8.5× faster than the same work sent as separate commands**, and 9 commands means 9 round trips.
   So almost all the time is the network, not Redis: ~0.6 ms per round trip here. Most of that is Docker Desktop
   forwarding the port from Windows into its WSL 2 VM. On AWS, inside one VPC, each round trip is shorter, so both
   numbers drop. The ratio still follows the round-trip count, though. The speed is a bonus: the script exists
   because it is atomic (ADR-004), and the separate commands are not.
2. **`EventReader`'s own checks cost nothing measurable.** `read` and `jacksonOnly` differ by 52 ns, and their error
   intervals overlap (1,450–1,568 vs 1,350–1,564). Parsing the JSON is the whole cost, ~1.5 µs per record. Next to
   the database transaction that follows each record, that's noise.
3. **`Ranking.rank` is linear, ~6.3 ns per line.** The top 10 after every answer takes 73 ns. The full results for
   500 players take 3 µs, and that happens once per game.
4. **`Points.of` is effectively free** (0.8 ns, a few CPU cycles). The JIT inlines it into the benchmark loop. A
   result this small tells you only that the call costs nothing worth measuring. It doesn't support a precise
   comparison.

**For Phase B's load tests:** none of the CPU code here can be the first bottleneck. Per answer it adds up to
~1.6 µs. The answer path is dominated by network round trips (Redis, Kafka, Postgres), and that's where B.2/B.3
should look first.

## How to read a JMH result

- **Score** is the mean over all measured iterations (here 2 forks × 5 = **Cnt 10**). In `avgt` mode, lower is better.
- **Error** is half the width of a 99.9% confidence interval around that mean. JMH computes it from the spread of
  the 10 iteration means (Student's t). `1,284 ± 29` means the true mean is very likely between 1,255 and 1,313.
- **Two results differ only if their intervals don't overlap.** If they overlap (`read` vs `jacksonOnly`), the
  benchmark can't tell them apart. That is a result too: "no measurable difference".
- **A wide error means noise, not a slow operation.** The Redis benchmarks have ±8–10% because a Docker port-forward
  round trip varies; the pure-Java ones have ±2–5%.
- **Why forks:** each fork is a fresh JVM. The JIT can make different decisions in different JVMs (which code to
  inline, in what order it compiles), so one JVM's number can be consistently off. Two forks expose that as a wider
  error.
- **Why warm-up:** the first iterations run interpreted code, then C1-compiled code, then C2-compiled code. Only the
  measured iterations, after that settles, are in the score.

### What the benchmark code does to stay honest

- **Every result is returned.** JMH consumes the returned value, so the JIT can't decide the work is unused and
  delete it (dead-code elimination).
- **Inputs are fields of a `@State` object, not constants.** From constants, the JIT could compute `Points.of(true, 4)`
  once at compile time (constant folding) and measure nothing.
- **The leaderboard is built once in `@Setup` with a fixed seed.** Building it isn't measured, and every fork ranks
  the same data.
- **Every Redis call is a new player**, so every call takes the longest path (accepted, correct). The answer set is
  cleared before each iteration, so it doesn't keep growing during the run.
