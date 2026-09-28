package dev.nibin.buzzer.session.infrastructure.quiz;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.application.QuizCatalog;
import dev.nibin.buzzer.session.application.QuizCatalog.PublishedQuiz;
import dev.nibin.buzzer.session.application.QuizServiceUnavailableException;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTP client, timeouts and circuit breaker against WireMock playing quiz-service.
 * The breaker is shared by the whole test context, so each test starts from a closed breaker.
 */
@ApiIntegrationTest
class HttpQuizCatalogTest {

    @Autowired
    private QuizCatalog catalog;

    @Autowired
    private WireMockServer quizService;

    @Autowired
    private Resilience4JCircuitBreakerFactory circuitBreakerFactory;

    @BeforeEach
    void reset() {
        quizService.resetAll();
        breaker().ifPresent(CircuitBreaker::reset);
    }

    @Test
    void mapsTheSnapshotAndTurnsTheCorrectOptionIntoAnIndex() {
        UUID quizId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(okJson("""
                {"id": "%s", "ownerId": "%s", "title": "Capitals", "questions": [
                  {"id": "%s", "text": "Capital of Italy?", "timeLimitSeconds": 15, "options": [
                    {"text": "Milan", "correct": false}, {"text": "Rome", "correct": true}]}],
                 "addedLater": "ignored"}""".formatted(quizId, ownerId, questionId))));

        Optional<PublishedQuiz> quiz = catalog.publishedQuiz(quizId);

        assertThat(quiz).contains(new PublishedQuiz(quizId, ownerId, "Capitals", List.of(
                new SessionQuestion(questionId, "Capital of Italy?", 15, List.of("Milan", "Rome"), 1))));
    }

    @Test
    void notFoundIsEmptyAndNeverOpensTheBreaker() {
        quizService.stubFor(get(urlPathMatching("/internal/quizzes/.*")).willReturn(notFound()));

        for (int i = 0; i < 20; i++) {
            assertThat(catalog.publishedQuiz(UUID.randomUUID())).isEmpty();
        }

        assertThat(breaker()).get().extracting(CircuitBreaker::getState).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aServerErrorIsUnavailable() {
        UUID quizId = UUID.randomUUID();
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(serverError()));

        assertThatThrownBy(() -> catalog.publishedQuiz(quizId)).isInstanceOf(QuizServiceUnavailableException.class);
    }

    @Test
    void aBrokenConnectionIsUnavailable() {
        UUID quizId = UUID.randomUUID();
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> catalog.publishedQuiz(quizId)).isInstanceOf(QuizServiceUnavailableException.class);
    }

    @Test
    void aHangingQuizServiceIsCutOffByTheReadTimeout() {
        UUID quizId = UUID.randomUUID();
        // The read timeout is 2 s (application.yml); the stub would take 10 s.
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(okJson("{}").withFixedDelay(10_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> catalog.publishedQuiz(quizId)).isInstanceOf(QuizServiceUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void afterFiveFailuresTheBreakerOpensAndStopsCallingQuizService() {
        quizService.stubFor(get(urlPathMatching("/internal/quizzes/.*")).willReturn(serverError()));

        // minimumNumberOfCalls = 5, all failed: 100% > 50%, so the breaker opens after the fifth.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> catalog.publishedQuiz(UUID.randomUUID()))
                    .isInstanceOf(QuizServiceUnavailableException.class);
        }
        assertThat(breaker()).get().extracting(CircuitBreaker::getState).isEqualTo(CircuitBreaker.State.OPEN);

        // Refused without a request: fails in microseconds instead of waiting on a sick service.
        assertThatThrownBy(() -> catalog.publishedQuiz(UUID.randomUUID()))
                .isInstanceOf(QuizServiceUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        quizService.verify(5, getRequestedFor(urlPathMatching("/internal/quizzes/.*")));
    }

    @Test
    void aSnapshotWithoutExactlyOneCorrectOptionIsABrokenContract() {
        UUID quizId = UUID.randomUUID();
        quizService.stubFor(get(snapshotUrl(quizId)).willReturn(okJson("""
                {"id": "%s", "ownerId": "%s", "title": "Broken", "questions": [
                  {"id": "%s", "text": "Q?", "timeLimitSeconds": 15, "options": [
                    {"text": "A", "correct": true}, {"text": "B", "correct": true}]}]}"""
                .formatted(quizId, UUID.randomUUID(), UUID.randomUUID()))));

        assertThatThrownBy(() -> catalog.publishedQuiz(quizId)).isInstanceOf(IllegalStateException.class);
    }

    private Optional<CircuitBreaker> breaker() {
        return circuitBreakerFactory.getCircuitBreakerRegistry().find(HttpQuizCatalog.CIRCUIT_BREAKER_ID);
    }

    private static UrlPattern snapshotUrl(UUID quizId) {
        return urlEqualTo("/internal/quizzes/" + quizId + "/snapshot");
    }
}
