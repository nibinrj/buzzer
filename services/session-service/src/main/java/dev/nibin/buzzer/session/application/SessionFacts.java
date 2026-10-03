package dev.nibin.buzzer.session.application;

import com.github.benmanes.caffeine.cache.Cache;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The facts about a session that never change once written, read from Postgres once and then kept in memory:
 * <ul>
 *   <li>its host and its questions (a frozen copy of the quiz, taken when the session was created);</li>
 *   <li>who joined it (there is no leave or kick: a membership, once there, stays).</li>
 * </ul>
 * Every answer needs all three, and the STOMP destination check needs them too. Reading them from Postgres each
 * time cost ~4.7 pool connections per answer, and at 200 players that made the 10-connection pool the first
 * bottleneck (docs/performance.md, H1). Now an answer needs one connection: the transaction that records it.
 * <p>
 * Nothing here is ever invalidated, because nothing cached can change. That also makes it safe with several
 * session-service instances: one that hasn't seen a session yet reads it once, and gets the same facts.
 * Only what EXISTS is cached. A lookup that finds nothing is asked again next time, so a player who joins after
 * an earlier miss is found. Never the whole Session: its status changes (start, end), and callers that need the
 * status still read SessionRepository.
 * <p>
 * The caches are bounded and expire after a while unused (SessionFactsConfig), so ended sessions fall out.
 */
@Service
public class SessionFacts {

    /** The part of a session that is fixed at creation. */
    public record Frozen(UUID hostId, List<SessionQuestion> questions) {
    }

    /** Key of a membership: a user in a session. */
    public record Membership(UUID sessionId, UUID userId) {
    }

    private final SessionRepository sessions;
    private final PlayerRepository players;
    private final Cache<UUID, Frozen> frozenSessions;
    private final Cache<Membership, Player> memberships;

    public SessionFacts(SessionRepository sessions, PlayerRepository players, Cache<UUID, Frozen> frozenSessions,
            Cache<Membership, Player> memberships) {
        this.sessions = sessions;
        this.players = players;
        this.frozenSessions = frozenSessions;
        this.memberships = memberships;
    }

    public Optional<UUID> hostOf(UUID sessionId) {
        return frozen(sessionId).map(Frozen::hostId);
    }

    public Optional<List<SessionQuestion>> questionsOf(UUID sessionId) {
        return frozen(sessionId).map(Frozen::questions);
    }

    /** The player this user is in this session, if they joined. */
    public Optional<Player> player(UUID sessionId, UUID userId) {
        // Caffeine records nothing when the function returns null: a "not a player" answer is never cached.
        return Optional.ofNullable(memberships.get(new Membership(sessionId, userId),
                key -> players.find(key.sessionId(), key.userId()).orElse(null)));
    }

    private Optional<Frozen> frozen(UUID sessionId) {
        return Optional.ofNullable(frozenSessions.get(sessionId,
                id -> sessions.findById(id).map(found -> new Frozen(found.hostId(), found.questions())).orElse(null)));
    }
}
