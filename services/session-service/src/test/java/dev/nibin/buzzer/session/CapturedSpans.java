package dev.nibin.buzzer.session;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps every finished span in memory, so tests can look at the traces the app produced. Imported as a bean by
 * {@link ApiIntegrationTest}: Boot hands every SpanExporter bean to its span processor, next to (or, with no OTLP
 * endpoint set, instead of) the OTLP exporter. Spans arrive in batches (management.opentelemetry.tracing.export
 * .schedule-delay, short in tests), so tests wait for them.
 */
public class CapturedSpans implements SpanExporter {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /** Every span of one trace, in the order they finished. */
    public List<SpanData> ofTrace(String traceId) {
        return spans.stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }
}
