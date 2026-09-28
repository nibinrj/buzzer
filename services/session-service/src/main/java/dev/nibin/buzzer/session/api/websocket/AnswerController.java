package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.api.websocket.SessionCommandController.CommandError;
import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.application.SessionNotFoundException;
import dev.nibin.buzzer.session.application.SubmitAnswer;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * A player's answer over STOMP: SEND to /app/sessions/{id}/answer with {"questionId": "...", "optionId": 2}.
 * DestinationAuthorizationInterceptor already let only the session's players through; SubmitAnswer checks again.
 * <p>
 * Every answer gets exactly one reply, on /user/queue/answer-ack, and only on the connection that sent it
 * (broadcast = false: not the player's other tabs). A request that can't be processed at all gets a reply on
 * /user/queue/errors instead.
 */
@Controller
public class AnswerController {

    private final SubmitAnswer submitAnswer;

    public AnswerController(SubmitAnswer submitAnswer) {
        this.submitAnswer = submitAnswer;
    }

    @MessageMapping("/sessions/{sessionId}/answer")
    @SendToUser(destinations = "/queue/answer-ack", broadcast = false)
    public AnswerAck answer(@DestinationVariable UUID sessionId, @Payload AnswerRequest request, Principal player) {
        SubmitAnswer.Result result = submitAnswer.submit(sessionId, UUID.fromString(player.getName()),
                request.questionId(), request.optionId());
        return AnswerAck.of(result);
    }

    /** Someone else's session, or not a player of it: "not found", as everywhere in this service. */
    @MessageExceptionHandler(SessionNotFoundException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public CommandError handleNotFound(SessionNotFoundException e) {
        return new CommandError("Session not found", "No session with this id for you.");
    }

    @MessageExceptionHandler(LiveStateUnavailableException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public CommandError handleLiveStateUnavailable(LiveStateUnavailableException e) {
        return new CommandError("Game state unavailable", "Your answer was not counted. Send it again.");
    }

    /** The payload isn't JSON of the expected shape (Spring couldn't build an AnswerRequest from it). */
    @MessageExceptionHandler(MessageConversionException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public CommandError handleUnreadable(MessageConversionException e) {
        return new CommandError("Unreadable answer", "Send JSON: {\"questionId\": \"...\", \"optionId\": 0}.");
    }

    /**
     * @param optionId the chosen option's index in the question's options. Integer, not int: a missing field
     *                 stays null (answered INVALID) instead of silently becoming option 0.
     */
    public record AnswerRequest(UUID questionId, Integer optionId) {
    }

    /**
     * @param reason null when accepted; otherwise why not (SubmitAnswer.Reason)
     * @param seq    the place in line when accepted, the first answer's place when a duplicate, else null
     */
    public record AnswerAck(UUID questionId, boolean accepted, String reason, Long seq) {

        static AnswerAck of(SubmitAnswer.Result result) {
            return new AnswerAck(result.questionId(), result.accepted(),
                    result.accepted() ? null : result.reason().name(),
                    result.seq() > 0 ? result.seq() : null);
        }
    }
}
