package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.SessionBroadcaster.AnswerRevealed;
import dev.nibin.buzzer.session.application.SessionBroadcaster.LeaderboardChanged;
import dev.nibin.buzzer.session.application.SessionBroadcaster.QuestionShown;
import dev.nibin.buzzer.session.application.SessionBroadcaster.StatusChanged;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.infrastructure.websocket.LocalStompDelivery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The relay's two ends without Redis: what the broadcaster publishes is exactly what the listener can read back,
 * and neither end throws on failure. MultiInstanceBroadcastTest covers the real channel between two instances.
 */
class RedisRelayTest {

    private static final byte[] CHANNEL = RedisRelayListener.CHANNEL.getBytes(StandardCharsets.UTF_8);

    private final JsonMapper json = JsonMapper.builder().build();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LocalStompDelivery delivery = mock(LocalStompDelivery.class);

    private final RedisRelayBroadcaster broadcaster = new RedisRelayBroadcaster(redis, json);
    private final RedisRelayListener listener = new RedisRelayListener(delivery, json);

    @Test
    void aQuestionComesOutOfTheChannelExactlyAsItWentIn() {
        QuestionShown question = new QuestionShown(UUID.randomUUID(), 0, UUID.randomUUID(), "Capital of France?",
                20, List.of("Paris", "Lyon"), Instant.parse("2026-09-28T10:15:30.123Z"));

        broadcaster.questionShown(question);
        String published = published();
        listener.onMessage(message(published), null);

        verify(delivery).questionShown(question);
        verifyNoMoreInteractions(delivery);
        assertThat(published).doesNotContainIgnoringCase("correct"); // a shown question never carries its answer
    }

    @Test
    void aRevealComesOutOfTheChannelExactlyAsItWentIn() {
        AnswerRevealed revealed = new AnswerRevealed(UUID.randomUUID(), 2, UUID.randomUUID(), 1);

        broadcaster.answerRevealed(revealed);
        listener.onMessage(message(published()), null);

        verify(delivery).answerRevealed(revealed);
        verifyNoMoreInteractions(delivery);
    }

    @Test
    void aStatusChangeComesOutOfTheChannelExactlyAsItWentIn() {
        StatusChanged change = new StatusChanged(UUID.randomUUID(), Session.Status.ENDED);

        broadcaster.statusChanged(change);
        listener.onMessage(message(published()), null);

        verify(delivery).statusChanged(change);
        verifyNoMoreInteractions(delivery);
    }

    @Test
    void aLeaderboardComesOutOfTheChannelExactlyAsItWentIn() {
        LeaderboardChanged leaderboard = new LeaderboardChanged(UUID.randomUUID(), 12, List.of(
                new LeaderboardChanged.Line(1, UUID.randomUUID(), 1000),
                new LeaderboardChanged.Line(2, UUID.randomUUID(), 900)));

        broadcaster.leaderboardChanged(leaderboard);
        listener.onMessage(message(published()), null);

        verify(delivery).leaderboardChanged(leaderboard);
        verifyNoMoreInteractions(delivery);
    }

    @Test
    void anUnreadableMessageIsDroppedWithoutStoppingTheListener() {
        assertThatCode(() -> listener.onMessage(message("not json"), null)).doesNotThrowAnyException();
        assertThatCode(() -> listener.onMessage(message("{}"), null)).doesNotThrowAnyException();

        verifyNoInteractions(delivery);
    }

    @Test
    void aFailedPublishIsLoggedNotThrownBecauseTheChangeIsAlreadyCommitted() {
        when(redis.convertAndSend(any(), any())).thenThrow(new RedisConnectionFailureException("Redis is down"));

        assertThatCode(() -> broadcaster.statusChanged(new StatusChanged(UUID.randomUUID(), Session.Status.ENDED)))
                .doesNotThrowAnyException();
    }

    /** The JSON the broadcaster PUBLISHed on the relay channel. */
    private String published() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(RedisRelayListener.CHANNEL), body.capture());
        return body.getValue();
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage(CHANNEL, body.getBytes(StandardCharsets.UTF_8));
    }
}
