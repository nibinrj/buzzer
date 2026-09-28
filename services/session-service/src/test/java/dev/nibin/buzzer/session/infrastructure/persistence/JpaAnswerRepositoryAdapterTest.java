package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The answers adapter against real PostgreSQL, and the V2 constraints that back up the Redis script. Tests commit,
 * so each one uses a fresh session.
 */
@ApiIntegrationTest
class JpaAnswerRepositoryAdapterTest {

    // Postgres keeps microseconds; a nanosecond Instant would not compare equal after the round trip.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private AnswerRepository answers;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private PlayerRepository players;

    private Session session;

    @BeforeEach
    void newSession() {
        session = Session.create(RoomCode.random(new SecureRandom()), UUID.randomUUID(), UUID.randomUUID(),
                "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0)),
                NOW);
        sessions.add(session);
    }

    @Test
    void storesAndFindsAnAnswer() {
        Player ada = join("Ada");
        Answer answer = answer(questionId(), ada, 0, true, 1, 1);

        answers.add(answer);

        assertThat(answers.find(session.id(), questionId(), ada.playerId())).contains(answer);
        assertThat(answers.find(session.id(), questionId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aSecondAnswerOfOnePlayerToOneQuestionIsRejected() {
        Player ada = join("Ada");
        answers.add(answer(questionId(), ada, 1, false, 1, 0));

        assertThatThrownBy(() -> answers.add(answer(questionId(), ada, 0, true, 2, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneSeqCannotBeGivenToTwoAnswers() {
        answers.add(answer(questionId(), join("Ada"), 1, false, 1, 0));

        assertThatThrownBy(() -> answers.add(answer(questionId(), join("Bob"), 1, false, 1, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aQuestionThatIsNotOneOfTheSessionsIsRejected() {
        Player ada = join("Ada");

        assertThatThrownBy(() -> answers.add(answer(UUID.randomUUID(), ada, 0, true, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers ---

    private Answer answer(UUID questionId, Player player, int option, boolean correct, long seq, int correctRank) {
        return new Answer(UUID.randomUUID(), session.id(), questionId, player.playerId(), option, correct, seq,
                correctRank, NOW);
    }

    private UUID questionId() {
        return session.questions().get(0).questionId();
    }

    private Player join(String name) {
        Player player = Player.join(session.id(), UUID.randomUUID(), name, NOW);
        players.add(player);
        return player;
    }
}
