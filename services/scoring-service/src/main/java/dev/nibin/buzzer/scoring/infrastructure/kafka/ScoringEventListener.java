package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.scoring.application.ApplyScoringEvent;
import dev.nibin.buzzer.scoring.application.LogContext;
import dev.nibin.buzzer.scoring.application.PublishLeaderboard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    public static final String DEAD_LETTERED = "buzzer.scoring.events.dead.lettered";
    /** Spring Kafka's default suffix for the dead-letter topic of a @RetryableTopic (asserted in the tests). */
    static final String DLT_SUFFIX = "-dlt";
    /** Every event read, by type and whether it was applied or already had been (a redelivery). */
    public static final String EVENTS = "buzzer.scoring.events";
    /** From the answer's acceptance (answeredAtMs) to its score being committed here. New answers only. */
    public static final String DELAY = "buzzer.scoring.delay";
    /** ScoreUpdated sent, or not because the player is outside the top 10. */
    public static final String SCORE_UPDATES = "buzzer.scoring.score.updates";

    private static final Logger log = LoggerFactory.getLogger(ScoringEventListener.class);
    private static final List<String> EVENT_TYPES = List.of(
            AnswerSubmitted.class.getSimpleName(), SessionStarted.class.getSimpleName(),
            SessionEnded.class.getSimpleName());

    private final EventReader reader;
    private final ApplyScoringEvent apply;
    private final PublishLeaderboard publishLeaderboard;
    private final MeterRegistry meters;
    private final Map<String, Counter> events = new HashMap<>();
    private final Map<PublishLeaderboard.Outcome, Counter> scoreUpdates =
            new EnumMap<>(PublishLeaderboard.Outcome.class);
    private final Timer scoringDelay;

    /**
     * Every series registered up front, at 0: a rate over a series that appears only on its first increment misses
     * that first one, and a dashboard shows "no data" instead of 0.
     */
    public ScoringEventListener(EventReader reader, ApplyScoringEvent apply, PublishLeaderboard publishLeaderboard,
            MeterRegistry meters) {
        this.reader = reader;
        this.apply = apply;
        this.publishLeaderboard = publishLeaderboard;
        this.meters = meters;
        for (String type : EVENT_TYPES) {
            for (boolean applied : new boolean[] {true, false}) {
                events.put(eventKey(type, applied), Counter.builder(EVENTS)
                        .description("Events read, applied or skipped as already applied")
                        .tag("type", type)
                        .tag("result", applied ? "applied" : "duplicate")
                        .register(meters));
            }
        }
        for (PublishLeaderboard.Outcome outcome : PublishLeaderboard.Outcome.values()) {
            scoreUpdates.put(outcome, Counter.builder(SCORE_UPDATES)
                    .description("ScoreUpdated sent, or skipped because the player is outside the top 10")
                    .tag("result", outcome.name().toLowerCase(Locale.ROOT))
                    .register(meters));
        }
        this.scoringDelay = Timer.builder(DELAY)
                .description("Answer accepted (session-service, Redis clock) to score committed (here)")
                .register(meters);
        // The DLT counters too. Created on the first dead-lettered record instead, a series would appear already at
        // 1, and increase() (the BuzzerScoringDeadLetter alert) needs an earlier sample to see that first record.
        for (String topic : List.of(AnswerSubmitted.TOPIC, SessionLifecycle.TOPIC)) {
            deadLetterCounter(topic + DLT_SUFFIX);
        }
    }

    /** Registers on first call, returns the same counter after: Micrometer keeps one meter per name and tags. */
    private Counter deadLetterCounter(String dltTopic) {
        return Counter.builder(DEAD_LETTERED)
                .description("Records that reached a DLT and are not applied")
                .tag("topic", dltTopic)
                .register(meters);
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 1000, multiplier = 2),
            numPartitions = "3",
            exclude = UnreadableEventException.class,
            kafkaTemplate = "kafkaTemplate")
    @KafkaListener(topics = {AnswerSubmitted.TOPIC, SessionLifecycle.TOPIC}, groupId = "${spring.kafka.consumer.group-id}")
    public void onEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        // Log fields for everything below. The session from the key (session-service keys by session), so even a record
        // that can't be parsed is labelled; the player once the answer has been read. Removed in finally: the consumer
        // thread goes on to other sessions' records. (Spring Kafka's own lines about a failure, written after this
        // method threw, come after the finally and are not labelled.)
        LogContext.putIfUuid(LogContext.SESSION_ID, record.key());
        try {
            handle(record, ack);
        } finally {
            MDC.remove(LogContext.SESSION_ID);
            MDC.remove(LogContext.PLAYER_ID);
        }
    }

    private void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        boolean applied = switch (reader.read(record)) {
            case AnswerSubmitted event -> {
                MDC.put(LogContext.PLAYER_ID, event.playerId().toString());
                boolean scored = counted(event, apply.answerSubmitted(event));
                if (scored) {
                    recordDelay(event.answeredAtMs());
                }
                // After the commit, and ALSO for a redelivery (scored == false): if Redis or Kafka failed on an
                // earlier attempt, this is the retry that repairs it. Both steps are safe to repeat.
                scoreUpdates.get(publishLeaderboard.afterAnswer(event.sessionId(), event.playerId())).increment();
                yield scored;
            }
            case SessionStarted event -> counted(event, apply.sessionStarted(event));
            case SessionEnded event -> counted(event, apply.sessionEnded(event));
            default -> throw new IllegalStateException("EventReader returned an unexpected type");
        };
        if (!applied) {
            log.debug("Already applied, skipped: topic={} partition={} offset={}", record.topic(),
                    record.partition(), record.offset());
        }
        ack.acknowledge(); // after the commit and the leaderboard above: never before
    }

    /**
     * Counted right after its transaction, before the leaderboard step: if that step fails, the retry finds the event
     * applied and counts a duplicate, so every event is counted as applied exactly once.
     */
    private boolean counted(Object event, boolean applied) {
        events.get(eventKey(event.getClass().getSimpleName(), applied)).increment();
        return applied;
    }

    /**
     * answeredAtMs is Redis's clock when session-service accepted the answer; now is this JVM's. Two clocks, so the
     * delay is off by their skew (milliseconds): negative values from that are recorded as 0.
     */
    private void recordDelay(long answeredAtMs) {
        scoringDelay.record(Math.max(0, System.currentTimeMillis() - answeredAtMs), TimeUnit.MILLISECONDS);
    }

    private static String eventKey(String type, boolean applied) {
        return type + "/" + (applied ? "applied" : "duplicate");
    }

    /**
     * The end of the line: nothing reads a DLT again on its own. Counted (alert on it) and logged with ids and the
     * failure's type only, never the record's contents, then acked so a restart doesn't count it twice.
     */
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) {
        // The key survives the retry topics, so a dead-lettered record still says which session is missing a score.
        LogContext.putIfUuid(LogContext.SESSION_ID, record.key());
        try {
            deadLettered(record, ack);
        } finally {
            MDC.remove(LogContext.SESSION_ID);
        }
    }

    private void deadLettered(ConsumerRecord<String, String> record, Acknowledgment ack) {
        deadLetterCounter(record.topic()).increment();
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
