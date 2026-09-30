package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.LogContext;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Labels every log line written while a STOMP frame is handled with its session (from the destination) and the
 * connected user.
 * <p>
 * Why beforeHandle and not preSend: a frame arrives on the WebSocket's I/O thread, where preSend runs, and is then
 * handed to the clientInboundChannel's thread pool, where @MessageMapping runs. MDC belongs to a thread, so a value
 * put in preSend would be gone by then. An ExecutorChannelInterceptor's beforeHandle and afterMessageHandled run on
 * the pool thread, around the handler, and afterMessageHandled runs even when the handler threw.
 */
@Component
public class StompLogContextInterceptor implements ExecutorChannelInterceptor {

    /** /app/sessions/{id}/... (commands, answers) and /topic/sessions/{id}[/...] (subscriptions). */
    private static final Pattern SESSION_DESTINATION = Pattern.compile("^/(?:app|topic)/sessions/([^/]+)(?:/.*)?$");

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (destination != null) {
            Matcher matcher = SESSION_DESTINATION.matcher(destination);
            if (matcher.matches()) {
                LogContext.putIfUuid(LogContext.SESSION_ID, matcher.group(1));
            }
        }
        // Set by JwtConnectInterceptor on CONNECT; Spring copies it onto every later frame of the connection.
        Principal user = SimpMessageHeaderAccessor.getUser(message.getHeaders());
        if (user != null) {
            LogContext.putIfUuid(LogContext.USER_ID, user.getName());
        }
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
            Exception ex) {
        MDC.remove(LogContext.SESSION_ID);
        MDC.remove(LogContext.USER_ID);
    }
}
