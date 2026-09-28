package dev.nibin.buzzer.session.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final RoomCode CODE = new RoomCode("ABC234");

    @Test
    void aNewSessionWaitsInTheLobby() {
        Session session = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals",
                List.of(question()), NOW);

        assertThat(session.status()).isEqualTo(Session.Status.LOBBY);
        assertThat(session.createdAt()).isEqualTo(NOW);
        assertThat(session.roomCode()).isEqualTo(CODE);
    }

    @Test
    void withRoomCodeKeepsEverythingElse() {
        Session session = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals",
                List.of(question()), NOW);

        Session renamed = session.withRoomCode(new RoomCode("XYZ789"));

        assertThat(renamed.roomCode().value()).isEqualTo("XYZ789");
        assertThat(renamed.id()).isEqualTo(session.id());
        assertThat(renamed.questions()).isEqualTo(session.questions());
    }

    @Test
    void needsAtLeastOneQuestion() {
        assertThatThrownBy(() -> Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Empty", List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void questionsAreACopy() {
        List<SessionQuestion> questions = new ArrayList<>(List.of(question()));
        Session session = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals", questions, NOW);

        questions.clear();

        assertThat(session.questions()).hasSize(1);
        assertThatThrownBy(() -> session.questions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aQuestionNeedsTwoOptionsAndAValidCorrectIndex() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new SessionQuestion(id, "Q", 20, List.of("only"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionQuestion(id, "Q", 20, List.of("A", "B"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionQuestion(id, "Q", 20, List.of("A", "B"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newPlayersMayJoinOnlyTheLobbyAndOnlyBelowTheLimit() {
        Session lobby = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals", List.of(question()), NOW);

        lobby.checkJoinable(Session.MAX_PLAYERS - 1);
        assertThatThrownBy(() -> lobby.checkJoinable(Session.MAX_PLAYERS)).isInstanceOf(SessionFullException.class);
        for (Session.Status status : List.of(Session.Status.IN_PROGRESS, Session.Status.ENDED)) {
            Session notLobby = new Session(lobby.id(), CODE, lobby.quizId(), lobby.hostId(), "Capitals", status, NOW,
                    lobby.questions());
            assertThatThrownBy(() -> notLobby.checkJoinable(0)).isInstanceOf(SessionNotJoinableException.class);
        }
    }

    @Test
    void startMovesTheLobbyToInProgressOnce() {
        Session lobby = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals", List.of(question()), NOW);

        Session started = lobby.start();

        assertThat(started.status()).isEqualTo(Session.Status.IN_PROGRESS);
        assertThat(started.id()).isEqualTo(lobby.id());
        assertThatThrownBy(started::start).isInstanceOf(SessionStateException.class);
    }

    @Test
    void nextQuestionIndexOnlyWhileRunningAndNotPastTheLast() {
        Session lobby = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals",
                List.of(question(), question()), NOW);
        Session running = lobby.start();

        assertThat(running.nextQuestionIndex(0)).isEqualTo(1);
        assertThatThrownBy(() -> running.nextQuestionIndex(1)).isInstanceOf(SessionStateException.class)
                .hasMessage("That was the last question. End the session.");
        assertThatThrownBy(() -> lobby.nextQuestionIndex(0)).isInstanceOf(SessionStateException.class);
    }

    @Test
    void endWorksFromTheLobbyOrWhileRunningButOnlyOnce() {
        Session lobby = Session.create(CODE, UUID.randomUUID(), UUID.randomUUID(), "Capitals", List.of(question()), NOW);

        assertThat(lobby.end().status()).isEqualTo(Session.Status.ENDED);
        assertThat(lobby.start().end().status()).isEqualTo(Session.Status.ENDED);
        assertThatThrownBy(() -> lobby.end().end()).isInstanceOf(SessionStateException.class);
    }

    @Test
    void aPlayersDisplayNameIsTrimmedAndBounded() {
        UUID sessionId = UUID.randomUUID();
        assertThat(Player.join(sessionId, UUID.randomUUID(), "  Ada ", NOW).displayName()).isEqualTo("Ada");
        assertThatThrownBy(() -> Player.join(sessionId, UUID.randomUUID(), "   ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Player.join(sessionId, UUID.randomUUID(), "x".repeat(31), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SessionQuestion question() {
        return new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0);
    }
}
