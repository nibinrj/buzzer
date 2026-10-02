package dev.nibin.buzzer.session.infrastructure.outbox;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.session.config.OutboxProperties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxPublisher when Kafka misbehaves, without Spring or containers: the repository is a mock that hands out the
 * rows not marked sent yet (as lockOldestUnsent would), and there is no real transaction. The happy path, with real
 * Postgres and Redpanda, is OutboxTest.
 */
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final OutboxJpaRepository repository = mock(OutboxJpaRepository.class);

    @Test
    void withTheBrokerUnreachableARunEndsAfterOneRefusedSendNotOnePerRow() throws Exception {
        List<OutboxJpaEntity> rows = rows(5);
        // A real producer, pointed at a port nobody listens on: each send waits max.block.ms for metadata, then fails.
        KafkaTemplate<String, String> unreachable = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + freePort(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000)));
        try {
            long started = System.nanoTime();

            publisher(unreachable, Duration.ofSeconds(15)).publishPending(); // and it doesn't throw

            // One max.block.ms (1 s), not five: the other four rows were never handed to the producer.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
            assertThat(rows).allSatisfy(row -> assertThat(row.getSentAt()).isNull());
        } finally {
            unreachable.destroy();
        }
    }

    @Test
    void rowsFromTheFirstUnacknowledgedOneOnStayUnsentAndTheNextRunSendsThem() {
        List<OutboxJpaEntity> rows = rows(3);
        KafkaTemplate<String, String> kafka = kafka();
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(acked(), failsLater(), acked()) // run 1: row 2 fails, so row 3 isn't marked either
                .thenReturn(acked(), acked());              // run 2: rows 2 and 3 again
        OutboxPublisher publisher = publisher(kafka, Duration.ofSeconds(15));

        publisher.publishPending();

        assertThat(rows).extracting(OutboxJpaEntity::getSentAt).containsExactly(NOW, null, null);

        publisher.publishPending();

        assertThat(rows).extracting(OutboxJpaEntity::getSentAt).containsExactly(NOW, NOW, NOW);
        verify(kafka, times(5)).send(any(ProducerRecord.class)); // rows 2 and 3 twice: at-least-once
    }

    @Test
    void sendTimeoutBoundsTheWholeRunNotEachRow() {
        List<OutboxJpaEntity> rows = rows(10);
        KafkaTemplate<String, String> kafka = kafka();
        // The broker never answers: every acknowledgement hangs.
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> new CompletableFuture<>());
        long started = System.nanoTime();

        publisher(kafka, Duration.ofMillis(300)).publishPending();

        // 300 ms in total; per row it would be 3 s.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        assertThat(rows).allSatisfy(row -> assertThat(row.getSentAt()).isNull());
    }

    @Test
    void aSendThatThrowsCountsAsNotAcknowledgedAndStopsTheRun() {
        List<OutboxJpaEntity> rows = rows(3);
        KafkaTemplate<String, String> kafka = kafka();
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(acked())
                .thenThrow(new IllegalStateException("producer closed"));

        publisher(kafka, Duration.ofSeconds(15)).publishPending(); // the exception doesn't escape

        assertThat(rows).extracting(OutboxJpaEntity::getSentAt).containsExactly(NOW, null, null);
        verify(kafka, times(2)).send(any(ProducerRecord.class)); // row 3 wasn't sent after the refusal
    }

    // --- helpers ---

    /** Rows as lockOldestUnsent returns them: the ones not marked sent yet, oldest first. */
    private List<OutboxJpaEntity> rows(int count) {
        List<OutboxJpaEntity> rows = IntStream.range(0, count)
                .mapToObj(i -> new OutboxJpaEntity(UUID.randomUUID(), AnswerSubmitted.TOPIC,
                        UUID.randomUUID().toString(), "AnswerSubmitted", "{}", NOW, null))
                .toList();
        when(repository.lockOldestUnsent(anyInt()))
                .thenAnswer(invocation -> rows.stream().filter(row -> row.getSentAt() == null).toList());
        return rows;
    }

    private OutboxPublisher publisher(KafkaTemplate<String, String> kafka, Duration sendTimeout) {
        return new OutboxPublisher(repository, kafka, TransactionOperations.withoutTransaction(),
                new OutboxProperties(300, 100, sendTimeout, 3, (short) 1), Clock.fixed(NOW, ZoneOffset.UTC),
                ObservationRegistry.NOOP);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafka() {
        return mock(KafkaTemplate.class);
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    /** Not failed yet when send returns (so the run keeps sending), failed by the time it is waited for. */
    private static CompletableFuture<SendResult<String, String>> failsLater() {
        return new CompletableFuture<SendResult<String, String>>().orTimeout(50, TimeUnit.MILLISECONDS);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
