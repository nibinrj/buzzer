package dev.nibin.buzzer.session.application;

import com.github.benmanes.caffeine.cache.Caffeine;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What is read from the repositories once, and what is asked again every time. */
class SessionFactsTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final UUID HOST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final SessionQuestion Q1 =
            new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0);
    private static final Session SESSION = new Session(UUID.randomUUID(), new RoomCode("ABC234"), UUID.randomUUID(),
            HOST, "Capitals", Session.Status.LOBBY, NOW, List.of(Q1));
    private static final Player ADA = new Player(UUID.randomUUID(), SESSION.id(), USER, "Ada", NOW);

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final SessionFacts facts =
            new SessionFacts(sessions, players, Caffeine.newBuilder().build(), Caffeine.newBuilder().build());

    @Test
    void aSessionsHostAndQuestionsAreReadOnce() {
        when(sessions.findById(SESSION.id())).thenReturn(Optional.of(SESSION));

        assertThat(facts.hostOf(SESSION.id())).contains(HOST);
        assertThat(facts.questionsOf(SESSION.id())).contains(List.of(Q1));
        assertThat(facts.hostOf(SESSION.id())).contains(HOST);

        verify(sessions, times(1)).findById(SESSION.id());
    }

    @Test
    void aMembershipIsReadOnce() {
        when(players.find(SESSION.id(), USER)).thenReturn(Optional.of(ADA));

        assertThat(facts.player(SESSION.id(), USER)).contains(ADA);
        assertThat(facts.player(SESSION.id(), USER)).contains(ADA);

        verify(players, times(1)).find(SESSION.id(), USER);
    }

    @Test
    void someoneWhoWasNotAPlayerYetIsFoundOnceTheyJoin() {
        when(players.find(SESSION.id(), USER)).thenReturn(Optional.empty(), Optional.of(ADA));

        assertThat(facts.player(SESSION.id(), USER)).isEmpty();
        assertThat(facts.player(SESSION.id(), USER)).contains(ADA); // the miss wasn't cached

        verify(players, times(2)).find(SESSION.id(), USER);
    }

    @Test
    void anUnknownSessionIsAskedAgainNextTime() {
        when(sessions.findById(SESSION.id())).thenReturn(Optional.empty(), Optional.of(SESSION));

        assertThat(facts.hostOf(SESSION.id())).isEmpty();
        assertThat(facts.hostOf(SESSION.id())).contains(HOST);

        verify(sessions, times(2)).findById(SESSION.id());
    }

    @Test
    void membershipsAreKeptPerSessionAndUser() {
        UUID otherSession = UUID.randomUUID();
        when(players.find(SESSION.id(), USER)).thenReturn(Optional.of(ADA));
        when(players.find(otherSession, USER)).thenReturn(Optional.empty());

        assertThat(facts.player(SESSION.id(), USER)).contains(ADA);
        assertThat(facts.player(otherSession, USER)).isEmpty();
    }
}
