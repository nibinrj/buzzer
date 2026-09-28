package dev.nibin.buzzer.session.api;

import com.jayway.jsonpath.JsonPath;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.config.SecurityConfig;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionFullException;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/sessions/join end to end (real PostgreSQL and Redis), plus the concurrency test for the row lock.
 * Sessions are created straight through the repository: quiz-service plays no part in joining.
 */
@ApiIntegrationTest
class SessionJoinApiTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private JoinSession joinSession;

    @Autowired
    private JdbcTemplate jdbc;

    // --- authentication and authorization ---

    @Test
    void noTokenIs401() throws Exception {
        join(null, "ABC234").andExpect(status().isUnauthorized());
    }

    @Test
    void aHostTokenIs403() throws Exception {
        Session session = newSession();
        join(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("roles", List.of("HOST")))
                .authorities(SecurityConfig.rolesToAuthorities()), session.roomCode().value())
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlayerTokenWithoutNicknameIs400() throws Exception {
        Session session = newSession();
        join(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("roles", List.of("PLAYER")))
                .authorities(SecurityConfig.rolesToAuthorities()), session.roomCode().value())
                .andExpect(status().isBadRequest());
    }

    // --- joining ---

    @Test
    void aGuestJoinsWithTheNicknameFromTheirToken() throws Exception {
        Session session = newSession();
        UUID user = UUID.randomUUID();

        MvcResult result = join(guest(user, "Ada"), session.roomCode().value())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/sessions/" + session.id() + "/state")))
                .andExpect(jsonPath("$.sessionId").value(session.id().toString()))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andReturn();

        UUID playerId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.playerId"));
        assertThat(players.find(session.id(), user)).get().extracting(p -> p.playerId()).isEqualTo(playerId);
        assertThat(liveState.find(session.id()).orElseThrow().players())
                .extracting(entry -> entry.playerId()).containsExactly(playerId);
    }

    @Test
    void theRoomCodeMayBeTypedInLowercaseWithSpaces() throws Exception {
        Session session = newSession();

        join(guest(UUID.randomUUID(), "Ada"), "  " + session.roomCode().value().toLowerCase() + " ")
                .andExpect(status().isCreated());
    }

    @Test
    void joiningAgainIs200WithTheSamePlayer() throws Exception {
        Session session = newSession();
        UUID user = UUID.randomUUID();
        String first = playerIdOf(join(guest(user, "Ada"), session.roomCode().value())
                .andExpect(status().isCreated()).andReturn());

        String second = playerIdOf(join(guest(user, "Ada"), session.roomCode().value())
                .andExpect(status().isOk()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(players.count(session.id())).isEqualTo(1);
    }

    @Test
    void anUnknownOrMalformedRoomCodeIs404() throws Exception {
        join(guest(UUID.randomUUID(), "Ada"), "ZZZZZZ").andExpect(status().isNotFound());
        join(guest(UUID.randomUUID(), "Ada"), "not a code").andExpect(status().isNotFound());
    }

    @Test
    void aStartedSessionIs409ForNewcomersButLetsItsPlayersBackIn() throws Exception {
        Session session = newSession();
        UUID alreadyIn = UUID.randomUUID();
        join(guest(alreadyIn, "Ada"), session.roomCode().value()).andExpect(status().isCreated());
        jdbc.update("UPDATE sessions SET status = 'IN_PROGRESS' WHERE id = ?", session.id());

        join(guest(UUID.randomUUID(), "Bob"), session.roomCode().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Session already started"));
        join(guest(alreadyIn, "Ada"), session.roomCode().value()).andExpect(status().isOk());
    }

    @Test
    void anEndedSessionIs409() throws Exception {
        Session session = newSession();
        jdbc.update("UPDATE sessions SET status = 'ENDED' WHERE id = ?", session.id());

        join(guest(UUID.randomUUID(), "Ada"), session.roomCode().value()).andExpect(status().isConflict());
    }

    @Test
    void aFullSessionIs409() throws Exception {
        Session session = newSession();
        insertPlayers(session.id(), Session.MAX_PLAYERS);

        join(guest(UUID.randomUUID(), "Ada"), session.roomCode().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Session is full"));
    }

    /**
     * The row lock at work: 20 joins released at the same instant into a session with 495 players. Without the
     * lock, up to 10 (the connection pool size) run at once, all count 495, and all insert: 505 players. With it
     * they run one by one: exactly 5 get in. (5 free places, not 10: with 10, the first 10 would fit anyway
     * and the test would pass even without the lock.)
     */
    @Test
    void twentySimultaneousJoinsAt495PlayersLetExactlyFiveIn() throws Exception {
        Session session = newSession();
        insertPlayers(session.id(), 495);
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                String nickname = "Racer" + i;
                Callable<Boolean> attempt = () -> {
                    startingGun.await();
                    try {
                        joinSession.join(session.roomCode().value(), UUID.randomUUID(), nickname);
                        return true;
                    } catch (SessionFullException e) {
                        return false;
                    }
                };
                outcomes.add(pool.submit(attempt));
            }
            startingGun.countDown();

            int joined = 0;
            for (Future<Boolean> outcome : outcomes) {
                joined += outcome.get() ? 1 : 0;
            }
            assertThat(joined).isEqualTo(5);
        }
        assertThat(players.count(session.id())).isEqualTo(Session.MAX_PLAYERS);
    }

    // --- helpers ---

    private ResultActions join(RequestPostProcessor token, String roomCode) throws Exception {
        var request = post("/api/sessions/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomCode\": \"" + roomCode + "\"}");
        return mvc.perform(token == null ? request : request.with(token));
    }

    private Session newSession() {
        Session session = Session.create(RoomCode.random(RANDOM), UUID.randomUUID(), UUID.randomUUID(), "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0)),
                Instant.now());
        sessions.add(session);
        liveState.initialize(session.id(), session.status());
        return session;
    }

    /** Straight SQL: 500 inserts through the API would only slow the test down. */
    private void insertPlayers(UUID sessionId, int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {UUID.randomUUID(), sessionId, UUID.randomUUID(), "Filler" + i,
                    Timestamp.from(Instant.now())});
        }
        jdbc.batchUpdate("INSERT INTO players (id, session_id, user_id, display_name, joined_at) VALUES (?, ?, ?, ?, ?)",
                rows);
    }

    private static String playerIdOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.playerId");
    }

    private static RequestPostProcessor guest(UUID userId, String nickname) {
        return jwt().jwt(builder -> builder.subject(userId.toString())
                        .claims(claims -> claims.putAll(Map.of("roles", List.of("PLAYER"), "nickname", nickname))))
                .authorities(SecurityConfig.rolesToAuthorities());
    }
}
