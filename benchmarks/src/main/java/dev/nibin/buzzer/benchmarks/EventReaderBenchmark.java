package dev.nibin.buzzer.benchmarks;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.scoring.infrastructure.kafka.EventReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * What scoring-service does with every record from session.answer-submitted before any database work: read the type
 * header, parse the JSON with Jackson 3, check the fields. {@link #jacksonOnly} is the parse alone, so the difference
 * is what EventReader's header lookup and checks cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class EventReaderBenchmark {

    // A default JsonMapper, as in EventReaderTest, not Boot's bean: there is no Spring context here.
    private final JsonMapper json = JsonMapper.builder().build();
    private final EventReader reader = new EventReader(json);

    private String value;
    private ConsumerRecord<String, String> record;

    @Setup
    public void setUp() {
        AnswerSubmitted event = new AnswerSubmitted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, true, 3, 5, System.currentTimeMillis(), AnswerSubmitted.SCHEMA_VERSION);
        // Written by Jackson, as session-service's outbox does: the same JSON shape a real record carries.
        value = json.writeValueAsString(event);
        record = new ConsumerRecord<>(AnswerSubmitted.TOPIC, 0, 0L, event.sessionId().toString(), value);
        record.headers().add(EventHeaders.TYPE, "AnswerSubmitted".getBytes(StandardCharsets.UTF_8));
    }

    @Benchmark
    public Object read() {
        return reader.read(record);
    }

    @Benchmark
    public AnswerSubmitted jacksonOnly() {
        return json.readValue(value, AnswerSubmitted.class);
    }
}
