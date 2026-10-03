package dev.nibin.buzzer.session.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.nibin.buzzer.session.application.SessionFacts;
import dev.nibin.buzzer.session.domain.Player;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.UUID;

/**
 * SessionFacts' two in-memory caches (Caffeine), and their hit/miss metrics.
 * <p>
 * Bounded so memory can't grow with the number of games ever played: 1,000 sessions (a few KB each: 20–50
 * questions) and 50,000 memberships (100 sessions of 500 players; a few hundred bytes each). An entry unused for
 * as long as a session's live state lives in Redis (session.live-state.ttl) is dropped: by then the game is over.
 * <p>
 * Metrics: cache_gets_total{cache, result=hit|miss}, cache_evictions_total, cache_size, among others. The hit ratio
 * is the proof that answers stopped reading Postgres.
 */
@Configuration(proxyBeanMethods = false)
public class SessionFactsConfig {

    private static final long MAX_SESSIONS = 1_000;
    private static final long MAX_MEMBERSHIPS = 50_000;

    @Bean
    Cache<UUID, SessionFacts.Frozen> frozenSessions(@Value("${session.live-state.ttl}") Duration ttl) {
        return cache(MAX_SESSIONS, ttl);
    }

    @Bean
    Cache<SessionFacts.Membership, Player> memberships(@Value("${session.live-state.ttl}") Duration ttl) {
        return cache(MAX_MEMBERSHIPS, ttl);
    }

    @Bean
    MeterBinder sessionFactsCacheMetrics(Cache<UUID, SessionFacts.Frozen> frozenSessions,
            Cache<SessionFacts.Membership, Player> memberships) {
        return registry -> {
            CaffeineCacheMetrics.monitor(registry, frozenSessions, "session.facts.sessions");
            CaffeineCacheMetrics.monitor(registry, memberships, "session.facts.memberships");
        };
    }

    // recordStats: Caffeine counts hits and misses only when asked to; the metrics above read those counts.
    private static <K, V> Cache<K, V> cache(long maximumSize, Duration expireAfterAccess) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterAccess(expireAfterAccess)
                .recordStats()
                .build();
    }
}
