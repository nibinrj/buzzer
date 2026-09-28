package dev.nibin.buzzer.session.infrastructure.outbox;

import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.session.config.OutboxConfig;
import dev.nibin.buzzer.session.config.OutboxProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains the outbox to Kafka. Every run, in one transaction:
 * <ol>
 *   <li>lock the oldest unsent rows (up to batch-size), skipping rows another instance holds;</li>
 *   <li>send them, in id order, stopping at the first send the producer refuses on the spot (broker unreachable);</li>
 *   <li>wait for the broker's acknowledgement of each, in id order, send-timeout in total for the whole run;</li>
 *   <li>mark sent every row up to the first one that was not acknowledged; the rest stay for the next run.</li>
 * </ol>
 * So a run holds its row locks and its database connection for send-timeout at most, even with Kafka down. A run
 * then fails after max.block.ms (broker never reached: the first send is refused) or delivery.timeout.ms (broker
 * lost after it was reached: the sends fail together), logs one line, and the rows wait for the next run.
 * Delivery is at-least-once: a row whose acknowledgement was lost (or whose mark didn't commit) is sent again, so
 * consumers deduplicate on eventId. Stopping at the first failure means nothing after a failed row is marked sent;
 * later rows of the same batch may already be on the broker and are then sent a second time (a duplicate, which the
 * consumer drops). Order is only as good as that: consumers must not depend on it (seq and correctRank are in the
 * event itself).
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxJpaRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionOperations transaction;
    private final OutboxProperties properties;
    private final Clock clock;

    OutboxPublisher(OutboxJpaRepository repository, KafkaTemplate<String, String> kafka,
            TransactionOperations transaction, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * fixedDelay: the next run starts poll-interval after this one ENDS, so runs never overlap. On its own scheduler
     * (OutboxConfig): without the qualifier it would run on the STOMP broker's scheduler, the only one in the context,
     * and a slow run while Kafka is down would hold one of that pool's threads.
     */
    @Scheduled(fixedDelayString = "${session.outbox.poll-interval-ms}", scheduler = OutboxConfig.OUTBOX_SCHEDULER)
    void publishPending() {
        transaction.executeWithoutResult(status -> {
            List<OutboxJpaEntity> batch = repository.lockOldestUnsent(properties.batchSize());
            if (batch.isEmpty()) {
                return;
            }
            // Send first, then wait: the broker gets the whole batch at once instead of one round trip each.
            List<CompletableFuture<SendResult<String, String>>> sends = sendUntilRefused(batch);
            long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
            for (int i = 0; i < sends.size(); i++) {
                if (!acknowledged(sends.get(i), batch.get(i), deadline)) {
                    break;
                }
                batch.get(i).markSent(clock.instant());
            }
        });
    }

    /**
     * A send already failed when it returns is the producer refusing on the spot. That is what an unreachable broker
     * looks like: KafkaProducer.send blocks up to max.block.ms waiting for the topic's metadata, then gives up. Every
     * further send would block just as long, so the rest of the batch isn't sent this run.
     */
    private List<CompletableFuture<SendResult<String, String>>> sendUntilRefused(List<OutboxJpaEntity> batch) {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
        for (OutboxJpaEntity row : batch) {
            CompletableFuture<SendResult<String, String>> send = send(row);
            sends.add(send);
            if (send.isCompletedExceptionally()) {
                break;
            }
        }
        return sends;
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxJpaEntity row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(row.getTopic(), row.getMessageKey(), row.getPayload());
        record.headers().add(EventHeaders.TYPE, row.getEventType().getBytes(StandardCharsets.UTF_8));
        try {
            return kafka.send(record);
        } catch (RuntimeException e) {
            // Some failures are thrown instead of failing the future; treat them the same way.
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Waits at most until the run's deadline: send-timeout bounds the whole run, not each row. */
    private boolean acknowledged(CompletableFuture<SendResult<String, String>> send, OutboxJpaEntity row,
            long deadlineNanos) {
        try {
            send.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Outbox event {} ({}) not acknowledged by Kafka; it and later ones are retried next run: {}",
                    row.getEventId(), row.getEventType(), e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down: keep the flag, retry on next start
            return false;
        }
    }
}
