package dev.nibin.buzzer.session.infrastructure.kafka;

import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.session.application.LogContext;
import dev.nibin.buzzer.session.application.PushLeaderboard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads scoring-service's scoring.score-updated and hands each leaderboard to {@link PushLeaderboard}.
 * <p>
 * One consumer group for all session-service instances (spring.kafka.consumer.group-id): each ScoreUpdated is read
 * by ONE instance, which publishes it on the Redis relay, and every instance delivers it to its own clients.
 * <p>
 * Offsets are committed by the container after each poll's records (Spring's default AckMode BATCH): a push is a
 * notification, so there is no manual ack, no retry topic and no DLT here. A record that can't be read is logged
 * (ids and types only) and skipped; the next leaderboard replaces it anyway.
 */
@Component
public class ScoreUpdatedListener {

    /** The listener container's id, so tests can wait for its partitions to be assigned. */
    public static final String ID = "score-updates";

    /** Every ScoreUpdated read, by what became of it: tag result = pushed, older, redis_unavailable, unreadable. */
    public static final String PUSHES = "buzzer.leaderboard.pushes";

    private static final Logger log = LoggerFactory.getLogger(ScoreUpdatedListener.class);

    private final PushLeaderboard pushLeaderboard;
    private final JsonMapper json;
    private final Map<PushLeaderboard.Outcome, Counter> pushes = new EnumMap<>(PushLeaderboard.Outcome.class);
    private final Counter unreadable;

    /**
     * All counters are registered up front, so each series exists (at 0) from the start: a rate over a series that
     * appears only after its first increment would miss that first one.
     */
    public ScoreUpdatedListener(PushLeaderboard pushLeaderboard, JsonMapper json, MeterRegistry meters) {
        this.pushLeaderboard = pushLeaderboard;
        this.json = json;
        for (PushLeaderboard.Outcome outcome : PushLeaderboard.Outcome.values()) {
            pushes.put(outcome, pushCounter(outcome.name().toLowerCase(Locale.ROOT), meters));
        }
        this.unreadable = pushCounter("unreadable", meters);
    }

    private static Counter pushCounter(String result, MeterRegistry meters) {
        return Counter.builder(PUSHES)
                .description("ScoreUpdated records read, by what became of them")
                .tag("result", result)
                .register(meters);
    }

    @KafkaListener(id = ID, topics = ScoreUpdated.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void onScoreUpdated(ConsumerRecord<String, String> record) {
        // The key is the sessionId (scoring-service keys by session). Taken from the key, not the JSON, so the
        // lines about a record that can't be read are labelled too. Removed in finally: the consumer thread is reused.
        LogContext.putIfUuid(LogContext.SESSION_ID, record.key());
        try {
            handle(record);
        } finally {
            MDC.remove(LogContext.SESSION_ID);
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        ScoreUpdated update = read(record);
        if (update == null) {
            unreadable.increment();
        } else {
            pushes.get(pushLeaderboard.push(update)).increment();
        }
    }

    /** @return null if the record isn't a ScoreUpdated this service can push */
    private ScoreUpdated read(ConsumerRecord<String, String> record) {
        String type = header(record);
        if (!"ScoreUpdated".equals(type)) {
            return skipped(record, "eventType " + type);
        }
        if (record.value() == null) {
            return skipped(record, "no value");
        }
        ScoreUpdated update;
        try {
            update = json.readValue(record.value(), ScoreUpdated.class);
        } catch (JacksonException e) {
            return skipped(record, e.getClass().getSimpleName()); // Jackson's message would quote the input
        }
        if (update.schemaVersion() != ScoreUpdated.SCHEMA_VERSION) {
            return skipped(record, "schemaVersion " + update.schemaVersion());
        }
        if (update.sessionId() == null || update.top10() == null) {
            return skipped(record, "no sessionId or top10");
        }
        return update;
    }

    private static ScoreUpdated skipped(ConsumerRecord<String, String> record, String why) {
        log.warn("Skipped an unreadable ScoreUpdated: partition={} offset={} key={} ({})", record.partition(),
                record.offset(), record.key(), why);
        return null;
    }

    private static String header(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EventHeaders.TYPE);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
