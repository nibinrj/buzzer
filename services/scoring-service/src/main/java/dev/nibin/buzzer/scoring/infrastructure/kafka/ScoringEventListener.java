package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.scoring.application.ApplyScoringEvent;
import dev.nibin.buzzer.scoring.application.PublishLeaderboard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes session-service's events and hands them to {@link ApplyScoringEvent}.
 * <p>
 * <b>Ack after commit.</b> The offset is committed ({@code ack.acknowledge()}, AckMode MANUAL_IMMEDIATE) only after
 * the event's transaction committed. A crash in between redelivers the record, and processed_events turns that
 * redelivery into a no-op: at-least-once delivery, applied once.
 * <p>
 * <b>Non-blocking retries.</b> If applying throws, the record is not acked here. The retry-topic error handling
 * forwards it to {@code <topic>-retry-1000}, then {@code -retry-2000} (3 attempts in all, 1 s then 2 s apart), and
 * finally to {@code <topic>-dlt}, while this partition goes on with the next record. That gives up ordering, which is
 * fine: an answer's points come from its own event (correctRank), and totals are sums.
 * <p>
 * <b>Poison records</b> ({@link UnreadableEventException}) skip the retries and go straight to the DLT.
 * {@code numPartitions = "3"}: the retry and DLT topics get as many partitions as the main topics (the annotation's
 * default is 1).
 * <p>
 * <b>groupId on the annotation</b>, not only in spring.kafka.consumer.group-id: only then does Spring Kafka give each
 * retry and DLT consumer a group of its own ({@code scoring-service-retry-1000}, ...{@code -dlt}). Without it they all
 * joined {@code scoring-service}, and any of them starting or stopping rebalanced the main consumers too.
 */
@Component
public class ScoringEventListener {

    /** Records that reached a DLT: each one is a score or a lifecycle change that is missing until a human acts. */
    public static final String DEAD_LETTERED = "scoring.events.dead.lettered";

    private static final Logger log = LoggerFactory.getLogger(ScoringEventListener.class);

    private final EventReader reader;
    private final ApplyScoringEvent apply;
    private final PublishLeaderboard publishLeaderboard;
    private final MeterRegistry meters;

    public ScoringEventListener(EventReader reader, ApplyScoringEvent apply, PublishLeaderboard publishLeaderboard,
            MeterRegistry meters) {
        this.reader = reader;
        this.apply = apply;
        this.publishLeaderboard = publishLeaderboard;
        this.meters = meters;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 1000, multiplier = 2),
            numPartitions = "3",
            exclude = UnreadableEventException.class,
            kafkaTemplate = "kafkaTemplate")
    @KafkaListener(topics = {AnswerSubmitted.TOPIC, SessionLifecycle.TOPIC}, groupId = "${spring.kafka.consumer.group-id}")
    public void onEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        boolean applied = switch (reader.read(record)) {
            case AnswerSubmitted event -> {
                boolean scored = apply.answerSubmitted(event);
                // After the commit, and ALSO for a redelivery (scored == false): if Redis or Kafka failed on an
                // earlier attempt, this is the retry that repairs it. Both steps are safe to repeat.
                publishLeaderboard.afterAnswer(event.sessionId(), event.playerId());
                yield scored;
            }
            case SessionStarted event -> apply.sessionStarted(event);
            case SessionEnded event -> apply.sessionEnded(event);
            default -> throw new IllegalStateException("EventReader returned an unexpected type");
        };
        if (!applied) {
            log.debug("Already applied, skipped: topic={} partition={} offset={}", record.topic(),
                    record.partition(), record.offset());
        }
        ack.acknowledge(); // after the commit and the leaderboard above: never before
    }

    /**
     * The end of the line: nothing reads a DLT again on its own. Counted (alert on it) and logged with ids and the
     * failure's type only, never the record's contents, then acked so a restart doesn't count it twice.
     */
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Counter.builder(DEAD_LETTERED)
                .description("Records that reached a DLT and are not applied")
                .tag("topic", record.topic())
                .register(meters)
                .increment();
        // The retry-topic path writes the plain kafka_original-* / kafka_exception-* headers, not the kafka_dlt-* ones
        // a stand-alone DeadLetterPublishingRecoverer would. The message and stack trace headers are left out on
        // purpose: an exception message can quote data.
        log.error("Dead-lettered: topic={} partition={} offset={} key={} eventType={} originalTopic={} exception={} cause={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                header(record, EventHeaders.TYPE), header(record, KafkaHeaders.ORIGINAL_TOPIC),
                header(record, KafkaHeaders.EXCEPTION_FQCN), header(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN));
        ack.acknowledge();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
