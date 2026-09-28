package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.session.application.SessionBroadcaster.AnswerRevealed;
import dev.nibin.buzzer.session.application.SessionBroadcaster.QuestionShown;
import dev.nibin.buzzer.session.application.SessionBroadcaster.StatusChanged;
import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import dev.nibin.buzzer.session.domain.SessionStateException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Use case: the host runs the game (start, next question, reveal, end). Every command has the same shape:
 * <ol>
 *   <li>Transaction: lock the session row (the lock joins take too, so a join can't slip past a start), check
 *       the rule, write Postgres, then write Redis. Redis is written INSIDE the transaction on purpose: if Redis
 *       fails, Postgres rolls back and the host can simply retry.</li>
 *   <li>After the commit: broadcast. Nobody is shown a question whose transaction was rolled back.</li>
 * </ol>
 * The host check here duplicates the WebSocket destination check on purpose: a use case must not rely on its
 * caller having checked.
 */
@Service
public class RunSession {

    private final SessionRepository sessions;
    private final LiveStateRepository liveState;
    private final SessionBroadcaster broadcaster;
    private final TransactionOperations transaction;
    private final EventOutbox outbox;

    public RunSession(SessionRepository sessions, LiveStateRepository liveState, SessionBroadcaster broadcaster,
            TransactionOperations transaction, EventOutbox outbox) {
        this.sessions = sessions;
        this.liveState = liveState;
        this.broadcaster = broadcaster;
        this.transaction = transaction;
        this.outbox = outbox;
    }

    /** LOBBY → IN_PROGRESS, and question 0 starts. SessionStarted goes to the outbox with the status change. */
    public void start(UUID sessionId, UUID hostId) {
        QuestionShown shown = inTransaction(() -> {
            Session started = lockedSessionHostedBy(sessionId, hostId).start();
            sessions.updateStatus(started);
            outbox.sessionStarted(new SessionStarted(UUID.randomUUID(), sessionId, started.quizId(), hostId,
                    started.questions().size(), liveState.serverTime().toEpochMilli(), SessionStarted.SCHEMA_VERSION));
            return showQuestion(started, 0);
        });
        broadcaster.statusChanged(new StatusChanged(sessionId, Session.Status.IN_PROGRESS));
        broadcaster.questionShown(shown);
    }

    /**
     * The next question starts, whether or not the current one's deadline has passed: the host sets the pace.
     *
     * @throws SessionStateException also when Redis no longer knows the current question (live state lost)
     */
    public void next(UUID sessionId, UUID hostId) {
        QuestionShown shown = inTransaction(() -> {
            Session session = lockedSessionHostedBy(sessionId, hostId);
            int current = liveState.find(sessionId)
                    .flatMap(LiveState::currentQuestionIndex)
                    .orElseThrow(() -> new SessionStateException(
                            "The current question is unknown (the live state was lost). End the session."));
            return showQuestion(session, session.nextQuestionIndex(current));
        });
        broadcaster.questionShown(shown);
    }

    /**
     * The current question takes no more answers, and everyone is told its correct option. Nothing changes in
     * Postgres; the transaction is still taken for the session row lock, so a reveal can't interleave with a next
     * or an end (and close a question other than the one it read).
     * <p>
     * Revealing a question that is already closed just broadcasts again: harmless, and it helps a host whose first
     * reveal's broadcast got lost.
     *
     * @throws SessionStateException no question is running (lobby, ended, or the live state was lost)
     */
    public void reveal(UUID sessionId, UUID hostId) {
        AnswerRevealed revealed = inTransaction(() -> {
            Session session = lockedSessionHostedBy(sessionId, hostId);
            LiveState live = liveState.find(sessionId)
                    .filter(state -> state.currentQuestionIndex().isPresent())
                    .orElseThrow(() -> new SessionStateException("No question is running."));
            int index = live.currentQuestionIndex().orElseThrow();
            if (live.questionOpen()) {
                liveState.closeQuestion(sessionId);
            }
            return AnswerRevealed.of(sessionId, index, session.questions().get(index));
        });
        broadcaster.answerRevealed(revealed);
    }

    /** LOBBY or IN_PROGRESS → ENDED. SessionEnded goes to the outbox with the status change. */
    public void end(UUID sessionId, UUID hostId) {
        inTransaction(() -> {
            Session ended = lockedSessionHostedBy(sessionId, hostId).end();
            sessions.updateStatus(ended);
            outbox.sessionEnded(new SessionEnded(UUID.randomUUID(), sessionId, liveState.serverTime().toEpochMilli(),
                    SessionEnded.SCHEMA_VERSION));
            liveState.end(sessionId);
            return ended;
        });
        broadcaster.statusChanged(new StatusChanged(sessionId, Session.Status.ENDED));
    }

    /** Someone else's session is "not found", as everywhere in this service. */
    private Session lockedSessionHostedBy(UUID sessionId, UUID hostId) {
        return sessions.findByIdForUpdate(sessionId)
                .filter(session -> session.hostId().equals(hostId))
                .orElseThrow(SessionNotFoundException::new);
    }

    /** Deadline by Redis's clock (see LiveStateRepository.serverTime), then written to Redis. */
    private QuestionShown showQuestion(Session session, int index) {
        SessionQuestion question = session.questions().get(index);
        Instant deadline = liveState.serverTime().plusSeconds(question.timeLimitSeconds());
        liveState.showQuestion(session.id(), index, deadline);
        return QuestionShown.of(session.id(), index, question, deadline);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transaction.execute(status -> work.get()));
    }
}
