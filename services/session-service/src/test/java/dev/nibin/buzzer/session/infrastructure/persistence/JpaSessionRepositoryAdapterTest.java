package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.RoomCodeTakenException;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionOperations;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against real PostgreSQL: the Flyway schema, Hibernate's mapping (validated at startup, including
 * the varchar[] options), and the unique room code. Tests commit, so each one uses fresh random codes.
 */
@ApiIntegrationTest
class JpaSessionRepositoryAdapterTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private TransactionOperations transaction;

    @Test
    void storesAndReloadsASessionWithItsQuestionsInOrder() {
        Session session = newSession(RoomCode.random(RANDOM));

        sessions.add(session);
        Session reloaded = sessions.findById(session.id()).orElseThrow();

        assertThat(reloaded.roomCode()).isEqualTo(session.roomCode());
        assertThat(reloaded.quizId()).isEqualTo(session.quizId());
        assertThat(reloaded.hostId()).isEqualTo(session.hostId());
        assertThat(reloaded.quizTitle()).isEqualTo("Capitals");
        assertThat(reloaded.status()).isEqualTo(Session.Status.LOBBY);
        assertThat(reloaded.createdAt()).isEqualTo(session.createdAt());
        assertThat(reloaded.questions()).isEqualTo(session.questions());
    }

    @Test
    void aSecondSessionWithTheSameRoomCodeIsRejectedAndNotStored() {
        RoomCode code = RoomCode.random(RANDOM);
        sessions.add(newSession(code));
        Session clash = newSession(code);

        assertThatThrownBy(() -> sessions.add(clash)).isInstanceOf(RoomCodeTakenException.class);
        assertThat(sessions.findById(clash.id())).isEmpty();
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertThat(sessions.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theLockingLookupFindsByRoomCodeInsideATransaction() {
        Session session = newSession(RoomCode.random(RANDOM));
        sessions.add(session);

        Optional<Session> found = transaction.execute(status -> sessions.findByRoomCodeForUpdate(session.roomCode()));

        assertThat(found).get().extracting(Session::id).isEqualTo(session.id());
    }

    @Test
    void theStatusIsUpdatedUnderTheLockById() {
        Session session = newSession(RoomCode.random(RANDOM));
        sessions.add(session);

        transaction.executeWithoutResult(status -> {
            Session locked = sessions.findByIdForUpdate(session.id()).orElseThrow();
            sessions.updateStatus(locked.start());
        });

        assertThat(sessions.findById(session.id()).orElseThrow().status()).isEqualTo(Session.Status.IN_PROGRESS);
    }

    @Test
    void lockingByIdAndUpdatingRefuseToRunWithoutATransaction() {
        Session session = newSession(RoomCode.random(RANDOM));

        assertThatThrownBy(() -> sessions.findByIdForUpdate(session.id()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> sessions.updateStatus(session))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theLockingLookupRefusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> sessions.findByRoomCodeForUpdate(RoomCode.random(RANDOM)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static Session newSession(RoomCode code) {
        List<SessionQuestion> questions = List.of(
                new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0),
                new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome", "Turin"), 1),
                new SessionQuestion(UUID.randomUUID(), "Capital of Spain?", 30,
                        List.of("Seville", "Valencia", "Madrid", "Bilbao"), 2));
        // PostgreSQL stores microseconds; truncate so the reloaded Instant compares equal.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return Session.create(code, UUID.randomUUID(), UUID.randomUUID(), "Capitals", questions, now);
    }
}
