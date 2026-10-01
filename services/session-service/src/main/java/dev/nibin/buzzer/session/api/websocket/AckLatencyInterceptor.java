package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.SubmitAnswer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stops buzzer.answer.ack.latency: on the outbound channel's thread, once the ack has been handed to the player's
 * WebSocket (afterMessageHandled runs after the handler that writes it).
 * <p>
 * So the timer covers the whole server side of an answer: the inbound interceptors, the wait for an inbound pool
 * thread, the controller (Redis + Postgres), the broker, the wait for an outbound pool thread, and the write. It
 * doesn't cover the network to and from the browser. If another thread is writing to the same socket at that moment,
 * Spring buffers the frame and this measures up to the hand-over.
 */
@Component
public class AckLatencyInterceptor implements ExecutorChannelInterceptor {

    /** From the answer frame arriving to its ack being handed to the WebSocket. Tag outcome = SubmitAnswer.Reason. */
    public static final String ACK_LATENCY = "buzzer.answer.ack.latency";

    private final Map<SubmitAnswer.Reason, Timer> ackLatency = new EnumMap<>(SubmitAnswer.Reason.class);

    public AckLatencyInterceptor(MeterRegistry meters) {
        for (SubmitAnswer.Reason reason : SubmitAnswer.Reason.values()) {
            ackLatency.put(reason, Timer.builder(ACK_LATENCY)
                    .description("Answer frame arrival until its ack is handed to the WebSocket, by outcome")
                    .tag("outcome", reason.name())
                    .register(meters));
        }
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception ex) {
        // Only acks carry these (AnswerController): every other outbound message passes untouched.
        Long receivedAtNanos = message.getHeaders().get(AnswerController.RECEIVED_AT_NANOS, Long.class);
        String outcome = message.getHeaders().get(AnswerController.OUTCOME, String.class);
        if (receivedAtNanos != null && outcome != null && ex == null) {
            ackLatency.get(SubmitAnswer.Reason.valueOf(outcome))
                    .record(System.nanoTime() - receivedAtNanos, TimeUnit.NANOSECONDS);
        }
    }
}
