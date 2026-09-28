package dev.nibin.buzzer.session.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.jayway.jsonpath.JsonPath;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.config.SecurityConfig;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import dev.nibin.buzzer.session.infrastructure.quiz.HttpQuizCatalog;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/sessions end to end: security filter chain, controller, use case, WireMock as quiz-service,
 * real PostgreSQL.
 */
@ApiIntegrationTest
class SessionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WireMockServer quizService;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private Resilience4JCircuitBreakerFactory circuitBreakerFactory;

    @BeforeEach
    void reset() {
        quizService.resetAll();
        circuitBreakerFactory.getCircuitBreakerRegistry().find(HttpQuizCatalog.CIRCUIT_BREAKER_ID)
                .ifPresent(CircuitBreaker::reset);
    }

    // --- authentication and authorization ---

    @Test
    void noTokenIs401WithABearerChallenge() throws Exception {
        createSession(null, UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void aPlayerTokenIs403() throws Exception {
        createSession(token(UUID.randomUUID(), "PLAYER"), UUID.randomUUID())
                .andExpect(status().isForbidden());
    }

    // --- creating a session ---

    @Test
    void aHostStartsASessionFromTheirPublishedQuiz() throws Exception {
        UUID host = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        stubSnapshot(quizId, host, questionId);

        MvcResult result = createSession(host(host), quizId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomCode", matchesPattern("[A-HJ-NP-Z2-9]{6}")))
                .andExpect(header().string("Location", startsWith("/api/sessions/")))
                .andReturn();

        UUID sessionId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId"));
        Session stored = sessions.findById(sessionId).orElseThrow();
        assertThat(stored.hostId()).isEqualTo(host);
        assertThat(stored.quizId()).isEqualTo(quizId);
        assertThat(stored.status()).isEqualTo(Session.Status.LOBBY);
        assertThat(stored.roomCode().value())
                .isEqualTo(JsonPath.read(result.getResponse().getContentAsString(), "$.roomCode"));
        assertThat(stored.questions()).containsExactly(
                new SessionQuestion(questionId, "Capital of France?", 20, List.of("Lyon", "Paris"), 1));
    }

    @Test
    void twoSessionsOfTheSameQuizGetDifferentRoomCodes() throws Exception {
        UUID host = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        stubSnapshot(quizId, host, UUID.randomUUID());

        String first = roomCodeOf(createSession(host(host), quizId).andExpect(status().isCreated()).andReturn());
        String second = roomCodeOf(createSession(host(host), quizId).andExpect(status().isCreated()).andReturn());

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void someoneElsesQuizIs404() throws Exception {
        UUID quizId = UUID.randomUUID();
        stubSnapshot(quizId, UUID.randomUUID(), UUID.randomUUID());

        createSession(host(UUID.randomUUID()), quizId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Quiz not found"));
    }

    @Test
    void anUnknownOrDraftQuizIs404() throws Exception {
        UUID quizId = UUID.randomUUID();
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(notFound()));

        createSession(host(UUID.randomUUID()), quizId)
                .andExpect(status().isNotFound());
    }

    @Test
    void quizServiceDownIs503ProblemDetail() throws Exception {
        UUID quizId = UUID.randomUUID();
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(serverError()));

        createSession(host(UUID.randomUUID()), quizId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.title").value("Quizzes unavailable"));
    }

    @Test
    void aMissingQuizIdIs400WithTheField() throws Exception {
        mvc.perform(post("/api/sessions").with(host(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quizId"));
    }

    // --- helpers ---

    private ResultActions createSession(RequestPostProcessor token, UUID quizId) throws Exception {
        var request = post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quizId\": \"" + quizId + "\"}");
        return mvc.perform(token == null ? request : request.with(token));
    }

    /** A published quiz with one question whose correct option is the second one. */
    private void stubSnapshot(UUID quizId, UUID ownerId, UUID questionId) {
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(okJson("""
                {"id": "%s", "ownerId": "%s", "title": "Capitals", "questions": [
                  {"id": "%s", "text": "Capital of France?", "timeLimitSeconds": 20, "options": [
                    {"text": "Lyon", "correct": false}, {"text": "Paris", "correct": true}]}]}"""
                .formatted(quizId, ownerId, questionId))));
    }

    private static UrlPattern snapshotUrl(UUID quizId) {
        return urlEqualTo("/internal/quizzes/" + quizId + "/snapshot");
    }

    private static String roomCodeOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.roomCode");
    }

    private static RequestPostProcessor host(UUID userId) {
        return token(userId, "HOST");
    }

    private static RequestPostProcessor token(UUID userId, String role) {
        return jwt().jwt(builder -> builder.subject(userId.toString()).claim("roles", List.of(role)))
                .authorities(SecurityConfig.rolesToAuthorities());
    }
}
