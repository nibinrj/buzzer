package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What a STOMP frame puts into MDC while it is handled, and that it all goes again afterwards. */
class StompLogContextInterceptorTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final StompLogContextInterceptor interceptor = new StompLogContextInterceptor();
    private final MessageChannel channel = mock(MessageChannel.class);
    private final MessageHandler handler = mock(MessageHandler.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/sessions/%s/answer", "/app/sessions/%s/start", "/topic/sessions/%s",
            "/topic/sessions/%s/leaderboard"})
    void aFrameForASessionIsLabelledWithTheSessionAndTheUser(String destination) {
        interceptor.beforeHandle(frame(StompCommand.SEND, destination.formatted(SESSION), USER), channel, handler);

        assertThat(MDC.get(LogContext.SESSION_ID)).isEqualTo(SESSION.toString());
        assertThat(MDC.get(LogContext.USER_ID)).isEqualTo(USER.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/queue/answer-ack", "/app/sessions/not-a-uuid/answer", "/topic/other"})
    void otherDestinationsCarryNoSession(String destination) {
        interceptor.beforeHandle(frame(StompCommand.SUBSCRIBE, destination, USER), channel, handler);

        assertThat(MDC.get(LogContext.SESSION_ID)).isNull();
        assertThat(MDC.get(LogContext.USER_ID)).isEqualTo(USER.toString());
    }

    @Test
    void aFrameWithoutAUserCarriesNoUser() {
        interceptor.beforeHandle(frame(StompCommand.CONNECT, null, null), channel, handler);

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void afterHandlingBothAreRemovedEvenWhenTheHandlerThrewAndTracingsEntriesStay() {
        MDC.put("traceId", "a-trace");
        Message<byte[]> answer = frame(StompCommand.SEND, "/app/sessions/" + SESSION + "/answer", USER);
        interceptor.beforeHandle(answer, channel, handler);

        interceptor.afterMessageHandled(answer, channel, handler, new IllegalStateException("handler failed"));

        assertThat(MDC.get(LogContext.SESSION_ID)).isNull();
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("a-trace");
    }

    private static Message<byte[]> frame(StompCommand command, String destination, UUID user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (user != null) {
            Principal principal = user::toString;
            accessor.setUser(principal);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
