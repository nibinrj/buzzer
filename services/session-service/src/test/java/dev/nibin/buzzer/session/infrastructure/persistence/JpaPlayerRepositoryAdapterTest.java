package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
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

/** The players adapter against real PostgreSQL. Tests commit, so each one uses a fresh session. */
@ApiIntegrationTest
class JpaPlayerRepositoryAdapterTest {

    @Autowired
    private PlayerRepository players;

    @Autowired
    private SessionRepository sessions;

    @Test
    void storesFindsAndCountsPlayersPerSession() {
        UUID sessionId = newSession();
        UUID otherSessionId = newSession();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Player ada = Player.join(sessionId, UUID.randomUUID(), "Ada", now);
        Player bob = Player.join(sessionId, UUID.randomUUID(), "Bob", now.plusSeconds(1));
        players.add(bob);
        players.add(ada);
        players.add(Player.join(otherSessionId, ada.userId(), "Ada", now)); // same user, other session: fine

        assertThat(players.find(sessionId, ada.userId())).contains(ada);
        assertThat(players.find(sessionId, UUID.randomUUID())).isEmpty();
        assertThat(players.count(sessionId)).isEqualTo(2);
        assertThat(players.findBySession(sessionId)).containsExactly(ada, bob); // join order
    }

    @Test
    void theSameUserCannotBeInOneSessionTwice() {
        UUID sessionId = newSession();
        UUID user = UUID.randomUUID();
        players.add(Player.join(sessionId, user, "Ada", Instant.now()));

        assertThatThrownBy(() -> players.add(Player.join(sessionId, user, "Ada again", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID newSession() {
        Session session = Session.create(RoomCode.random(new SecureRandom()), UUID.randomUUID(), UUID.randomUUID(),
                "Capitals", List.of(new SessionQuestion(UUID.randomUUID(), "Q?", 20, List.of("A", "B"), 0)),
                Instant.now());
        sessions.add(session);
        return session.id();
    }
}
