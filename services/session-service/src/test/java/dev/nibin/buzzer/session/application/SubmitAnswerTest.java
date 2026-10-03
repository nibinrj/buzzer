package dev.nibin.buzzer.session.application;

import com.github.benmanes.caffeine.cache.Caffeine;
import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.session.application.SubmitAnswer.Reason;
import dev.nibin.buzzer.session.application.SubmitAnswer.Result;
import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRegistration;
import dev.nibin.buzzer.session.domain.AnswerRegistration.Outcome;
import dev.nibin.buzzer.session.domain.AnswerRegistry;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.exc.StreamWriteException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The answer use case with its four ports mocked: what is checked before Redis, what Redis is told, what is saved. */
class SubmitAnswerTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant REDIS_TIME = NOW.plusSeconds(3);
    private static final UUID USER = UUID.randomUUID();
    private static final SessionQuestion Q1 =
            new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0);
    private static final SessionQuestion Q2 =
            new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome", "Turin"), 1);
    private static final Session SESSION = new Session(UUID.randomUUID(), new RoomCode("ABC234"), UUID.randomUUID(),
            UUID.randomUUID(), "Capitals", Session.Status.IN_PROGRESS, NOW, List.of(Q1, Q2));
    private static final Player ADA = new Player(UUID.randomUUID(), SESSION.id(), USER, "Ada", NOW);

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final AnswerRegistry registry = mock(AnswerRegistry.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final EventOutbox outbox = mock(EventOutbox.class);
    // Real caches over the mocked repositories: the same lookups the service makes.
    private final SubmitAnswer submitAnswer = new SubmitAnswer(
            new SessionFacts(sessions, players, Caffeine.newBuilder().build(), Caffeine.newBuilder().build()),
            registry, answers, outbox, TransactionOperations.withoutTransaction());

    SubmitAnswerTest() {
        when(sessions.findById(SESSION.id())).thenReturn(Optional.of(SESSION));
        when(players.find(SESSION.id(), USER)).thenReturn(Optional.of(ADA));
    }

    @Test
    void aCorrectAnswerIsDecidedByRedisThenRecorded() {
        when(registry.register(SESSION.id(), Q2.questionId(), 1, ADA.playerId(), true))
                .thenReturn(new AnswerRegistration(Outcome.ACCEPTED, 3, 1, REDIS_TIME));

        Result result = submitAnswer.submit(SESSION.id(), USER, Q2.questionId(), 1); // Rome

        assertThat(result).isEqualTo(new Result(Q2.questionId(), Reason.ACCEPTED, 3));
        InOrder order = inOrder(registry, answers);
        order.verify(registry).register(SESSION.id(), Q2.questionId(), 1, ADA.playerId(), true);
        ArgumentCaptor<Answer> saved = ArgumentCaptor.forClass(Answer.class);
        order.verify(answers).add(saved.capture());
        assertThat(saved.getValue()).usingRecursiveComparison().ignoringFields("answerId").isEqualTo(new Answer(
                UUID.randomUUID(), SESSION.id(), Q2.questionId(), ADA.playerId(), 1, true, 3, 1, REDIS_TIME));
    }

    @Test
    void theAnswerAndItsEventAreWrittenTogetherAndTheEventIdIsTheAnswerId() {
        acceptEverything();

        submitAnswer.submit(SESSION.id(), USER, Q2.questionId(), 1);

        ArgumentCaptor<Answer> saved = ArgumentCaptor.forClass(Answer.class);
        ArgumentCaptor<AnswerSubmitted> event = ArgumentCaptor.forClass(AnswerSubmitted.class);
        InOrder order = inOrder(answers, outbox);
        order.verify(answers).add(saved.capture());
        order.verify(outbox).answerSubmitted(event.capture());
        Answer answer = saved.getValue();
        assertThat(event.getValue()).isEqualTo(new AnswerSubmitted(answer.answerId(), SESSION.id(), Q2.questionId(),
                ADA.playerId(), 1, true, 1, 1, REDIS_TIME.toEpochMilli(), AnswerSubmitted.SCHEMA_VERSION));
    }

    @Test
    void whenTheOutboxWriteFailsTheAnswerIsNotRecordedEither() {
        acceptEverything();
        doThrow(new DataAccessResourceFailureException("Postgres is down")).when(outbox).answerSubmitted(any());

        assertThat(submitAnswer.submit(SESSION.id(), USER, Q1.questionId(), 0).reason())
                .isEqualTo(Reason.NOT_RECORDED);
    }

    @Test
    void aWrongOptionReachesRedisAsNotCorrectAndIsRecordedWithoutARank() {
        when(registry.register(SESSION.id(), Q2.questionId(), 1, ADA.playerId(), false))
                .thenReturn(new AnswerRegistration(Outcome.ACCEPTED, 2, 0, REDIS_TIME));

        Result result = submitAnswer.submit(SESSION.id(), USER, Q2.questionId(), 2); // Turin

        assertThat(result.accepted()).isTrue();
        ArgumentCaptor<Answer> saved = ArgumentCaptor.forClass(Answer.class);
        verify(answers).add(saved.capture());
        assertThat(saved.getValue().correct()).isFalse();
        assertThat(saved.getValue().correctRank()).isZero();
        assertThat(saved.getValue().optionIndex()).isEqualTo(2);
    }

    @Test
    void someoneWhoDidNotJoinIsToldTheSessionDoesNotExist() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> submitAnswer.submit(SESSION.id(), stranger, Q1.questionId(), 0))
                .isInstanceOf(SessionNotFoundException.class);
        verifyNoInteractions(registry, answers);
    }

    @Test
    void aQuestionOfAnotherSessionIsInvalidAndNeverReachesRedis() {
        assertInvalid(UUID.randomUUID(), 0);
    }

    @Test
    void anOptionOutsideTheQuestionIsInvalid() {
        assertInvalid(Q2.questionId(), -1);
        assertInvalid(Q2.questionId(), 3); // Q2 has options 0..2
        assertInvalid(Q1.questionId(), 2); // Q1 has options 0..1
    }

    @Test
    void missingFieldsAreInvalid() {
        assertInvalid(null, 0);
        assertInvalid(Q1.questionId(), null);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, mode = EnumSource.Mode.EXCLUDE, names = "ACCEPTED")
    void everyRefusalFromRedisIsPassedOnUnderItsOwnNameAndNothingIsRecorded(Outcome outcome) {
        long seq = outcome == Outcome.DUPLICATE ? 4 : 0;
        when(registry.register(any(), any(), anyInt(), any(), anyBoolean()))
                .thenReturn(new AnswerRegistration(outcome, seq, 0, null));

        Result result = submitAnswer.submit(SESSION.id(), USER, Q1.questionId(), 0);

        assertThat(result).isEqualTo(new Result(Q1.questionId(), Reason.valueOf(outcome.name()), seq));
        verify(answers, never()).add(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void whenPostgresFailsAfterRedisAcceptedThePlayerIsToldItWasNotRecorded() {
        acceptEverything();
        doThrow(new DataAccessResourceFailureException("Postgres is down")).when(answers).add(any());

        assertThat(submitAnswer.submit(SESSION.id(), USER, Q1.questionId(), 0))
                .isEqualTo(new Result(Q1.questionId(), Reason.NOT_RECORDED, 0));
    }

    @Test
    void aTransactionThatCannotEvenStartCountsAsNotRecordedToo() {
        acceptEverything();
        doThrow(new CannotCreateTransactionException("no connection")).when(answers).add(any());

        assertThat(submitAnswer.submit(SESSION.id(), USER, Q1.questionId(), 0).reason())
                .isEqualTo(Reason.NOT_RECORDED);
    }

    @Test
    void anyOtherFailureToRecordCountsAsNotRecordedAndDoesNotEscape() {
        acceptEverything();
        // E.g. the event can't be written as JSON: not a database error, but the transaction rolls back just the same.
        doThrow(new StreamWriteException((JsonGenerator) null, "cannot write the event"))
                .when(outbox).answerSubmitted(any());

        assertThat(submitAnswer.submit(SESSION.id(), USER, Q1.questionId(), 0))
                .isEqualTo(new Result(Q1.questionId(), Reason.NOT_RECORDED, 0));
    }

    @Test
    void whenRedisIsDownNothingIsDecidedOrRecorded() {
        when(registry.register(any(), any(), anyInt(), any(), anyBoolean()))
                .thenThrow(new LiveStateUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(() -> submitAnswer.submit(SESSION.id(), USER, Q1.questionId(), 0))
                .isInstanceOf(LiveStateUnavailableException.class);
        verifyNoInteractions(answers);
    }

    private void assertInvalid(UUID questionId, Integer option) {
        assertThat(submitAnswer.submit(SESSION.id(), USER, questionId, option))
                .isEqualTo(new Result(questionId, Reason.INVALID, 0));
        verifyNoInteractions(registry, answers);
    }

    private void acceptEverything() {
        when(registry.register(eq(SESSION.id()), any(), anyInt(), any(), anyBoolean()))
                .thenReturn(new AnswerRegistration(Outcome.ACCEPTED, 1, 1, REDIS_TIME));
    }
}
