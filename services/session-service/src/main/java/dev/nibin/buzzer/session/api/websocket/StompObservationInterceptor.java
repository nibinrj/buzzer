package dev.nibin.buzzer.session.api.websocket;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Traces STOMP commands. Spring traces HTTP requests, RestClient calls and Kafka by itself, but not STOMP frames
 * (spring-messaging 7.0 has no observation support), so this opens one observation per command a client SENDs to
 * /app/... : an answer, or a host's start/next/reveal/end.
 * <p>
 * <b>It is the root of its trace.</b> Browsers aren't traced and a STOMP frame carries no traceparent, so there is no
 * parent to continue. Everything the command causes hangs below it: the Redis script, the Postgres transaction, the
 * outbox row (which stores this trace, V5), and through Kafka, scoring-service's work and the ScoreUpdated push.
 * <p>
 * Around the @MessageMapping handler only: the inbound channel also hands each frame to the broker and the user
 * destination handler, which would open two more, empty spans. beforeHandle and afterMessageHandled run on the pool
 * thread around that one handler (see StompLogContextInterceptor), so the observation's scope (and with it the
 * traceId/spanId in MDC) covers exactly the command's work.
 */
@Component
public class StompObservationInterceptor implements ExecutorChannelInterceptor {

    /** One span and one timer per STOMP command; tag destination = the template, never the real session id. */
    public static final String COMMAND = "buzzer.stomp.command";

    private static final Pattern SESSION_ID = Pattern.compile("^/app/sessions/[^/]+/");

    private final ObservationRegistry observations;

    public StompObservationInterceptor(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (handler instanceof SimpAnnotationMethodMessageHandler && destination != null
                && destination.startsWith("/app/")) {
            String template = SESSION_ID.matcher(destination).replaceFirst("/app/sessions/{sessionId}/");
            Observation.createNotStarted(COMMAND, observations)
                    .contextualName("STOMP " + template)
                    .lowCardinalityKeyValue("destination", template)
                    .start()
                    .openScope();
        }
        return message;
    }

    /** Closes what beforeHandle opened on this thread: the current scope, if it is one of ours. */
    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception ex) {
        if (!(handler instanceof SimpAnnotationMethodMessageHandler)) {
            return;
        }
        Observation.Scope scope = observations.getCurrentObservationScope();
        if (scope == null || !COMMAND.equals(scope.getCurrentObservation().getContext().getName())) {
            return;
        }
        Observation observation = scope.getCurrentObservation();
        scope.close();
        if (ex != null) {
            observation.error(ex);
        }
        observation.stop();
    }
}
