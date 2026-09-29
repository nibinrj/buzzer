package dev.nibin.buzzer.scoring.application;

import dev.nibin.buzzer.scoring.domain.Leaderboard;
import dev.nibin.buzzer.scoring.domain.PlayerPoints;
import dev.nibin.buzzer.scoring.domain.PlayerTotal;
import dev.nibin.buzzer.scoring.domain.Ranking;
import dev.nibin.buzzer.scoring.domain.Ranking.Ranked;
import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import dev.nibin.buzzer.scoring.domain.ScoringSession;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The two reads behind /api/results. The live leaderboard comes from the Redis copy (rebuilt from Postgres if it's
 * gone); the final results come straight from Postgres, every player, once the session has ended.
 * <p>
 * No membership check: scoring knows membership ids, not user ids, so it can't tell whether the caller played.
 * Any HOST or PLAYER token may read any session's results (SecurityConfig); the data is points against opaque ids.
 */
@Service
public class GetResults {

    private final ScoringRepository scoring;
    private final Leaderboard leaderboard;

    public GetResults(ScoringRepository scoring, Leaderboard leaderboard) {
        this.scoring = scoring;
        this.leaderboard = leaderboard;
    }

    public LiveLeaderboard leaderboard(UUID sessionId) {
        ScoringSession session = scoring.session(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        List<PlayerPoints> top = leaderboard.top(sessionId).orElseGet(() -> rebuildAndRead(sessionId));
        return new LiveLeaderboard(session.version(), Ranking.rank(top, PlayerPoints::points));
    }

    public FinalResults finalResults(UUID sessionId) {
        ScoringSession session = scoring.session(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!session.ended()) {
            throw new SessionNotEndedException(sessionId);
        }
        return new FinalResults(Instant.ofEpochMilli(session.endedAtMs()),
                Ranking.rank(scoring.totals(sessionId), PlayerTotal::points));
    }

    private List<PlayerPoints> rebuildAndRead(UUID sessionId) {
        List<PlayerPoints> totals = scoring.totals(sessionId).stream().map(PlayerTotal::toPoints).toList();
        if (totals.isEmpty()) {
            return List.of(); // started, no answers yet: nothing to copy, and Redis doesn't keep empty sets
        }
        leaderboard.rebuild(sessionId, totals);
        return leaderboard.top(sessionId).orElse(List.of());
    }

    /** @param version the same counter ScoreUpdated carries */
    public record LiveLeaderboard(long version, List<Ranked<PlayerPoints>> top) {
    }

    public record FinalResults(Instant endedAt, List<Ranked<PlayerTotal>> players) {
    }
}
