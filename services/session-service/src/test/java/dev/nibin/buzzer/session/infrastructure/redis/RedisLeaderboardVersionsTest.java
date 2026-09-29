package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.LeaderboardVersions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** accept_leaderboard_version.lua against a real Redis 7. Every test uses a new session id. */
@ApiIntegrationTest
class RedisLeaderboardVersionsTest {

    @Autowired
    private LeaderboardVersions versions;

    @Autowired
    private StringRedisTemplate redis;

    private final UUID session = UUID.randomUUID();

    @Test
    void theFirstVersionOfASessionIsAccepted() {
        assertThat(versions.acceptIfNotOlder(session, 5)).isTrue();

        assertThat(stored()).isEqualTo("5");
    }

    @Test
    void aNewerVersionIsAcceptedAndStored() {
        versions.acceptIfNotOlder(session, 5);

        assertThat(versions.acceptIfNotOlder(session, 6)).isTrue();
        assertThat(stored()).isEqualTo("6");
    }

    @Test
    void theSameVersionAgainIsAccepted() {
        versions.acceptIfNotOlder(session, 5);

        assertThat(versions.acceptIfNotOlder(session, 5)).isTrue();
    }

    @Test
    void anOlderVersionIsRefusedAndLeavesTheStoredOneAlone() {
        versions.acceptIfNotOlder(session, 12);

        assertThat(versions.acceptIfNotOlder(session, 9)).isFalse();
        assertThat(stored()).isEqualTo("12");
    }

    @Test
    void versionsAreComparedAsNumbersNotAsText() {
        versions.acceptIfNotOlder(session, 9);

        assertThat(versions.acceptIfNotOlder(session, 10)).isTrue(); // as text, "10" < "9"
    }

    @Test
    void eachSessionHasItsOwnVersion() {
        versions.acceptIfNotOlder(session, 100);

        assertThat(versions.acceptIfNotOlder(UUID.randomUUID(), 1)).isTrue();
    }

    @Test
    void theKeyHasTheSessionHashTagAndExpiresWithTheLiveState() {
        versions.acceptIfNotOlder(session, 1);

        String key = SessionKeys.leaderboardVersion(session);
        assertThat(key).isEqualTo("session:{" + session + "}:leaderboard-version");
        Duration ttl = Duration.ofSeconds(redis.getExpire(key));
        assertThat(ttl).isBetween(Duration.ofHours(6).minusMinutes(1), Duration.ofHours(6));
    }

    private String stored() {
        return redis.opsForValue().get(SessionKeys.leaderboardVersion(session));
    }
}
