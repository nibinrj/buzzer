package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.scoring.application.ScoreUpdatePublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends ScoreUpdated the same way session-service's outbox sends its events: key = sessionId (one partition per
 * session, so one session's updates stay in order), String JSON value, and the eventType header.
 * <p>
 * Waits for the broker's ack (acks=all) on the consumer thread. That is what makes a failure visible: the listener
 * then doesn't ack the answer's record, and the retry path publishes again.
 */
@Component
class KafkaScoreUpdatePublisher implements ScoreUpdatePublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Duration sendTimeout;

    KafkaScoreUpdatePublisher(KafkaTemplate<String, String> kafka, JsonMapper json,
            @Value("${scoring.score-updates.send-timeout}") Duration sendTimeout) {
        this.kafka = kafka;
        this.json = json;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(ScoreUpdated event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(ScoreUpdated.TOPIC,
                event.sessionId().toString(), json.writeValueAsString(event));
        record.headers().add(EventHeaders.TYPE, "ScoreUpdated".getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing ScoreUpdated", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("ScoreUpdated not acknowledged by the broker", e);
        }
    }
}
