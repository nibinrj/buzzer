package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionStarted;

/**
 * Port: records an event to be published to Kafka later (the transactional outbox). Must be called INSIDE the
 * transaction that makes the change the event describes: the event is then stored if and only if that change
 * commits. The implementation refuses to run without a transaction.
 */
public interface EventOutbox {

    void answerSubmitted(AnswerSubmitted event);

    void sessionStarted(SessionStarted event);

    void sessionEnded(SessionEnded event);
}
