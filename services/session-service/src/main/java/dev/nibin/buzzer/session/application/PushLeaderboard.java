package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.session.application.SessionBroadcaster.LeaderboardChanged;
import dev.nibin.buzzer.session.domain.LeaderboardVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Turns scoring-service's ScoreUpdated into a push to /topic/sessions/{id}/leaderboard, unless a newer leaderboard
 * was already pushed for that session.
 * <p>
 * A push is a notification (ADR-003: at-most-once). If Redis can't be reached, the update is logged and dropped,
 * never retried: the next ScoreUpdated replaces it anyway, and a client can always ask scoring-service's
 * GET /api/results/sessions/{id}/leaderboard.
 */
@Service
public class PushLeaderboard {

    private static final Logger log = LoggerFactory.getLogger(PushLeaderboard.class);

    private final LeaderboardVersions versions;
    private final SessionBroadcaster broadcaster;

    public PushLeaderboard(LeaderboardVersions versions, SessionBroadcaster broadcaster) {
        this.versions = versions;
        this.broadcaster = broadcaster;
    }

    /** @return true if it was pushed */
    public boolean push(ScoreUpdated update) {
        boolean notOlder;
        try {
            notOlder = versions.acceptIfNotOlder(update.sessionId(), update.version());
        } catch (DataAccessException e) {
            log.warn("Leaderboard version {} for session {} not pushed, Redis unavailable ({})", update.version(),
                    update.sessionId(), e.getClass().getSimpleName());
            return false;
        }
        if (!notOlder) {
            log.debug("Leaderboard version {} for session {} is older than the last pushed one, skipped",
                    update.version(), update.sessionId());
            return false;
        }
        broadcaster.leaderboardChanged(new LeaderboardChanged(update.sessionId(), update.version(),
                update.top10().stream()
                        .map(entry -> new LeaderboardChanged.Line(entry.rank(), entry.playerId(), entry.points()))
                        .toList()));
        return true;
    }
}
