package dev.nibin.buzzer.session.api;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.config.SecurityConfig;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/sessions/{id}/state end to end: who may read it, what it contains, and the rebuild after Redis loses it. */
@ApiIntegrationTest
class SessionStateApiTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void aPlayerSeesTheLobbyWithEveryoneSortedByName() throws Exception {
        Session session = newSession();
        UUID ada = UUID.randomUUID();
        joinAs(session, ada, "Ada");
        joinAs(session, UUID.randomUUID(), "Zoe");
        joinAs(session, UUID.randomUUID(), "bob");

        state(session.id(), guest(ada, "Ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.id().toString()))
                .andExpect(jsonPath("$.roomCode").value(session.roomCode().value()))
                .andExpect(jsonPath("$.quizTitle").value("Capitals"))
                .andExpect(jsonPath("$.status").value("LOBBY"))
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.currentQuestion").value(nullValue()))
                .andExpect(jsonPath("$.questionDeadline").value(nullValue()))
                .andExpect(jsonPath("$.players[*].displayName", contains("Ada", "bob", "Zoe")));
    }

    @Test
    void theHostSeesItToo() throws Exception {
        Session session = newSession();

        state(session.id(), host(session.hostId())).andExpect(status().isOk());
    }

    @Test
    void outsidersAndOtherHostsGet404() throws Exception {
        Session session = newSession();

        state(session.id(), guest(UUID.randomUUID(), "Eve")).andExpect(status().isNotFound());
        state(session.id(), host(UUID.randomUUID())).andExpect(status().isNotFound());
        state(UUID.randomUUID(), host(session.hostId())).andExpect(status().isNotFound());
    }

    @Test
    void theCurrentQuestionIsShownWithoutItsAnswer() throws Exception {
        Session session = newSession();
        Instant deadline = Instant.parse("2026-09-28T10:00:15Z");
        // Question 2 of 2 is running and open.
        liveState.showQuestion(session.id(), 1, deadline);

        String body = state(session.id(), host(session.hostId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentQuestion.index").value(1))
                .andExpect(jsonPath("$.currentQuestion.text").value("Capital of Italy?"))
                .andExpect(jsonPath("$.currentQuestion.options", contains("Milan", "Rome", "Turin")))
                .andExpect(jsonPath("$.currentQuestion.revealed").value(false))
                .andExpect(jsonPath("$.currentQuestion.revealedOption").value(nullValue()))
                .andExpect(jsonPath("$.questionDeadline").value("2026-09-28T10:00:15Z"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("correct");
    }

    @Test
    void onceRevealedAReconnectingPlayerSeesTheCorrectOption() throws Exception {
        Session session = newSession();
        UUID ada = UUID.randomUUID();
        joinAs(session, ada, "Ada");
        liveState.showQuestion(session.id(), 1, Instant.parse("2026-09-28T10:00:15Z"));
        liveState.closeQuestion(session.id());

        state(session.id(), guest(ada, "Ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentQuestion.revealed").value(true))
                .andExpect(jsonPath("$.currentQuestion.revealedOption").value(1)); // Rome
    }

    @Test
    void stateLostFromRedisIsRebuiltFromPostgres() throws Exception {
        Session session = newSession();
        UUID ada = UUID.randomUUID();
        joinAs(session, ada, "Ada");
        // Redis restarted empty, or the keys expired.
        redis.delete(List.of("session:{" + session.id() + "}:state", "session:{" + session.id() + "}:players"));

        state(session.id(), guest(ada, "Ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOBBY"))
                .andExpect(jsonPath("$.players[*].displayName", contains("Ada")));

        assertThat(liveState.find(session.id())).isPresent(); // written back for the next reader
    }

    // --- helpers ---

    private ResultActions state(UUID sessionId, RequestPostProcessor token) throws Exception {
        return mvc.perform(get("/api/sessions/" + sessionId + "/state").with(token));
    }

    private void joinAs(Session session, UUID user, String nickname) throws Exception {
        mvc.perform(post("/api/sessions/join").with(guest(user, nickname)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\": \"" + session.roomCode().value() + "\"}"))
                .andExpect(status().isCreated());
    }

    private Session newSession() {
        Session session = Session.create(RoomCode.random(RANDOM), UUID.randomUUID(), UUID.randomUUID(), "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0),
                        new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15,
                                List.of("Milan", "Rome", "Turin"), 1)),
                Instant.now());
        sessions.add(session);
        liveState.initialize(session.id(), session.status());
        return session;
    }

    private static RequestPostProcessor host(UUID userId) {
        return jwt().jwt(builder -> builder.subject(userId.toString()).claim("roles", List.of("HOST")))
                .authorities(SecurityConfig.rolesToAuthorities());
    }

    private static RequestPostProcessor guest(UUID userId, String nickname) {
        return jwt().jwt(builder -> builder.subject(userId.toString())
                        .claims(claims -> claims.putAll(Map.of("roles", List.of("PLAYER"), "nickname", nickname))))
                .authorities(SecurityConfig.rolesToAuthorities());
    }
}
