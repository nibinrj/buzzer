package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.nibin.buzzer.session.infrastructure.redis.RedisLiveStateRepository.playersKey;
import static dev.nibin.buzzer.session.infrastructure.redis.RedisLiveStateRepository.stateKey;
import static org.assertj.core.api.Assertions.assertThat;

/** Against a real Redis 7: key layout, TTLs, and the no-overwrite rules of restore. Every test uses a new session id. */
@ApiIntegrationTest
class RedisLiveStateRepositoryTest {

    private static final Duration SIX_HOURS = Duration.ofHours(6);

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void aNewSessionIsInTheLobbyWithNobodyAndExpiresInSixHours() {
        UUID sessionId = UUID.randomUUID();

        liveState.initialize(sessionId, Session.Status.LOBBY);

        assertThat(liveState.find(sessionId)).contains(LiveState.of(sessionId, Session.Status.LOBBY, List.of()));
        assertExpiresInAboutSixHours(stateKey(sessionId));
    }

    @Test
    void playersAreListedByNameAndBothKeysGetTheTtl() {
        UUID sessionId = UUID.randomUUID();
        liveState.initialize(sessionId, Session.Status.LOBBY);
        RosterEntry zoe = new RosterEntry(UUID.randomUUID(), "zoe");
        RosterEntry ada = new RosterEntry(UUID.randomUUID(), "Ada");

        liveState.addPlayer(sessionId, zoe);
        liveState.addPlayer(sessionId, ada);
        liveState.addPlayer(sessionId, ada); // a retried join: still one entry

        assertThat(liveState.find(sessionId).orElseThrow().players()).containsExactly(ada, zoe);
        assertExpiresInAboutSixHours(stateKey(sessionId));
        assertExpiresInAboutSixHours(playersKey(sessionId));
    }

    @Test
    void nothingStoredIsEmpty() {
        assertThat(liveState.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRosterWithoutStateCountsAsMissing() {
        UUID sessionId = UUID.randomUUID();

        liveState.addPlayer(sessionId, new RosterEntry(UUID.randomUUID(), "Ada"));

        assertThat(liveState.find(sessionId)).isEmpty();
    }

    @Test
    void restoreKeepsNewerStatusAndOnlyAddsPlayers() {
        UUID sessionId = UUID.randomUUID();
        RosterEntry joinedMeanwhile = new RosterEntry(UUID.randomUUID(), "Bob");
        RosterEntry onRecord = new RosterEntry(UUID.randomUUID(), "Ada");
        // Written while the rebuild was reading Postgres: a start, and a join.
        redis.opsForHash().put(stateKey(sessionId), "status", "IN_PROGRESS");
        liveState.addPlayer(sessionId, joinedMeanwhile);

        liveState.restore(LiveState.of(sessionId, Session.Status.LOBBY, List.of(onRecord)));

        LiveState state = liveState.find(sessionId).orElseThrow();
        assertThat(state.status()).isEqualTo(Session.Status.IN_PROGRESS);
        assertThat(state.players()).containsExactly(onRecord, joinedMeanwhile);
        assertExpiresInAboutSixHours(stateKey(sessionId));
    }

    @Test
    void restoreOfAnEmptyRosterWritesTheStatus() {
        UUID sessionId = UUID.randomUUID();

        liveState.restore(LiveState.of(sessionId, Session.Status.ENDED, List.of()));

        assertThat(liveState.find(sessionId)).contains(LiveState.of(sessionId, Session.Status.ENDED, List.of()));
    }

    @Test
    void readsTheQuestionPointerAndDeadline() {
        UUID sessionId = UUID.randomUUID();
        Instant deadline = Instant.parse("2026-09-28T10:00:15Z");
        // The format batch 3.4 will write.
        redis.opsForHash().putAll(stateKey(sessionId), Map.of("status", "IN_PROGRESS", "questionIndex", "2",
                "questionDeadline", String.valueOf(deadline.toEpochMilli())));

        LiveState state = liveState.find(sessionId).orElseThrow();

        assertThat(state.currentQuestionIndex()).contains(2);
        assertThat(state.questionDeadline()).isEqualTo(Optional.of(deadline));
    }

    @Test
    void serverTimeIsRedisClock() {
        // Container and JVM run on the same machine here, so the clocks agree within a second.
        assertThat(Duration.between(liveState.serverTime(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void showQuestionSetsStatusPointerAndDeadline() {
        UUID sessionId = UUID.randomUUID();
        liveState.initialize(sessionId, Session.Status.LOBBY);
        Instant deadline = Instant.parse("2026-09-28T10:00:20Z");

        liveState.showQuestion(sessionId, 3, deadline);

        LiveState state = liveState.find(sessionId).orElseThrow();
        assertThat(state.status()).isEqualTo(Session.Status.IN_PROGRESS);
        assertThat(state.currentQuestionIndex()).contains(3);
        assertThat(state.questionDeadline()).contains(deadline);
        assertExpiresInAboutSixHours(stateKey(sessionId));
    }

    @Test
    void endClearsTheQuestionPointerAndDeadline() {
        UUID sessionId = UUID.randomUUID();
        liveState.showQuestion(sessionId, 0, Instant.parse("2026-09-28T10:00:20Z"));

        liveState.end(sessionId);

        assertThat(liveState.find(sessionId)).contains(LiveState.of(sessionId, Session.Status.ENDED, List.of()));
        assertThat(redis.opsForHash().keys(stateKey(sessionId))).containsExactly("status");
    }

    private void assertExpiresInAboutSixHours(String key) {
        Long seconds = redis.getExpire(key);
        assertThat(seconds).isBetween(SIX_HOURS.minusMinutes(1).toSeconds(), SIX_HOURS.toSeconds());
    }
}
