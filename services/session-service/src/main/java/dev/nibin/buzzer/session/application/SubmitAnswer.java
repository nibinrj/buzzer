package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRegistration;
import dev.nibin.buzzer.session.domain.AnswerRegistry;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.UUID;

/**
 * Use case: a player answers the running question. In this order:
 * <ol>
 *   <li>Postgres reads: the caller must be a player of the session; the question must be one of its questions and
 *       the option one of the question's. Correctness is looked up here, from the stored questions. The client
 *       only says which option it picked.</li>
 *   <li>Redis decides (AnswerRegistry, one atomic script): accepted with a seq, or why not.</li>
 *   <li>Postgres records an accepted answer AND its AnswerSubmitted event (the outbox), in one transaction. The
 *       event reaches Kafka later, from OutboxPublisher: scoring never slows down the answer's ack.</li>
 * </ol>
 * Redis goes first because it is the one place every instance's answers pass in single file: it must decide before
 * anything is written. The price is a gap if step 3 fails after step 2 accepted: Redis counts the answer, Postgres
 * has no row, and a retry is a DUPLICATE. That case is logged and acked as NOT_RECORDED; the player loses this
 * question. A known limitation (batch 4.3 decision D2a), to be written down in ADR-004.
 */
@Service
public class SubmitAnswer {

    private static final Logger log = LoggerFactory.getLogger(SubmitAnswer.class);

    private final SessionRepository sessions;
    private final PlayerRepository players;
    private final AnswerRegistry registry;
    private final AnswerRepository answers;
    private final EventOutbox outbox;
    private final TransactionOperations transaction;

    public SubmitAnswer(SessionRepository sessions, PlayerRepository players, AnswerRegistry registry,
            AnswerRepository answers, EventOutbox outbox, TransactionOperations transaction) {
        this.sessions = sessions;
        this.players = players;
        this.registry = registry;
        this.answers = answers;
        this.outbox = outbox;
        this.transaction = transaction;
    }

    /**
     * @param questionId  may be null (a malformed request): answered INVALID
     * @param optionIndex may be null (a malformed request): answered INVALID
     * @throws SessionNotFoundException      no such session, or the caller is not one of its players
     * @throws LiveStateUnavailableException Redis can't be reached; nothing was decided or recorded, retry
     */
    public Result submit(UUID sessionId, UUID userId, UUID questionId, Integer optionIndex) {
        Player player = players.find(sessionId, userId).orElseThrow(SessionNotFoundException::new);
        Session session = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);

        int questionIndex = indexOf(session.questions(), questionId);
        if (questionIndex < 0 || optionIndex == null) {
            return Result.rejected(questionId, Reason.INVALID);
        }
        SessionQuestion question = session.questions().get(questionIndex);
        if (optionIndex < 0 || optionIndex >= question.options().size()) {
            return Result.rejected(questionId, Reason.INVALID);
        }
        boolean correct = optionIndex == question.correctOption();

        AnswerRegistration verdict =
                registry.register(sessionId, questionId, questionIndex, player.playerId(), correct);
        if (!verdict.accepted()) {
            return new Result(questionId, Reason.valueOf(verdict.outcome().name()), verdict.seq());
        }

        Answer answer = Answer.accepted(sessionId, questionId, player.playerId(), optionIndex, correct, verdict);
        try {
            // One transaction: the answer row and its outbox event commit together or not at all.
            transaction.executeWithoutResult(status -> {
                answers.add(answer);
                outbox.answerSubmitted(toEvent(answer));
            });
        } catch (RuntimeException e) {
            // Anything: a database error, a transaction that can't start, an event that can't be written as JSON.
            // The transaction rolled back either way, and Redis has already counted the answer: the player must still
            // hear something, so no exception may escape past this point.
            log.error("Answer accepted by Redis but not recorded in Postgres: session={} question={} player={} "
                    + "seq={} ({})", sessionId, questionId, player.playerId(), verdict.seq(), e.getClass().getName());
            return Result.rejected(questionId, Reason.NOT_RECORDED);
        }
        return new Result(questionId, Reason.ACCEPTED, verdict.seq());
    }

    /**
     * The event for scoring. Its eventId is the answer's own id: one answer, one event, and a consumer's
     * idempotency key that can be traced straight back to the answers table.
     */
    private static AnswerSubmitted toEvent(Answer answer) {
        return new AnswerSubmitted(answer.answerId(), answer.sessionId(), answer.questionId(), answer.playerId(),
                answer.optionIndex(), answer.correct(), answer.correctRank(), answer.seq(),
                answer.answeredAt().toEpochMilli(), AnswerSubmitted.SCHEMA_VERSION);
    }

    /** The question's index in the session, or -1 if it isn't one of the session's questions. */
    private static int indexOf(List<SessionQuestion> questions, UUID questionId) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).questionId().equals(questionId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ACCEPTED, or why not. The middle six are the registry's outcomes, under the same names.
     */
    public enum Reason {
        ACCEPTED,
        DUPLICATE,
        LATE,
        CLOSED,
        WRONG_QUESTION,
        NOT_RUNNING,
        /** Not a question of this session, or not an option of the question, or a field missing. */
        INVALID,
        /** Accepted by Redis, but Postgres failed to record it. */
        NOT_RECORDED
    }

    /**
     * What the player is told. Deliberately nothing about correctness or correctRank: that would give the answer
     * away before the reveal.
     *
     * @param seq the answer's place in line when ACCEPTED, the first answer's place when DUPLICATE, else 0
     */
    public record Result(UUID questionId, Reason reason, long seq) {

        static Result rejected(UUID questionId, Reason reason) {
            return new Result(questionId, reason, 0);
        }

        public boolean accepted() {
            return reason == Reason.ACCEPTED;
        }
    }
}
