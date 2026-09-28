package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.SessionAccess;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who may SUBSCRIBE or SEND where. Deny by default: only the destinations below are allowed.
 * <pre>
 * SUBSCRIBE /topic/sessions/{id}[/...]                  the session's host and its players
 * SUBSCRIBE /user/queue/...                             anyone connected (Spring routes it to that user only)
 * SEND      /app/sessions/{id}/start|next|reveal|end    the session's host
 * </pre>
 * Everything else is refused, notably a client SENDing straight to /topic/...: the simple broker would relay it
 * to every subscriber, so any player could broadcast a fake question.
 * <p>
 * Runs after JwtConnectInterceptor on the inbound channel. A refusal throws; the client receives an ERROR frame.
 */
@Component
public class DestinationAuthorizationInterceptor implements ChannelInterceptor {

    private static final Pattern SESSION_TOPIC = Pattern.compile("^/topic/sessions/([^/]+)(/.*)?$");
    private static final Pattern HOST_COMMAND = Pattern.compile("^/app/sessions/([^/]+)/(start|next|reveal|end)$");
    private static final String USER_QUEUES = "/user/queue/";

    private final SessionAccess access;

    public DestinationAuthorizationInterceptor(SessionAccess access) {
        this.access = access;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.SUBSCRIBE.equals(command)) {
            requireAllowed(maySubscribe(accessor.getDestination(), accessor.getUser()), "SUBSCRIBE", accessor);
        } else if (StompCommand.SEND.equals(command)) {
            requireAllowed(maySend(accessor.getDestination(), accessor.getUser()), "SEND", accessor);
        }
        return message;
    }

    boolean maySubscribe(String destination, Principal user) {
        if (user == null || destination == null) {
            return false;
        }
        if (destination.startsWith(USER_QUEUES)) {
            return true;
        }
        return sessionId(SESSION_TOPIC, destination)
                .map(sessionId -> access.isHostOrPlayer(sessionId, userId(user)))
                .orElse(false);
    }

    boolean maySend(String destination, Principal user) {
        if (user == null || destination == null) {
            return false;
        }
        return sessionId(HOST_COMMAND, destination)
                .map(sessionId -> access.isHost(sessionId, userId(user)))
                .orElse(false);
    }

    private static void requireAllowed(boolean allowed, String action, StompHeaderAccessor accessor) {
        if (!allowed) {
            throw new AccessDeniedException(action + " to " + accessor.getDestination() + " is not allowed");
        }
    }

    /** The {id} of a matching destination, if it is a UUID. */
    private static Optional<UUID> sessionId(Pattern pattern, String destination) {
        Matcher matcher = pattern.matcher(destination);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(matcher.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static UUID userId(Principal user) {
        return UUID.fromString(user.getName());
    }
}
