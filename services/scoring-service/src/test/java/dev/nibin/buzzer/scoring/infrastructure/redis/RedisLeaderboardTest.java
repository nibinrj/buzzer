package dev.nibin.buzzer.scoring.infrastructure.redis;

import dev.nibin.buzzer.scoring.ScoringIntegrationTest;
import dev.nibin.buzzer.scoring.domain.Leaderboard;
import dev.nibin.buzzer.scoring.domain.Leaderboard.Placement;
import dev.nibin.buzzer.scoring.domain.PlayerPoints;
import dev.nibin.buzzer.scoring.domain.PlayerTotal;
import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The two leaderboard scripts against a real Redis 7 (and, for tie order, a real Postgres). */
@ScoringIntegrationTest
class RedisLeaderboardTest {

    @Autowired
    private Leaderboard leaderboard;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ScoringRepository scoring;

    private final UUID session = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID cid = UUID.randomUUID();

    @Test
    void theKeyCarriesTheSessionAsAClusterHashTag() {
        assertThat(RedisLeaderboard.key(session)).isEqualTo("leaderboard:{" + session + "}");
    }

    @Test
    void raisingOnAMissingLeaderboardReportsItMissingAndWritesNothing() {
        assertThat(leaderboard.raise(session, new PlayerPoints(ada, 1000))).isEmpty();

        assertThat(redis.hasKey(RedisLeaderboard.key(session))).isFalse(); // no leaderboard of one
    }

    @Test
    void afterARebuildARaiseAddsThePlayerAndReportsItsPlaceAndTheTop() {
        leaderboard.rebuild(session, List.of(new PlayerPoints(ada, 1000), new PlayerPoints(bob, 500)));

        Placement placement = leaderboard.raise(session, new PlayerPoints(cid, 700)).orElseThrow();

        assertThat(placement.position()).isEqualTo(1);
        assertThat(placement.inTop()).isTrue();
        assertThat(placement.top()).containsExactly(
                new PlayerPoints(ada, 1000), new PlayerPoints(cid, 700), new PlayerPoints(bob, 500));
    }

    @Test
    void aLowerTotalNeverReplacesAHigherOne() {
        leaderboard.rebuild(session, List.of(new PlayerPoints(ada, 1000)));

        leaderboard.raise(session, new PlayerPoints(ada, 400)); // a stale copy arriving late
        assertThat(leaderboard.top(session)).contains(List.of(new PlayerPoints(ada, 1000)));

        leaderboard.raise(session, new PlayerPoints(ada, 1200));
        assertThat(leaderboard.top(session)).contains(List.of(new PlayerPoints(ada, 1200)));
    }

    @Test
    void theTopIsTheBestTenBestFirstAndTheEleventhIsNotInIt() {
        List<PlayerPoints> twelve = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> new PlayerPoints(UUID.randomUUID(), i * 100)).toList(); // 100 .. 1200
        leaderboard.rebuild(session, twelve);

        Placement eleventh = leaderboard.raise(session, twelve.get(1)).orElseThrow(); // 200: 11th best

        assertThat(eleventh.position()).isEqualTo(10);
        assertThat(eleventh.inTop()).isFalse();
        assertThat(eleventh.top()).hasSize(Leaderboard.SIZE);
        assertThat(eleventh.top()).extracting(PlayerPoints::points)
                .containsExactly(1200, 1100, 1000, 900, 800, 700, 600, 500, 400, 300);
        assertThat(leaderboard.top(session).orElseThrow()).isEqualTo(eleventh.top());
    }

    @Test
    void redisAndPostgresPutEqualPointsInTheSameOrder() {
        // 10 players on 500 points: the order among them must match between the live leaderboard (Redis) and the
        // final results (SQL), or a player could "move" when the game ends. With 10 random ids a mismatch shows.
        List<UUID> players = new ArrayList<>(IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList());
        for (UUID player : players) {
            scoring.addAnswer(session, player, 500, true);
        }
        List<PlayerTotal> fromPostgres = scoring.totals(session);
        leaderboard.rebuild(session, fromPostgres.stream().map(PlayerTotal::toPoints).toList());

        List<UUID> fromRedis = leaderboard.top(session).orElseThrow().stream().map(PlayerPoints::playerId).toList();

        assertThat(fromRedis).isEqualTo(fromPostgres.stream().map(PlayerTotal::playerId).toList());
        // And that order is the ids' text, descending (UUID.compareTo would compare signed longs instead).
        players.sort(Comparator.comparing(UUID::toString).reversed());
        assertThat(fromRedis).isEqualTo(players);
    }

    @Test
    void theKeyExpiresAfterARebuildAndAfterEveryRaise() {
        String key = RedisLeaderboard.key(session);

        leaderboard.rebuild(session, List.of(new PlayerPoints(ada, 1000)));
        assertExpiresInAboutADay(key);

        redis.persist(key);
        leaderboard.raise(session, new PlayerPoints(bob, 900));
        assertExpiresInAboutADay(key);
    }

    @Test
    void rebuildingWithNoTotalsCreatesNothing() {
        leaderboard.rebuild(session, List.of());

        assertThat(redis.hasKey(RedisLeaderboard.key(session))).isFalse();
        assertThat(leaderboard.top(session)).isEmpty();
    }

    private void assertExpiresInAboutADay(String key) {
        Duration ttl = Duration.ofSeconds(redis.getExpire(key));
        assertThat(ttl).isBetween(Duration.ofHours(24).minusMinutes(1), Duration.ofHours(24));
    }
}
