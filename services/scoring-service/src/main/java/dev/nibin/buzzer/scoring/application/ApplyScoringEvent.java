package dev.nibin.buzzer.scoring.application;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.scoring.domain.Points;
import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one event to scoring_db, at most once. Each method is one transaction: "this eventId is processed" and the
 * change it causes commit together or not at all. So a redelivered event (Kafka is at-least-once) finds its id
 * already there and changes nothing, and an event whose change failed left no mark, so its retry applies it.
 * <p>
 * The caller acknowledges the Kafka record only after the method returned, i.e. after the commit. Each method
 * returns true if the event was applied now, false if it had been applied before.
 */
@Service
public class ApplyScoringEvent {

    private final ScoringRepository scoring;

    public ApplyScoringEvent(ScoringRepository scoring) {
        this.scoring = scoring;
    }

    @Transactional
    public boolean answerSubmitted(AnswerSubmitted event) {
        if (!scoring.markProcessed(event.eventId())) {
            return false;
        }
        scoring.addAnswer(event.sessionId(), event.playerId(), Points.of(event.correct(), event.correctRank()),
                event.correct());
        return true;
    }

    @Transactional
    public boolean sessionStarted(SessionStarted event) {
        if (!scoring.markProcessed(event.eventId())) {
            return false;
        }
        scoring.sessionStarted(event.sessionId(), event.questionCount(), event.startedAtMs());
        return true;
    }

    @Transactional
    public boolean sessionEnded(SessionEnded event) {
        if (!scoring.markProcessed(event.eventId())) {
            return false;
        }
        scoring.sessionEnded(event.sessionId(), event.endedAtMs());
        return true;
    }
}
