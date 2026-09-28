package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port: pushes what happened in a session to everyone connected to it, on whichever session-service instance their
 * WebSocket is connected to. Implemented by a Redis pub/sub relay (infrastructure.redis.RedisRelayBroadcaster).
 * Called only after the change is committed, so nobody sees something that was then rolled back.
 */
public interface SessionBroadcaster {

    void questionShown(QuestionShown question);

    void statusChanged(StatusChanged change);

    /**
     * Goes to every player: built field by field from the question, and correctOption is not one of the fields.
     * Options are addressed by index.
     */
    record QuestionShown(UUID sessionId, int index, UUID questionId, String text, int timeLimitSeconds,
            List<String> options, Instant deadline) {

        public static QuestionShown of(UUID sessionId, int index, SessionQuestion question, Instant deadline) {
            return new QuestionShown(sessionId, index, question.questionId(), question.text(),
                    question.timeLimitSeconds(), question.options(), deadline);
        }
    }

    record StatusChanged(UUID sessionId, Session.Status status) {
    }
}
