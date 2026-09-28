package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisAnswerRegistry when Redis can't be reached or answers something the script never returns. No container: the
 * script's rules are RedisAnswerRegistryTest's job.
 */
class RedisAnswerRegistryFailureTest {

    private static final Duration TTL = Duration.ofHours(6);

    @Test
    void redisUnreachableIsLiveStateUnavailableSoTheCallerCanSayRetry() throws Exception {
        // A real client, pointed at a port nobody listens on: the connection is refused.
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", freePort()));
        factory.afterPropertiesSet();
        factory.start();
        try {
            RedisAnswerRegistry registry = new RedisAnswerRegistry(new StringRedisTemplate(factory), TTL);

            assertThatThrownBy(() -> register(registry)).isInstanceOf(LiveStateUnavailableException.class);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void aReplyOfTheWrongShapeIsAnErrorNotAGuess() {
        RedisAnswerRegistry registry = registryReplying(List.of("ACCEPTED", 1L, 1L)); // answeredAtMs missing

        assertThatThrownBy(() -> register(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submit_answer.lua returned");
    }

    @Test
    void noReplyAtAllIsAnErrorToo() {
        RedisAnswerRegistry registry = registryReplying(null);

        assertThatThrownBy(() -> register(registry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOutcomeTheCodeDoesNotKnowIsAnError() {
        RedisAnswerRegistry registry = registryReplying(List.of("MAYBE", 0L, 0L, 0L));

        assertThatThrownBy(() -> register(registry)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private static void register(RedisAnswerRegistry registry) {
        registry.register(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(), true);
    }

    /** A template whose script call returns {@code reply}, whatever the script and arguments. */
    @SuppressWarnings("unchecked")
    private static RedisAnswerRegistry registryReplying(List<?> reply) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // any(Object[].class) matches the whole varargs array (Mockito 5).
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(reply);
        return new RedisAnswerRegistry(redis, TTL);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
