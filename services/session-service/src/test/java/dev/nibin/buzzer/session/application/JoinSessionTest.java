package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionFullException;
import dev.nibin.buzzer.session.domain.SessionNotJoinableException;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Join rules and ordering, with the repositories mocked. TransactionOperations.withoutTransaction() runs the
 * callback directly; the real lock is proven in SessionJoinApiTest.
 */
class JoinSessionTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final RoomCode CODE = new RoomCode("ABC234");
    private static final UUID USER = UUID.randomUUID();

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final LiveStateRepository liveState = mock(LiveStateRepository.class);
    private final JoinSession joinSession = new JoinSession(sessions, players, liveState,
            TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aNewPlayerIsStoredThenAddedToTheLiveRoster() {
        Session session = session(Session.Status.LOBBY);
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.of(session));
        when(players.count(session.id())).thenReturn(3);

        JoinSession.Joined joined = joinSession.join(" abc234 ", USER, "Ada");

        assertThat(joined.newPlayer()).isTrue();
        assertThat(joined.player().sessionId()).isEqualTo(session.id());
        assertThat(joined.player().userId()).isEqualTo(USER);
        assertThat(joined.player().displayName()).isEqualTo("Ada");
        assertThat(joined.player().joinedAt()).isEqualTo(NOW);
        InOrder order = inOrder(players, liveState);
        order.verify(players).add(joined.player());
        order.verify(liveState).addPlayer(session.id(), RosterEntry.of(joined.player()));
    }

    @Test
    void joiningAgainReturnsTheSamePlayerAndRewritesTheRosterEntry() {
        Session session = session(Session.Status.LOBBY);
        Player existing = Player.join(session.id(), USER, "Ada", NOW.minusSeconds(60));
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.of(session));
        when(players.find(session.id(), USER)).thenReturn(Optional.of(existing));

        JoinSession.Joined joined = joinSession.join("ABC234", USER, "Ada");

        assertThat(joined.newPlayer()).isFalse();
        assertThat(joined.player()).isEqualTo(existing);
        verify(players, never()).add(any());
        verify(liveState).addPlayer(session.id(), RosterEntry.of(existing));
    }

    @Test
    void aPlayerAlreadyInMayComeBackAfterTheStart() {
        Session session = session(Session.Status.IN_PROGRESS);
        Player existing = Player.join(session.id(), USER, "Ada", NOW.minusSeconds(60));
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.of(session));
        when(players.find(session.id(), USER)).thenReturn(Optional.of(existing));

        assertThat(joinSession.join("ABC234", USER, "Ada").player()).isEqualTo(existing);
    }

    @Test
    void aNewPlayerCannotJoinAStartedSession() {
        Session session = session(Session.Status.IN_PROGRESS);
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> joinSession.join("ABC234", USER, "Ada"))
                .isInstanceOf(SessionNotJoinableException.class);
        verify(players, never()).add(any());
        verifyNoInteractions(liveState);
    }

    @Test
    void aNewPlayerCannotJoinAFullSession() {
        Session session = session(Session.Status.LOBBY);
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.of(session));
        when(players.count(session.id())).thenReturn(Session.MAX_PLAYERS);

        assertThatThrownBy(() -> joinSession.join("ABC234", USER, "Ada")).isInstanceOf(SessionFullException.class);
        verify(players, never()).add(any());
    }

    @Test
    void anUnknownRoomCodeIsNotFound() {
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> joinSession.join("ABC234", USER, "Ada"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void somethingThatCantBeARoomCodeIsNotFoundWithoutAQuery() {
        assertThatThrownBy(() -> joinSession.join("nope!", USER, "Ada"))
                .isInstanceOf(SessionNotFoundException.class);
        verifyNoInteractions(sessions);
    }

    @Test
    void redisFailingAfterTheInsertIsReportedSoTheClientRetries() {
        Session session = session(Session.Status.LOBBY);
        when(sessions.findByRoomCodeForUpdate(CODE)).thenReturn(Optional.of(session));
        doThrow(new LiveStateUnavailableException(new RuntimeException("down")))
                .when(liveState).addPlayer(any(), any());

        assertThatThrownBy(() -> joinSession.join("ABC234", USER, "Ada"))
                .isInstanceOf(LiveStateUnavailableException.class);
        verify(players).add(any());
    }

    private static Session session(Session.Status status) {
        return new Session(UUID.randomUUID(), CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals", status, NOW,
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0)));
    }
}
