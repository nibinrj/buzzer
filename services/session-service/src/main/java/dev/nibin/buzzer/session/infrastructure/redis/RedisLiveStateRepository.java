package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Live state in Redis, two hashes per session:
 * <pre>
 * session:{id}:state    status → LOBBY | IN_PROGRESS | ENDED
 *                       questionIndex → 0-based index      (only while a question runs)
 *                       questionDeadline → epoch millis    (only while a question runs; Redis clock)
 * session:{id}:players  {playerId} → display name
 * </pre>
 * Every write refreshes the time-to-live of BOTH keys, inside MULTI/EXEC: Redis runs the queued commands as one
 * unit, so no key is ever left without an expiry (which would keep it forever).
 */
@Component
class RedisLiveStateRepository implements LiveStateRepository {

    static final String STATUS = "status";
    static final String QUESTION_INDEX = "questionIndex";
    static final String QUESTION_DEADLINE = "questionDeadline";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RedisLiveStateRepository(StringRedisTemplate redis, @Value("${session.live-state.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    static String stateKey(UUID sessionId) {
        return "session:" + sessionId + ":state";
    }

    static String playersKey(UUID sessionId) {
        return "session:" + sessionId + ":players";
    }

    @Override
    public void initialize(UUID sessionId, Session.Status status) {
        inTransaction(sessionId, ops -> ops.opsForHash().put(stateKey(sessionId), STATUS, status.name()));
    }

    @Override
    public void addPlayer(UUID sessionId, RosterEntry player) {
        inTransaction(sessionId, ops -> ops.opsForHash()
                .put(playersKey(sessionId), player.playerId().toString(), player.displayName()));
    }

    @Override
    public void restore(LiveState rebuilt) {
        UUID sessionId = rebuilt.sessionId();
        Map<String, String> roster = rebuilt.players().stream()
                .collect(Collectors.toMap(entry -> entry.playerId().toString(), RosterEntry::displayName));
        inTransaction(sessionId, ops -> {
            // HSETNX: a status written since the rebuild read Postgres (e.g. a start) is newer; keep it.
            ops.opsForHash().putIfAbsent(stateKey(sessionId), STATUS, rebuilt.status().name());
            if (!roster.isEmpty()) {
                // Only adds: players never leave, so entries already here are never wrong.
                ops.opsForHash().putAll(playersKey(sessionId), roster);
            }
        });
    }

    @Override
    public Optional<LiveState> find(UUID sessionId) {
        try {
            Map<String, String> state = redis.<String, String>opsForHash().entries(stateKey(sessionId));
            if (state.isEmpty()) {
                // No state hash (a roster alone can exist if Redis lost data mid-game): treat as missing.
                return Optional.empty();
            }
            List<RosterEntry> players = redis.<String, String>opsForHash().entries(playersKey(sessionId))
                    .entrySet().stream()
                    .map(entry -> new RosterEntry(UUID.fromString(entry.getKey()), entry.getValue()))
                    // A hash has no order; sort so every client sees the same list.
                    .sorted(Comparator.comparing(RosterEntry::displayName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(RosterEntry::playerId))
                    .toList();
            return Optional.of(new LiveState(sessionId,
                    Session.Status.valueOf(state.get(STATUS)),
                    Optional.ofNullable(state.get(QUESTION_INDEX)).map(Integer::valueOf),
                    Optional.ofNullable(state.get(QUESTION_DEADLINE)).map(Long::parseLong).map(Instant::ofEpochMilli),
                    players));
        } catch (DataAccessException e) {
            throw new LiveStateUnavailableException(e);
        }
    }

    @Override
    public Instant serverTime() {
        try {
            Long millis = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
            return Instant.ofEpochMilli(Objects.requireNonNull(millis));
        } catch (DataAccessException e) {
            throw new LiveStateUnavailableException(e);
        }
    }

    @Override
    public void showQuestion(UUID sessionId, int index, Instant deadline) {
        inTransaction(sessionId, ops -> ops.opsForHash().putAll(stateKey(sessionId), Map.of(
                STATUS, Session.Status.IN_PROGRESS.name(),
                QUESTION_INDEX, String.valueOf(index),
                QUESTION_DEADLINE, String.valueOf(deadline.toEpochMilli()))));
    }

    @Override
    public void end(UUID sessionId) {
        inTransaction(sessionId, ops -> {
            ops.opsForHash().put(stateKey(sessionId), STATUS, Session.Status.ENDED.name());
            ops.opsForHash().delete(stateKey(sessionId), QUESTION_INDEX, QUESTION_DEADLINE);
        });
    }

    /** Runs the writes, then refreshes both keys' TTL, all in one MULTI/EXEC. */
    private void inTransaction(UUID sessionId, Consumer<RedisOperations<String, String>> writes) {
        try {
            redis.execute(new SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings("unchecked") // StringRedisTemplate: keys and values are Strings
                public <K, V> List<Object> execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    ops.multi();
                    writes.accept(ops);
                    ops.expire(stateKey(sessionId), ttl);
                    ops.expire(playersKey(sessionId), ttl);
                    return ops.exec();
                }
            });
        } catch (DataAccessException e) {
            throw new LiveStateUnavailableException(e);
        }
    }
}
