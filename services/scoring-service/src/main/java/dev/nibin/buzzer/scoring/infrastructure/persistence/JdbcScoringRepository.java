package dev.nibin.buzzer.scoring.infrastructure.persistence;

import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * scoring_db through plain SQL. Each method is ONE statement, which Postgres runs atomically, so two consumers
 * (the main topic's and a retry topic's) changing the same player's total at once both count: no read-modify-write,
 * no lost update. JdbcClient already translates SQLExceptions into Spring's DataAccessException.
 */
@Component
public class JdbcScoringRepository implements ScoringRepository {

    private final JdbcClient jdbc;

    public JdbcScoringRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ON CONFLICT DO NOTHING instead of catching a duplicate-key error: in Postgres, an error aborts the whole
     * transaction, so the caller couldn't carry on in it. A conflict here is no error; it just inserts 0 rows.
     * (A concurrent insert of the same id waits for the other transaction, then inserts 0 rows if that committed.)
     */
    @Override
    public boolean markProcessed(UUID eventId) {
        return jdbc.sql("""
                        INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, now())
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .update() == 1;
    }

    @Override
    public void addAnswer(UUID sessionId, UUID playerId, int points, boolean correct) {
        jdbc.sql("""
                        INSERT INTO player_scores (session_id, player_id, points, answers, correct_answers, updated_at)
                        VALUES (:sessionId, :playerId, :points, 1, :correct, now())
                        ON CONFLICT (session_id, player_id) DO UPDATE SET
                            points          = player_scores.points + EXCLUDED.points,
                            answers         = player_scores.answers + 1,
                            correct_answers = player_scores.correct_answers + EXCLUDED.correct_answers,
                            updated_at      = EXCLUDED.updated_at
                        """)
                .param("sessionId", sessionId)
                .param("playerId", playerId)
                .param("points", points)
                .param("correct", correct ? 1 : 0)
                .update();
    }

    @Override
    public void sessionStarted(UUID sessionId, int questionCount, long startedAtMs) {
        jdbc.sql("""
                        INSERT INTO scoring_sessions (session_id, question_count, started_at_ms)
                        VALUES (:sessionId, :questionCount, :startedAtMs)
                        ON CONFLICT (session_id) DO UPDATE SET
                            question_count = EXCLUDED.question_count,
                            started_at_ms  = EXCLUDED.started_at_ms
                        """)
                .param("sessionId", sessionId)
                .param("questionCount", questionCount)
                .param("startedAtMs", startedAtMs)
                .update();
    }

    @Override
    public void sessionEnded(UUID sessionId, long endedAtMs) {
        jdbc.sql("""
                        INSERT INTO scoring_sessions (session_id, ended_at_ms) VALUES (:sessionId, :endedAtMs)
                        ON CONFLICT (session_id) DO UPDATE SET ended_at_ms = EXCLUDED.ended_at_ms
                        """)
                .param("sessionId", sessionId)
                .param("endedAtMs", endedAtMs)
                .update();
    }
}
