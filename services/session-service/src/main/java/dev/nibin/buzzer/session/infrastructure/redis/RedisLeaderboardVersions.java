package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.domain.LeaderboardVersions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Runs lua/accept_leaderboard_version.lua (the rule lives there). The key expires with the rest of the session's live
 * state; if it's gone, the next leaderboard is simply accepted.
 */
@Component
class RedisLeaderboardVersions implements LeaderboardVersions {

    private static final RedisScript<Long> ACCEPT =
            RedisScript.of(new ClassPathResource("lua/accept_leaderboard_version.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final String ttlSeconds;

    RedisLeaderboardVersions(StringRedisTemplate redis, @Value("${session.live-state.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttlSeconds = String.valueOf(ttl.toSeconds());
    }

    @Override
    public boolean acceptIfNotOlder(UUID sessionId, long version) {
        Long accepted = redis.execute(ACCEPT, List.of(SessionKeys.leaderboardVersion(sessionId)),
                String.valueOf(version), ttlSeconds);
        return accepted != null && accepted == 1L;
    }
}
