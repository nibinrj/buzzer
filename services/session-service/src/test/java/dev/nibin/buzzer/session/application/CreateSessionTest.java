package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.application.QuizCatalog.PublishedQuiz;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCodeTakenException;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ownership check, snapshot copy and room-code retries, with the catalog and repository mocked. */
class CreateSessionTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final UUID HOST = UUID.randomUUID();
    private static final UUID QUIZ_ID = UUID.randomUUID();
    private static final List<SessionQuestion> QUESTIONS = List.of(
            new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0),
            new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome", "Turin"), 1));

    private final QuizCatalog quizzes = mock(QuizCatalog.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final LiveStateRepository liveState = mock(LiveStateRepository.class);
    private final CreateSession createSession = new CreateSession(quizzes, sessions, liveState, new Random(42),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsALobbySessionWithTheQuizCopiedIn() {
        when(quizzes.publishedQuiz(QUIZ_ID)).thenReturn(Optional.of(quizOwnedBy(HOST)));

        Session session = createSession.create(HOST, QUIZ_ID);

        assertThat(session.hostId()).isEqualTo(HOST);
        assertThat(session.quizId()).isEqualTo(QUIZ_ID);
        assertThat(session.quizTitle()).isEqualTo("Capitals");
        assertThat(session.questions()).isEqualTo(QUESTIONS);
        assertThat(session.status()).isEqualTo(Session.Status.LOBBY);
        assertThat(session.createdAt()).isEqualTo(NOW);
        verify(sessions).add(session);
        verify(liveState).initialize(session.id(), Session.Status.LOBBY);
    }

    @Test
    void redisBeingDownDoesNotFailTheCreate() {
        when(quizzes.publishedQuiz(QUIZ_ID)).thenReturn(Optional.of(quizOwnedBy(HOST)));
        doThrow(new LiveStateUnavailableException(new RuntimeException("down")))
                .when(liveState).initialize(any(), any());

        Session session = createSession.create(HOST, QUIZ_ID);

        verify(sessions).add(session);
    }

    @Test
    void anUnknownOrDraftQuizIsNotFound() {
        when(quizzes.publishedQuiz(QUIZ_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createSession.create(HOST, QUIZ_ID)).isInstanceOf(QuizNotFoundException.class);
        verify(sessions, never()).add(any());
    }

    @Test
    void someoneElsesQuizIsNotFoundEither() {
        when(quizzes.publishedQuiz(QUIZ_ID)).thenReturn(Optional.of(quizOwnedBy(UUID.randomUUID())));

        assertThatThrownBy(() -> createSession.create(HOST, QUIZ_ID)).isInstanceOf(QuizNotFoundException.class);
        verify(sessions, never()).add(any());
    }

    @Test
    void quizServiceBeingDownIsPassedOn() {
        when(quizzes.publishedQuiz(QUIZ_ID))
                .thenThrow(new QuizServiceUnavailableException(QUIZ_ID, new RuntimeException("down")));

        assertThatThrownBy(() -> createSession.create(HOST, QUIZ_ID))
                .isInstanceOf(QuizServiceUnavailableException.class);
    }

    @Test
    void aTakenRoomCodeIsRetriedWithANewCodeForTheSameSession() {
        when(quizzes.publishedQuiz(QUIZ_ID)).thenReturn(Optional.of(quizOwnedBy(HOST)));
        doThrow(RoomCodeTakenException.class).doThrow(RoomCodeTakenException.class).doNothing()
                .when(sessions).add(any());

        Session session = createSession.create(HOST, QUIZ_ID);

        ArgumentCaptor<Session> attempts = ArgumentCaptor.forClass(Session.class);
        verify(sessions, times(3)).add(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(Session::roomCode).doesNotHaveDuplicates();
        assertThat(attempts.getAllValues()).extracting(Session::id).containsOnly(session.id());
        assertThat(session.roomCode()).isEqualTo(attempts.getAllValues().getLast().roomCode());
    }

    @Test
    void givesUpAfterFiveTakenCodes() {
        when(quizzes.publishedQuiz(QUIZ_ID)).thenReturn(Optional.of(quizOwnedBy(HOST)));
        doThrow(RoomCodeTakenException.class).when(sessions).add(any());

        assertThatThrownBy(() -> createSession.create(HOST, QUIZ_ID)).isInstanceOf(IllegalStateException.class);
        verify(sessions, times(CreateSession.MAX_ROOM_CODE_ATTEMPTS)).add(any());
    }

    private static PublishedQuiz quizOwnedBy(UUID owner) {
        return new PublishedQuiz(QUIZ_ID, owner, "Capitals", QUESTIONS);
    }
}
