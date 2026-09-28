package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.application.GetSessionState.SessionState;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who may read, and the rebuild from Postgres when Redis has nothing. */
class GetSessionStateTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final UUID HOST = UUID.randomUUID();
    private static final SessionQuestion Q1 =
            new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0);
    private static final SessionQuestion Q2 =
            new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome"), 1);
    private static final Session SESSION = new Session(UUID.randomUUID(), new RoomCode("ABC234"), UUID.randomUUID(),
            HOST, "Capitals", Session.Status.LOBBY, NOW, List.of(Q1, Q2));

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final LiveStateRepository liveState = mock(LiveStateRepository.class);
    private final GetSessionState getSessionState = new GetSessionState(sessions, players, liveState);

    GetSessionStateTest() {
        when(sessions.findById(SESSION.id())).thenReturn(Optional.of(SESSION));
    }

    @Test
    void theHostReadsTheLiveState() {
        LiveState live = LiveState.of(SESSION.id(), Session.Status.LOBBY, List.of());
        when(liveState.find(SESSION.id())).thenReturn(Optional.of(live));

        SessionState state = getSessionState.get(SESSION.id(), HOST);

        assertThat(state.live()).isEqualTo(live);
        assertThat(state.currentQuestion()).isEmpty();
    }

    @Test
    void aPlayerOfTheSessionReadsItToo() {
        Player player = Player.join(SESSION.id(), UUID.randomUUID(), "Ada", NOW);
        when(players.find(SESSION.id(), player.userId())).thenReturn(Optional.of(player));
        when(liveState.find(SESSION.id())).thenReturn(Optional.of(LiveState.of(SESSION.id(), Session.Status.LOBBY,
                List.of(RosterEntry.of(player)))));

        assertThat(getSessionState.get(SESSION.id(), player.userId()).live().players())
                .containsExactly(RosterEntry.of(player));
    }

    @Test
    void anyoneElseIsToldTheSessionDoesNotExist() {
        assertThatThrownBy(() -> getSessionState.get(SESSION.id(), UUID.randomUUID()))
                .isInstanceOf(SessionNotFoundException.class);
        assertThatThrownBy(() -> getSessionState.get(UUID.randomUUID(), HOST))
                .isInstanceOf(SessionNotFoundException.class);
        verify(liveState, never()).find(any());
    }

    @Test
    void missingLiveStateIsRebuiltFromPostgresAndReadBack() {
        Player ada = Player.join(SESSION.id(), UUID.randomUUID(), "Ada", NOW);
        when(players.findBySession(SESSION.id())).thenReturn(List.of(ada));
        LiveState afterRestore = LiveState.of(SESSION.id(), Session.Status.LOBBY, List.of(RosterEntry.of(ada)));
        when(liveState.find(SESSION.id())).thenReturn(Optional.empty(), Optional.of(afterRestore));

        SessionState state = getSessionState.get(SESSION.id(), HOST);

        verify(liveState).restore(LiveState.of(SESSION.id(), Session.Status.LOBBY, List.of(RosterEntry.of(ada))));
        assertThat(state.live()).isEqualTo(afterRestore);
    }

    @Test
    void theCurrentQuestionComesFromTheIndexInTheLiveState() {
        when(liveState.find(SESSION.id())).thenReturn(Optional.of(new LiveState(SESSION.id(),
                Session.Status.IN_PROGRESS, Optional.of(1), Optional.of(NOW.plusSeconds(15)), List.of())));

        assertThat(getSessionState.get(SESSION.id(), HOST).currentQuestion()).contains(Q2);
    }
}
