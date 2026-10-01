package dev.nibin.buzzer.session.api.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Stamps an answer frame with the moment it arrived: the start of buzzer.answer.ack.latency (AckLatencyInterceptor
 * stops it). Registered FIRST on the inbound channel, so the stamp is taken on the WebSocket thread before anything
 * else, including DestinationAuthorizationInterceptor's database check, and before the frame waits for a pool thread.
 * <p>
 * System.nanoTime(): a monotonic clock, right for durations inside one JVM (unlike currentTimeMillis, which jumps
 * when the wall clock is adjusted). Never compared across processes.
 */
@Component
public class AnswerReceivedInterceptor implements ChannelInterceptor {

    private static final Pattern ANSWER = Pattern.compile("^/app/sessions/[^/]+/answer$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        // Mutable: the accessor of this very message, still being built by Spring's STOMP handler, so setHeader
        // changes the message that continues down the chain (as JwtConnectInterceptor's setUser does).
        if (accessor != null && accessor.isMutable() && StompCommand.SEND.equals(accessor.getCommand())
                && accessor.getDestination() != null && ANSWER.matcher(accessor.getDestination()).matches()) {
            accessor.setHeader(AnswerController.RECEIVED_AT_NANOS, System.nanoTime());
        }
        return message;
    }
}
