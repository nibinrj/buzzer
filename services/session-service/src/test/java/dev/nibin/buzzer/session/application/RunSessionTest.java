package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.session.application.SessionBroadcaster.AnswerRevealed;
import dev.nibin.buzzer.session.application.SessionBroadcaster.QuestionShown;
import dev.nibin.buzzer.session.application.SessionBroadcaster.StatusChanged;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import dev.nibin.buzzer.session.domain.SessionStateException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Start/next/reveal/end rules and ordering, with repositories and broadcaster mocked. */
class RunSessionTest {

    private static final Instant REDIS_NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final UUID HOST = UUID.randomUUID();
    private static final SessionQuestion Q1 =
            new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0);
    private static final SessionQuestion Q2 =
            new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome"), 1);

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final LiveStateRepository liveState = mock(LiveStateRepository.class);
    private final SessionBroadcaster broadcaster = mock(SessionBroadcaster.class);
    private final EventOutbox outbox = mock(EventOutbox.class);
    private final RunSession runSession =
            new RunSession(sessions, liveState, broadcaster, TransactionOperations.withoutTransaction(), outbox);

    RunSessionTest() {
        when(liveState.serverTime()).thenReturn(REDIS_NOW);
    }

    @Test
    void startWritesPostgresThenRedisThenBroadcastsTheFirstQuestion() {
        Session lobby = session(Session.Status.LOBBY);

        runSession.start(lobby.id(), HOST);

        Instant deadline = REDIS_NOW.plusSeconds(20);
        InOrder order = inOrder(sessions, liveState, broadcaster);
        ArgumentCaptor<Session> updated = ArgumentCaptor.forClass(Session.class);
        order.verify(sessions).updateStatus(updated.capture());
        order.verify(liveState).showQuestion(lobby.id(), 0, deadline);
        order.verify(broadcaster).statusChanged(new StatusChanged(lobby.id(), Session.Status.IN_PROGRESS));
        order.verify(broadcaster).questionShown(QuestionShown.of(lobby.id(), 0, Q1, deadline));
        assertThat(updated.getValue().status()).isEqualTo(Session.Status.IN_PROGRESS);
    }

    @Test
    void startRecordsSessionStartedInTheOutboxBeforeAnythingIsBroadcast() {
        Session lobby = session(Session.Status.LOBBY);

        runSession.start(lobby.id(), HOST);

        ArgumentCaptor<SessionStarted> event = ArgumentCaptor.forClass(SessionStarted.class);
        InOrder order = inOrder(sessions, outbox, broadcaster);
        order.verify(sessions).updateStatus(any());
        order.verify(outbox).sessionStarted(event.capture());
        order.verify(broadcaster).statusChanged(any());
        assertThat(event.getValue()).usingRecursiveComparison().ignoringFields("eventId").isEqualTo(
                new SessionStarted(null, lobby.id(), lobby.quizId(), HOST, 2, REDIS_NOW.toEpochMilli(), 1));
    }

    @Test
    void endRecordsSessionEndedInTheOutbox() {
        Session running = session(Session.Status.IN_PROGRESS);

        runSession.end(running.id(), HOST);

        ArgumentCaptor<SessionEnded> event = ArgumentCaptor.forClass(SessionEnded.class);
        verify(outbox).sessionEnded(event.capture());
        assertThat(event.getValue().sessionId()).isEqualTo(running.id());
        assertThat(event.getValue().endedAtMs()).isEqualTo(REDIS_NOW.toEpochMilli());
        assertThat(event.getValue().eventId()).isNotNull();
    }

    @Test
    void theDeadlineComesFromRedisTimeNotThisServersClock() {
        Session lobby = session(Session.Status.LOBBY);

        runSession.start(lobby.id(), HOST);

        verify(liveState).showQuestion(lobby.id(), 0, REDIS_NOW.plusSeconds(Q1.timeLimitSeconds()));
    }

    @Test
    void nextShowsTheQuestionAfterTheCurrentOne() {
        Session running = session(Session.Status.IN_PROGRESS);
        runningQuestion(running, 0, true);

        runSession.next(running.id(), HOST);

        verify(liveState).showQuestion(running.id(), 1, REDIS_NOW.plusSeconds(15));
        verify(broadcaster).questionShown(QuestionShown.of(running.id(), 1, Q2, REDIS_NOW.plusSeconds(15)));
        verify(sessions, never()).updateStatus(any());
    }

    @Test
    void nextWithoutAKnownCurrentQuestionIsRefused() {
        Session running = session(Session.Status.IN_PROGRESS);
        when(liveState.find(running.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runSession.next(running.id(), HOST)).isInstanceOf(SessionStateException.class);
        verifyNoInteractions(broadcaster);
    }

    @Test
    void endWritesBothStoresThenBroadcasts() {
        Session running = session(Session.Status.IN_PROGRESS);

        runSession.end(running.id(), HOST);

        InOrder order = inOrder(sessions, liveState, broadcaster);
        order.verify(sessions).updateStatus(any());
        order.verify(liveState).end(running.id());
        order.verify(broadcaster).statusChanged(new StatusChanged(running.id(), Session.Status.ENDED));
    }

    @Test
    void anotherHostsSessionIsNotFound() {
        Session lobby = session(Session.Status.LOBBY);

        assertThatThrownBy(() -> runSession.start(lobby.id(), UUID.randomUUID()))
                .isInstanceOf(SessionNotFoundException.class);
        verify(sessions, never()).updateStatus(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void aRuleViolationChangesNothingAndBroadcastsNothing() {
        Session running = session(Session.Status.IN_PROGRESS);

        assertThatThrownBy(() -> runSession.start(running.id(), HOST)).isInstanceOf(SessionStateException.class);
        verify(sessions, never()).updateStatus(any());
        verifyNoInteractions(outbox);
        verify(liveState, never()).showQuestion(any(), anyInt(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void whenRedisFailsNothingIsBroadcast() {
        Session lobby = session(Session.Status.LOBBY);
        doThrow(new LiveStateUnavailableException(new RuntimeException("down")))
                .when(liveState).showQuestion(any(), anyInt(), any());

        assertThatThrownBy(() -> runSession.start(lobby.id(), HOST))
                .isInstanceOf(LiveStateUnavailableException.class);
        verifyNoInteractions(broadcaster);
    }

    // --- reveal ---

    @Test
    void revealClosesTheQuestionThenBroadcastsItsCorrectOption() {
        Session running = session(Session.Status.IN_PROGRESS);
        runningQuestion(running, 1, true);

        runSession.reveal(running.id(), HOST);

        InOrder order = inOrder(liveState, broadcaster);
        order.verify(liveState).closeQuestion(running.id());
        order.verify(broadcaster).answerRevealed(new AnswerRevealed(running.id(), 1, Q2.questionId(), 1));
        verify(sessions, never()).updateStatus(any());
    }

    @Test
    void revealingAClosedQuestionAgainOnlyBroadcastsAgain() {
        Session running = session(Session.Status.IN_PROGRESS);
        runningQuestion(running, 0, false);

        runSession.reveal(running.id(), HOST);

        verify(liveState, never()).closeQuestion(any());
        verify(broadcaster).answerRevealed(new AnswerRevealed(running.id(), 0, Q1.questionId(), 0));
    }

    @Test
    void revealWithoutARunningQuestionIsRefused() {
        Session lobby = session(Session.Status.LOBBY);
        when(liveState.find(lobby.id())).thenReturn(Optional.of(LiveState.of(lobby.id(), Session.Status.LOBBY,
                List.of())));

        assertThatThrownBy(() -> runSession.reveal(lobby.id(), HOST)).isInstanceOf(SessionStateException.class);
        verify(liveState, never()).closeQuestion(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void anotherHostCannotReveal() {
        Session running = session(Session.Status.IN_PROGRESS);
        runningQuestion(running, 0, true);

        assertThatThrownBy(() -> runSession.reveal(running.id(), UUID.randomUUID()))
                .isInstanceOf(SessionNotFoundException.class);
        verify(liveState, never()).closeQuestion(any());
        verifyNoInteractions(broadcaster);
    }

    private void runningQuestion(Session session, int index, boolean open) {
        when(liveState.find(session.id())).thenReturn(Optional.of(new LiveState(session.id(),
                Session.Status.IN_PROGRESS, Optional.of(index), Optional.of(REDIS_NOW), open, List.of())));
    }

    private Session session(Session.Status status) {
        Session session = new Session(UUID.randomUUID(), new RoomCode("ABC234"), UUID.randomUUID(), HOST, "Capitals",
                status, REDIS_NOW, List.of(Q1, Q2));
        when(sessions.findByIdForUpdate(session.id())).thenReturn(Optional.of(session));
        return session;
    }
}
