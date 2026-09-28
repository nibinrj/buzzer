package dev.nibin.buzzer.session.application;

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
 * Use case: the host runs the game (start, next question, end). Every command has the same shape:
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

    public RunSession(SessionRepository sessions, LiveStateRepository liveState, SessionBroadcaster broadcaster,
            TransactionOperations transaction) {
        this.sessions = sessions;
        this.liveState = liveState;
        this.broadcaster = broadcaster;
        this.transaction = transaction;
    }

    /** LOBBY → IN_PROGRESS, and question 0 starts. */
    public void start(UUID sessionId, UUID hostId) {
        QuestionShown shown = inTransaction(() -> {
            Session started = lockedSessionHostedBy(sessionId, hostId).start();
            sessions.updateStatus(started);
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

    /** LOBBY or IN_PROGRESS → ENDED. */
    public void end(UUID sessionId, UUID hostId) {
        inTransaction(() -> {
            Session ended = lockedSessionHostedBy(sessionId, hostId).end();
            sessions.updateStatus(ended);
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
