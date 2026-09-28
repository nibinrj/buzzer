package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.application.QuizCatalog.PublishedQuiz;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.RoomCodeTakenException;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Use case: a host starts a live session from one of their published quizzes.
 * <p>
 * Deliberately NOT @Transactional. The quiz-service call happens before any transaction, so a slow
 * quiz-service never holds a database connection. Each save attempt is then its own transaction
 * (SessionRepository.add): a room code collision rolls back only that attempt, and the retry starts clean.
 * <p>
 * The live state in Redis is written last and best effort: the session is on record in Postgres, and
 * GetSessionState rebuilds missing live state from there. Failing a create over Redis would only make the
 * host retry and leave an orphan session behind.
 */
@Service
public class CreateSession {

    /** With ~10^9 codes a single collision is already rare; five in a row means something else is wrong. */
    static final int MAX_ROOM_CODE_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(CreateSession.class);

    private final QuizCatalog quizzes;
    private final SessionRepository sessions;
    private final LiveStateRepository liveState;
    private final RandomGenerator random;
    private final Clock clock;

    public CreateSession(QuizCatalog quizzes, SessionRepository sessions, LiveStateRepository liveState,
            RandomGenerator random, Clock clock) {
        this.quizzes = quizzes;
        this.sessions = sessions;
        this.liveState = liveState;
        this.random = random;
        this.clock = clock;
    }

    /**
     * @throws QuizNotFoundException           no published quiz with this id belongs to this host
     * @throws QuizServiceUnavailableException quiz-service can't answer right now
     */
    public Session create(UUID hostId, UUID quizId) {
        PublishedQuiz quiz = quizzes.publishedQuiz(quizId)
                .filter(published -> published.ownerId().equals(hostId))
                .orElseThrow(() -> new QuizNotFoundException(quizId));

        Session session = Session.create(RoomCode.random(random), quiz.id(), hostId, quiz.title(),
                quiz.questions(), clock.instant());
        for (int attempt = 1; ; attempt++) {
            try {
                sessions.add(session);
                initializeLiveState(session);
                return session;
            } catch (RoomCodeTakenException e) {
                if (attempt == MAX_ROOM_CODE_ATTEMPTS) {
                    throw new IllegalStateException(
                            "No free room code after " + MAX_ROOM_CODE_ATTEMPTS + " attempts", e);
                }
                session = session.withRoomCode(RoomCode.random(random));
            }
        }
    }

    private void initializeLiveState(Session session) {
        try {
            liveState.initialize(session.id(), session.status());
        } catch (LiveStateUnavailableException e) {
            log.warn("Live state for session {} not written; it will be rebuilt from Postgres on first read",
                    session.id());
        }
    }
}
