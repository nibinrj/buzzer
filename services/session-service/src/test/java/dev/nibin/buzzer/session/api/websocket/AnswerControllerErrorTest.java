package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.application.SessionNotFoundException;
import dev.nibin.buzzer.session.application.SubmitAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AnswerController's error replies. SubmitAnswer is mocked, because neither error can be produced for real in the
 * shared test context: Redis would have to be down, and the destination check already stops non-players before a
 * SEND reaches the controller (SessionNotFoundException is the controller's own second line of defence).
 * <p>
 * No server, no WebSocket: a frame goes straight into Spring's real @MessageMapping dispatcher
 * (SimpAnnotationMethodMessageHandler), and whatever the controller sends back is captured where the broker would
 * receive it. So what is tested is the real routing: exception → @MessageExceptionHandler → @SendToUser destination.
 */
class AnswerControllerErrorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SubmitAnswer submitAnswer = mock(SubmitAnswer.class);
    private final List<Message<?>> toBroker = new ArrayList<>();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        SimpMessagingTemplate brokerTemplate = new SimpMessagingTemplate(capturing(toBroker));
        brokerTemplate.setMessageConverter(new JacksonJsonMessageConverter());
        dispatcher = new Dispatcher(new ExecutorSubscribableChannel(), capturing(new ArrayList<>()), brokerTemplate);
        dispatcher.setDestinationPrefixes(List.of("/app"));
        dispatcher.setMessageConverter(new JacksonJsonMessageConverter());
        dispatcher.setApplicationContext(new StaticApplicationContext());
        dispatcher.afterPropertiesSet();
        dispatcher.register(new AnswerController(submitAnswer));
    }

    @Test
    void anAnswerIsAckedOnTheSendersAnswerAckQueue() {
        UUID question = UUID.randomUUID();
        when(submitAnswer.submit(any(), any(), any(), any()))
                .thenReturn(new SubmitAnswer.Result(question, SubmitAnswer.Reason.ACCEPTED, 3));

        sendAnswer(question);

        assertThat(destinationOfReply()).isEqualTo("/user/" + user + "/queue/answer-ack");
        assertThat(replyBody().get("seq").asLong()).isEqualTo(3);
    }

    @Test
    void sessionNotFoundIsReportedOnTheErrorQueueNotAsAnAck() {
        when(submitAnswer.submit(any(), any(), any(), any())).thenThrow(new SessionNotFoundException());

        sendAnswer(UUID.randomUUID());

        assertThat(destinationOfReply()).isEqualTo("/user/" + user + "/queue/errors");
        assertThat(replyBody().get("title").asString()).isEqualTo("Session not found");
    }

    @Test
    void redisDownIsReportedOnTheErrorQueueAndAsksForARetry() {
        when(submitAnswer.submit(any(), any(), any(), any()))
                .thenThrow(new LiveStateUnavailableException(new RuntimeException("down")));

        sendAnswer(UUID.randomUUID());

        assertThat(destinationOfReply()).isEqualTo("/user/" + user + "/queue/errors");
        assertThat(replyBody().get("title").asString()).isEqualTo("Game state unavailable");
        assertThat(replyBody().get("detail").asString()).contains("Send it again");
    }

    // --- helpers ---

    /** A SEND frame as the inbound channel delivers it: already authenticated (user set on CONNECT). */
    private void sendAnswer(UUID questionId) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setDestination("/app/sessions/" + sessionId + "/answer");
        headers.setSessionId("connection-1");
        headers.setSessionAttributes(new HashMap<>());
        Principal principal = user::toString;
        headers.setUser(principal);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        byte[] body = JSON.writeValueAsBytes(new AnswerController.AnswerRequest(questionId, 1));
        dispatcher.handleMessage(MessageBuilder.createMessage(body, headers.getMessageHeaders()));
    }

    private String destinationOfReply() {
        assertThat(toBroker).hasSize(1);
        return SimpMessageHeaderAccessor.getDestination(toBroker.getFirst().getHeaders());
    }

    private JsonNode replyBody() {
        return JSON.readTree(new String((byte[]) toBroker.getFirst().getPayload(), StandardCharsets.UTF_8));
    }

    private static MessageChannel capturing(List<Message<?>> into) {
        return (message, timeout) -> into.add(message);
    }

    /** The dispatcher, with its handler detection opened up so it can be given one controller directly. */
    private static final class Dispatcher extends SimpAnnotationMethodMessageHandler {

        Dispatcher(SubscribableChannel clientInbound, MessageChannel clientOutbound,
                SimpMessageSendingOperations brokerTemplate) {
            super(clientInbound, clientOutbound, brokerTemplate);
        }

        void register(Object controller) {
            detectHandlerMethods(controller);
        }
    }
}
