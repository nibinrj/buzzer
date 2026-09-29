package dev.nibin.buzzer.scoring.application;

import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.scoring.domain.Leaderboard;
import dev.nibin.buzzer.scoring.domain.Leaderboard.Placement;
import dev.nibin.buzzer.scoring.domain.PlayerPoints;
import dev.nibin.buzzer.scoring.domain.PlayerTotal;
import dev.nibin.buzzer.scoring.domain.Ranking;
import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import dev.nibin.buzzer.scoring.domain.ScoringSession;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The part of scoring an answer that happens AFTER its transaction committed: copy the player's new total to the
 * Redis leaderboard, and publish ScoreUpdated if the top 10 may have changed.
 * <p>
 * <b>No outbox, on purpose.</b> This runs after every AnswerSubmitted record, including a redelivery whose score was
 * already applied (then it just repeats). If Redis or Kafka fails here, the exception sends the record down the
 * retry path; the retry finds the answer already applied, skips the score, and runs this again. That is only safe
 * because both steps can be repeated: Redis gets an absolute total that can only raise the stored one, and
 * ScoreUpdated is a whole snapshot, so sending one twice changes nothing.
 * <p>
 * <b>Published only when the answering player is in the top 10 afterwards.</b> Otherwise the top 10 didn't change:
 * points only grow, so a player outside it can't push anyone out. With 500 players this turns up to 500 events per
 * question into at most one per answer from a top-10 player, and each event becomes a push to every client.
 */
@Service
public class PublishLeaderboard {

    private final ScoringRepository scoring;
    private final Leaderboard leaderboard;
    private final ScoreUpdatePublisher publisher;

    public PublishLeaderboard(ScoringRepository scoring, Leaderboard leaderboard, ScoreUpdatePublisher publisher) {
        this.scoring = scoring;
        this.leaderboard = leaderboard;
        this.publisher = publisher;
    }

    public void afterAnswer(UUID sessionId, UUID playerId) {
        PlayerPoints total = scoring.pointsOf(sessionId, playerId).orElseThrow(() -> new IllegalStateException(
                "no total for a player whose answer was committed")); // the caller only runs after the commit
        Placement placement = leaderboard.raise(sessionId, total).orElseGet(() -> rebuildAndRaise(sessionId, total));
        if (!placement.inTop()) {
            return;
        }
        // Read after the Redis write: the version then counts at least every answer the snapshot contains.
        long version = scoring.session(sessionId).map(ScoringSession::version).orElseThrow();
        publisher.publish(new ScoreUpdated(UUID.randomUUID(), sessionId, entries(placement.top()), version,
                ScoreUpdated.SCHEMA_VERSION));
    }

    /** The Redis copy expired or was lost: refill it from Postgres (which already has this answer), then retry. */
    private Placement rebuildAndRaise(UUID sessionId, PlayerPoints total) {
        leaderboard.rebuild(sessionId, scoring.totals(sessionId).stream().map(PlayerTotal::toPoints).toList());
        return leaderboard.raise(sessionId, total).orElseThrow(() -> new IllegalStateException(
                "leaderboard still missing right after its rebuild"));
    }

    static List<ScoreUpdated.Entry> entries(List<PlayerPoints> top) {
        return Ranking.rank(top, PlayerPoints::points).stream()
                .map(ranked -> new ScoreUpdated.Entry(ranked.rank(), ranked.item().playerId(), ranked.item().points()))
                .toList();
    }
}
