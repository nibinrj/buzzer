package dev.nibin.buzzer.scoring.infrastructure.redis;

import dev.nibin.buzzer.scoring.domain.Leaderboard;
import dev.nibin.buzzer.scoring.domain.PlayerPoints;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The leaderboards as Redis sorted sets, one per session: {@code leaderboard:{sessionId}}, member = playerId,
 * score = total points. The rules live in lua/update_leaderboard.lua and lua/rebuild_leaderboard.lua; this class
 * passes arguments and reads replies.
 * <p>
 * The session id is in literal braces, a Redis Cluster hash tag, like every session-service key: a script may only
 * touch keys of one slot, and this keeps the option of adding per-session keys to these scripts later.
 * <p>
 * Each script object is built once and sent as EVALSHA; after a Redis restart (NOSCRIPT) Spring Data Redis resends
 * it once as EVAL.
 */
@Component
class RedisLeaderboard implements Leaderboard {

    @SuppressWarnings("rawtypes") // the reply mixes Strings and a Long; List.class is the only fitting result type
    private static final RedisScript<List> UPDATE =
            RedisScript.of(new ClassPathResource("lua/update_leaderboard.lua"), List.class);
    private static final RedisScript<Long> REBUILD =
            RedisScript.of(new ClassPathResource("lua/rebuild_leaderboard.lua"), Long.class);

    /**
     * Lost Redis copies restored from Postgres, by either path (an answer, or GET /leaderboard); see rebuild for what
     * isn't counted. A few after a Redis restart are expected; a steady rate means keys expire mid-game
     * (scoring.leaderboard.ttl too short).
     */
    static final String REBUILDS = "buzzer.scoring.leaderboard.rebuilds";

    private final StringRedisTemplate redis;
    private final String ttlSeconds;
    private final Counter rebuilds;

    RedisLeaderboard(StringRedisTemplate redis, @Value("${scoring.leaderboard.ttl}") Duration ttl,
            MeterRegistry meters) {
        this.redis = redis;
        this.ttlSeconds = String.valueOf(ttl.toSeconds());
        this.rebuilds = Counter.builder(REBUILDS)
                .description("Redis leaderboards rebuilt from Postgres")
                .register(meters);
    }

    static String key(UUID sessionId) {
        return "leaderboard:{" + sessionId + "}";
    }

    /**
     * Reply: {'MISSING'} or {'OK', position, player1, points1, ...}. Strings arrive as Strings (the template's
     * String serializer), the integer position as a Long.
     */
    @Override
    public Optional<Placement> raise(UUID sessionId, PlayerPoints total) {
        List<?> reply = redis.execute(UPDATE, List.of(key(sessionId)),
                total.playerId().toString(), String.valueOf(total.points()), ttlSeconds, String.valueOf(SIZE));
        if (reply == null || reply.isEmpty()) {
            throw new IllegalStateException("update_leaderboard.lua returned " + reply);
        }
        if ("MISSING".equals(reply.get(0))) {
            return Optional.empty();
        }
        long position = ((Number) reply.get(1)).longValue();
        List<PlayerPoints> top = new ArrayList<>();
        for (int i = 2; i + 1 < reply.size(); i += 2) {
            top.add(new PlayerPoints(UUID.fromString((String) reply.get(i)), points((String) reply.get(i + 1))));
        }
        return Optional.of(new Placement(position, List.copyOf(top)));
    }

    @Override
    public void rebuild(UUID sessionId, List<PlayerPoints> totals) {
        List<String> args = new ArrayList<>(1 + 2 * totals.size());
        args.add(ttlSeconds);
        for (PlayerPoints total : totals) {
            args.add(total.playerId().toString());
            args.add(String.valueOf(total.points()));
        }
        redis.execute(REBUILD, List.of(key(sessionId)), args.toArray());
        // A session's FIRST answer comes through here too (its key doesn't exist yet), always with exactly one total:
        // one partition, one consumer thread, so no second answer can be committed before it. Only a rebuild that
        // restores more than one player's total is a lost copy that mattered. Counted after the script: one that
        // threw didn't happen.
        if (totals.size() > 1) {
            rebuilds.increment();
        }
    }

    /** Redis never stores an empty sorted set, so an empty reply means the key doesn't exist. */
    @Override
    public Optional<List<PlayerPoints>> top(UUID sessionId) {
        Set<TypedTuple<String>> best = redis.opsForZSet().reverseRangeWithScores(key(sessionId), 0, SIZE - 1);
        if (best == null || best.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(best.stream()
                .map(tuple -> new PlayerPoints(UUID.fromString(tuple.getValue()), tuple.getScore().intValue()))
                .toList());
    }

    /** Sorted-set scores are doubles; Redis prints whole ones without a fraction ("1000"). */
    private static int points(String score) {
        return (int) Double.parseDouble(score);
    }
}
