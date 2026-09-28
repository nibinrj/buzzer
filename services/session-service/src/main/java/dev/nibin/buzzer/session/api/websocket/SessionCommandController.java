package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.application.RunSession;
import dev.nibin.buzzer.session.application.SessionNotFoundException;
import dev.nibin.buzzer.session.domain.SessionStateException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * The host's commands over STOMP: SEND to /app/sessions/{id}/start|next|reveal|end (the /app prefix is stripped
 * before matching). Nothing is returned on success: the result reaches everyone, host included, on the session's
 * topics. A failure goes back to the sender only, on /user/queue/errors.
 * <p>
 * DestinationAuthorizationInterceptor already let only the host through; RunSession checks again.
 */
@Controller
public class SessionCommandController {

    private final RunSession runSession;

    public SessionCommandController(RunSession runSession) {
        this.runSession = runSession;
    }

    @MessageMapping("/sessions/{sessionId}/start")
    public void start(@DestinationVariable UUID sessionId, Principal host) {
        runSession.start(sessionId, userId(host));
    }

    @MessageMapping("/sessions/{sessionId}/next")
    public void next(@DestinationVariable UUID sessionId, Principal host) {
        runSession.next(sessionId, userId(host));
    }

    @MessageMapping("/sessions/{sessionId}/reveal")
    public void reveal(@DestinationVariable UUID sessionId, Principal host) {
        runSession.reveal(sessionId, userId(host));
    }

    @MessageMapping("/sessions/{sessionId}/end")
    public void end(@DestinationVariable UUID sessionId, Principal host) {
        runSession.end(sessionId, userId(host));
    }

    /** broadcast = false: only the connection that sent the command, not the host's other tabs. */
    @MessageExceptionHandler(SessionStateException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public CommandError handleState(SessionStateException e) {
        return new CommandError("Not possible now", e.getMessage());
    }

    @MessageExceptionHandler(SessionNotFoundException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public CommandError handleNotFound(SessionNotFoundException e) {
        return new CommandError("Session not found", "No session with this id for you.");
    }

    @MessageExceptionHandler(LiveStateUnavailableException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public CommandError handleLiveStateUnavailable(LiveStateUnavailableException e) {
        return new CommandError("Game state unavailable", "Nothing was changed. Try again in a few seconds.");
    }

    private static UUID userId(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    /** The STOMP counterpart of a ProblemDetail: what went wrong, in words for the host. */
    public record CommandError(String title, String detail) {
    }
}
