package dev.nibin.buzzer.scoring.application;

import dev.nibin.buzzer.events.ScoreUpdated;

/** Sends ScoreUpdated to scoring.score-updated (Kafka). */
public interface ScoreUpdatePublisher {

    /** Returns once the broker acknowledged the event; throws if it didn't, so the caller's record is retried. */
    void publish(ScoreUpdated event);
}
