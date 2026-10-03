package dev.nibin.buzzer.benchmarks;

import dev.nibin.buzzer.scoring.domain.Points;
import dev.nibin.buzzer.scoring.domain.Ranking;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * scoring-service's two pure functions: points for one answer, and ranks for a sorted leaderboard.
 * Short warm-ups and measurements on purpose: these are nanosecond operations, and 5 × 1 s is millions of calls.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ScoringDomainBenchmark {

    /** Inputs for Points.of. Fields, not constants: from a constant the JIT could fold the whole call away. */
    @State(Scope.Benchmark)
    public static class PointsInput {
        boolean correct = true;
        int correctRank = 4;
    }

    /** A leaderboard the way a real game makes one: 20 questions, many players on the same total. */
    @State(Scope.Benchmark)
    public static class Leaderboard {

        @Param({"10", "200", "500"})
        int players;

        List<Line> bestFirst;

        @Setup
        public void build() {
            Random random = new Random(42); // the same leaderboard in every fork and every run
            List<Line> lines = new ArrayList<>(players);
            for (int player = 0; player < players; player++) {
                int total = 0;
                for (int question = 0; question < 20; question++) {
                    boolean correct = random.nextInt(10) < 6;
                    total += Points.of(correct, correct ? 1 + random.nextInt(players) : 0);
                }
                lines.add(new Line(player, total));
            }
            lines.sort(Comparator.comparingInt(Line::points).reversed());
            bestFirst = List.copyOf(lines);
        }
    }

    record Line(int player, int points) {
    }

    @Benchmark
    public int pointsOf(PointsInput input) {
        return Points.of(input.correct, input.correctRank);
    }

    /** Returned, so JMH consumes the result and the JIT can't drop the work as unused. */
    @Benchmark
    public List<Ranking.Ranked<Line>> rank(Leaderboard leaderboard) {
        return Ranking.rank(leaderboard.bestFirst, Line::points);
    }
}
