package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionFullException;
import dev.nibin.buzzer.session.domain.SessionNotJoinableException;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case: a player joins a session by its room code. Idempotent: joining again returns the same player,
 * so a client can safely retry after a timeout or a 503.
 * <p>
 * Two steps, in this order:
 * <ol>
 *   <li>Postgres, in one transaction that first locks the session row. The lock makes check-then-insert safe:
 *       two joins at 499 players run one after the other, so the second sees 500 and is refused.</li>
 *   <li>Redis, after the commit. The player is already on record, so if Redis fails the client gets 503,
 *       retries, is found in step 1 as already joined, and this step runs again.</li>
 * </ol>
 * The transaction is written out with TransactionOperations rather than @Transactional, because step 2 must
 * run after the commit, outside it.
 */
@Service
public class JoinSession {

    private final SessionRepository sessions;
    private final PlayerRepository players;
    private final LiveStateRepository liveState;
    private final TransactionOperations transaction;
    private final Clock clock;

    public JoinSession(SessionRepository sessions, PlayerRepository players, LiveStateRepository liveState,
            TransactionOperations transaction, Clock clock) {
        this.sessions = sessions;
        this.players = players;
        this.liveState = liveState;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * @param roomCodeInput what the player typed; case and surrounding spaces don't matter
     * @throws SessionNotFoundException     no session has this room code
     * @throws SessionNotJoinableException  a new player, but the session has started or ended
     * @throws SessionFullException         a new player, but the session is full
     * @throws LiveStateUnavailableException joined in Postgres, but Redis failed: retry
     */
    public Joined join(String roomCodeInput, UUID userId, String displayName) {
        Joined joined = Objects.requireNonNull(
                transaction.execute(status -> joinOnRecord(roomCodeInput, userId, displayName)));
        liveState.addPlayer(joined.player().sessionId(), RosterEntry.of(joined.player()));
        return joined;
    }

    private Joined joinOnRecord(String roomCodeInput, UUID userId, String displayName) {
        Session session = RoomCode.parse(roomCodeInput)
                .flatMap(sessions::findByRoomCodeForUpdate)
                .orElseThrow(SessionNotFoundException::new);

        Optional<Player> alreadyIn = players.find(session.id(), userId);
        if (alreadyIn.isPresent()) {
            return new Joined(alreadyIn.get(), false);
        }
        session.checkJoinable(players.count(session.id()));
        Player player = Player.join(session.id(), userId, displayName, clock.instant());
        players.add(player);
        return new Joined(player, true);
    }

    /** newPlayer is false when the caller was already in the session (a retry or a second tab). */
    public record Joined(Player player, boolean newPlayer) {
    }
}
