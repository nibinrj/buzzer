package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.scoring.CapturedSpans;
import dev.nibin.buzzer.scoring.ScoringIntegrationTest;
import io.opentelemetry.api.trace.SpanKind;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * scoring-service's half of one answer's trace (session-service's half: SessionWebSocketTest). An AnswerSubmitted
 * arrives carrying a traceparent, as session-service's outbox publisher sends it. The listener's span must continue
 * that trace, and the ScoreUpdated it publishes must carry the same trace on to session-service's push.
 * <p>
 * Sent with a plain KafkaProducer, not the app's KafkaTemplate: with observation on, the template would add a
 * traceparent of its own (a new trace, since the test thread has none), and the listener reads the last one.
 */
@ScoringIntegrationTest
class TracingTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private JsonMapper json;

    @Autowired
    private CapturedSpans spans;

    private final UUID session = UUID.randomUUID();

    @Test
    void anAnswerContinuesTheTraceItArrivedWithAndScoreUpdatedCarriesItOn() throws Exception {
        String traceId = hex(16);
        String senderSpanId = hex(8);
        AnswerSubmitted answer = new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), UUID.randomUUID(),
                0, true, 1, 1, System.currentTimeMillis(), AnswerSubmitted.SCHEMA_VERSION);

        send(answer, "00-" + traceId + "-" + senderSpanId + "-01");

        // The listener's span: same trace, child of the span that sent the record.
        await().atMost(WAIT).untilAsserted(() -> assertThat(spans.ofTrace(traceId))
                .anyMatch(span -> span.getKind() == SpanKind.CONSUMER
                        && span.getParentSpanId().equals(senderSpanId)));
        // The first answer of the session is in the top 10, so a ScoreUpdated goes out, in the same trace.
        assertThat(traceparentOfScoreUpdated().split("-")[1]).isEqualTo(traceId);
    }

    private void send(AnswerSubmitted answer, String traceparent) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record = new ProducerRecord<>(AnswerSubmitted.TOPIC, session.toString(),
                    json.writeValueAsString(answer));
            record.headers().add(EventHeaders.TYPE, "AnswerSubmitted".getBytes(StandardCharsets.UTF_8));
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        }
    }

    private String traceparentOfScoreUpdated() {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(ScoreUpdated.TOPIC).stream()
                    .map(info -> new TopicPartition(ScoreUpdated.TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    Header traceparent = record.headers().lastHeader("traceparent");
                    if (session.toString().equals(record.key()) && traceparent != null) {
                        return new String(traceparent.value(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return fail("No ScoreUpdated with a traceparent for session " + session);
    }

    private Object bootstrapServers() {
        return kafkaAdmin.getConfigurationProperties().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
    }

    /** A random lowercase hex id of {@code bytes} bytes: 16 for a trace id, 8 for a span id. */
    private static String hex(int bytes) {
        byte[] id = new byte[bytes];
        RANDOM.nextBytes(id);
        return HexFormat.of().formatHex(id);
    }
}
