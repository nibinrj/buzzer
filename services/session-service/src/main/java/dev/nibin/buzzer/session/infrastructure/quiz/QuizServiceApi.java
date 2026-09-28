package dev.nibin.buzzer.session.infrastructure.quiz;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;
import java.util.UUID;

/**
 * quiz-service's internal API, declared as a Java interface. No implementation is written by hand:
 * QuizClientConfig generates one that turns each call into an HTTP request (Spring's HTTP interface clients).
 * <p>
 * A 4xx/5xx answer is thrown as RestClient's HttpClientErrorException / HttpServerErrorException; a network
 * failure or timeout as ResourceAccessException.
 */
@HttpExchange("/internal/quizzes")
public interface QuizServiceApi {

    @GetExchange("/{quizId}/snapshot")
    QuizSnapshotResponse snapshot(@PathVariable UUID quizId);

    /**
     * Mirrors quiz-service's InternalQuizController.QuizSnapshot. A copy, not a shared class: the two services
     * only share the JSON. Jackson 3 ignores unknown fields by default, so quiz-service can add fields safely.
     */
    record QuizSnapshotResponse(UUID id, UUID ownerId, String title, List<SnapshotQuestion> questions) {
    }

    record SnapshotQuestion(UUID id, String text, int timeLimitSeconds, List<SnapshotOption> options) {
    }

    record SnapshotOption(String text, boolean correct) {
    }
}
