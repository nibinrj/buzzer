package dev.nibin.buzzer.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

/**
 * session.outbox.*: how OutboxPublisher drains the outbox, and how its topics are created.
 *
 * @param pollIntervalMs  pause between the end of one run and the start of the next
 * @param batchSize       rows sent per run at most, oldest first
 * @param sendTimeout     how long one run waits for the broker's acknowledgements, in total, before giving up on
 *                        the rest (they are retried next run)
 * @param topicPartitions partitions of each topic when this service creates it (sessions spread over them)
 * @param topicReplicas   copies of each partition; 1 locally, 3 on a real cluster
 */
@Validated
@ConfigurationProperties("session.outbox")
public record OutboxProperties(
        @Min(50) long pollIntervalMs,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration sendTimeout,
        @Min(1) int topicPartitions,
        @Min(1) short topicReplicas) {
}
