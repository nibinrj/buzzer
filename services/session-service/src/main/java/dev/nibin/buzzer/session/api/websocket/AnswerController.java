package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.api.websocket.SessionCommandController.CommandError;
import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.application.SessionNotFoundException;
import dev.nibin.buzzer.session.application.SubmitAnswer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player's answer over STOMP: SEND to /app/sessions/{id}/answer with {"questionId": "...", "optionId": 2}.
 * DestinationAuthorizationInterceptor already let only the session's players through; SubmitAnswer checks again.
 * <p>
 * Every answer gets exactly one reply, on /user/queue/answer-ack, and only on the connection that sent it (not the
 * player's other tabs). A request that can't be processed at all gets a reply on /user/queue/errors instead.
 * <p>
 * The ack is sent through the messaging template rather than returned with @SendToUser, because it must carry two
 * internal headers for {@link AckLatencyInterceptor}: when the frame arrived and its outcome. Headers that aren't
 * STOMP native headers never reach the client.
 */
@Controller
public class AnswerController {

    /**
     * The controller's part alone: the Redis decision plus the Postgres write. Tag outcome = SubmitAnswer.Reason, so
     * its count per outcome is also "answers by outcome". The whole round trip is {@link AckLatencyInterceptor}'s.
     */
    public static final String HANDLING = "buzzer.answer.handling";
    /** System.nanoTime() when the frame arrived (AnswerReceivedInterceptor), carried from the answer to its ack. */
    public static final String RECEIVED_AT_NANOS = "buzzerReceivedAtNanos";
    /** The ack's SubmitAnswer.Reason, for AckLatencyInterceptor's outcome tag. */
    public static final String OUTCOME = "buzzerOutcome";

    private static final String ACK_QUEUE = "/queue/answer-ack";

    private final SubmitAnswer submitAnswer;
    private final SimpMessageSendingOperations messaging;
    private final MeterRegistry meters;
    private final Map<SubmitAnswer.Reason, Timer> handling = new EnumMap<>(SubmitAnswer.Reason.class);

    /**
     * One timer per outcome, registered up front: every outcome's series exists from the start (at 0), so a rate of
     * LATE answers reads 0, not "no data", until the first one.
     */
    public AnswerController(SubmitAnswer submitAnswer, SimpMessageSendingOperations messaging, MeterRegistry meters) {
        this.submitAnswer = submitAnswer;
        this.messaging = messaging;
        this.meters = meters;
        for (SubmitAnswer.Reason reason : SubmitAnswer.Reason.values()) {
            handling.put(reason, Timer.builder(HANDLING)
                    .description("Answer handling in the controller (Redis + Postgres), by outcome")
                    .tag("outcome", reason.name())
                    .register(meters));
        }
    }

    /**
     * @param connectionId    the STOMP session (one browser tab's connection) the frame came in on
     * @param receivedAtNanos null if the frame wasn't stamped (only in tests that bypass the inbound channel)
     */
    @MessageMapping("/sessions/{sessionId}/answer")
    public void answer(@DestinationVariable UUID sessionId, @Payload AnswerRequest request, Principal player,
            @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String connectionId,
            @Header(name = RECEIVED_AT_NANOS, required = false) Long receivedAtNanos) {
        Timer.Sample sample = Timer.start(meters);
        SubmitAnswer.Result result = submitAnswer.submit(sessionId, UUID.fromString(player.getName()),
                request.questionId(), request.optionId());
        // Stopped only once the outcome is known. An answer that throws (not a player, Redis down) gets no ack and
        // isn't timed: it is a reply on /user/queue/errors instead.
        sample.stop(handling.get(result.reason()));
        messaging.convertAndSendToUser(player.getName(), ACK_QUEUE, AnswerAck.of(result),
                ackHeaders(connectionId, result.reason(), receivedAtNanos));
    }

    /**
     * What @SendToUser(broadcast = false) did: the session id header makes Spring's user-destination resolution
     * deliver to that one connection only, not to every connection of the user. leaveMutable lets the template use
     * these headers as they are.
     */
    private static MessageHeaders ackHeaders(String connectionId, SubmitAnswer.Reason outcome, Long receivedAtNanos) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(connectionId);
        if (receivedAtNanos != null) {
            headers.setHeader(RECEIVED_AT_NANOS, receivedAtNanos);
            headers.setHeader(OUTCOME, outcome.name());
        }
        headers.setLeaveMutable(true);
        return headers.getMessageHeaders();
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
