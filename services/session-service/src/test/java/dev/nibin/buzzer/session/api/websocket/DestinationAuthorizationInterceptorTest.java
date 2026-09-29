package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.application.SessionAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The destination rules as a table, with SessionAccess mocked. */
class DestinationAuthorizationInterceptorTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private final SessionAccess access = mock(SessionAccess.class);
    private final DestinationAuthorizationInterceptor interceptor = new DestinationAuthorizationInterceptor(access);

    DestinationAuthorizationInterceptorTest() {
        when(access.isHost(SESSION, HOST)).thenReturn(true);
        when(access.isHostOrPlayer(SESSION, HOST)).thenReturn(true);
        when(access.isHostOrPlayer(SESSION, PLAYER)).thenReturn(true);
        when(access.isPlayer(SESSION, PLAYER)).thenReturn(true);
    }

    @Test
    void theSessionsTopicsAreForItsHostAndPlayers() {
        assertThat(interceptor.maySubscribe(topic("question"), user(PLAYER))).isTrue();
        assertThat(interceptor.maySubscribe(topic("status"), user(HOST))).isTrue();
        assertThat(interceptor.maySubscribe("/topic/sessions/" + SESSION, user(PLAYER))).isTrue();
        assertThat(interceptor.maySubscribe(topic("question"), user(STRANGER))).isFalse();
    }

    @Test
    void theLeaderboardIsForTheSessionsHostAndPlayersOnly() {
        assertThat(interceptor.maySubscribe(topic("leaderboard"), user(PLAYER))).isTrue();
        assertThat(interceptor.maySubscribe(topic("leaderboard"), user(HOST))).isTrue();
        assertThat(interceptor.maySubscribe(topic("leaderboard"), user(STRANGER))).isFalse();
    }

    @Test
    void anyConnectedUserMaySubscribeToTheirOwnQueues() {
        assertThat(interceptor.maySubscribe("/user/queue/errors", user(STRANGER))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/other", "/topic/sessions/not-a-uuid/question", "/queue/errors",
            "/user/topic/x", "/app/sessions/x/start"})
    void everyOtherSubscriptionIsRefused(String destination) {
        assertThat(interceptor.maySubscribe(destination, user(HOST))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "next", "reveal", "end"})
    void hostCommandsAreForTheHostOnly(String command) {
        String destination = "/app/sessions/" + SESSION + "/" + command;
        assertThat(interceptor.maySend(destination, user(HOST))).isTrue();
        assertThat(interceptor.maySend(destination, user(PLAYER))).isFalse();
    }

    @Test
    void answersAreForThePlayersOnlyNotTheHost() {
        String destination = "/app/sessions/" + SESSION + "/answer";
        assertThat(interceptor.maySend(destination, user(PLAYER))).isTrue();
        assertThat(interceptor.maySend(destination, user(HOST))).isFalse();
        assertThat(interceptor.maySend(destination, user(STRANGER))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/sessions/%s/question", "/queue/errors", "/app/sessions/%s/pause",
            "/app/sessions/%s/start/extra", "/app/sessions/%s/answer/extra", "/app/sessions/not-a-uuid/answer",
            "/user/queue/errors"})
    void everyOtherSendIsRefusedEvenForTheHost(String destination) {
        assertThat(interceptor.maySend(destination.formatted(SESSION), user(HOST))).isFalse();
    }

    @Test
    void withoutAUserNothingIsAllowed() {
        assertThat(interceptor.maySubscribe("/user/queue/errors", null)).isFalse();
        assertThat(interceptor.maySend("/app/sessions/" + SESSION + "/start", null)).isFalse();
    }

    @Test
    void aRefusalThrowsSoSpringAnswersWithAnErrorFrame() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(topic("question"));
        accessor.setUser(user(STRANGER));

        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), mock(MessageChannel.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static String topic(String name) {
        return "/topic/sessions/" + SESSION + "/" + name;
    }

    private static Principal user(UUID id) {
        return id::toString;
    }
}
