package dev.nibin.buzzer.session.infrastructure.quiz;

import dev.nibin.buzzer.session.application.QuizCatalog;
import dev.nibin.buzzer.session.application.QuizServiceUnavailableException;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.infrastructure.quiz.QuizServiceApi.QuizSnapshotResponse;
import dev.nibin.buzzer.session.infrastructure.quiz.QuizServiceApi.SnapshotOption;
import dev.nibin.buzzer.session.infrastructure.quiz.QuizServiceApi.SnapshotQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Implements the QuizCatalog port by calling quiz-service's snapshot endpoint through a circuit breaker.
 * <p>
 * What counts as a failure matters: a 404 is quiz-service working correctly ("no such published quiz"), so it
 * is returned as empty INSIDE the breaker and counts as a success. Otherwise a host mistyping quiz ids could
 * open the breaker and lock every other host out. Only exceptions (5xx, timeouts, refused connections) count.
 */
@Component
public class HttpQuizCatalog implements QuizCatalog {

    /** The breaker's name; QuizClientConfig configures it under this id. */
    public static final String CIRCUIT_BREAKER_ID = "quiz-service";

    private static final Logger log = LoggerFactory.getLogger(HttpQuizCatalog.class);

    private final QuizServiceApi api;
    private final CircuitBreaker circuitBreaker;

    HttpQuizCatalog(QuizServiceApi api, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.api = api;
        this.circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_ID);
    }

    @Override
    public Optional<PublishedQuiz> publishedQuiz(UUID quizId) {
        Optional<QuizSnapshotResponse> snapshot = circuitBreaker.run(() -> fetch(quizId), failure -> {
            // The fallback: runs for every failed call AND for calls the open breaker refused (CallNotPermittedException).
            log.warn("quiz-service call failed for quiz {}: {}", quizId, describe(failure));
            throw new QuizServiceUnavailableException(quizId, failure);
        });
        return snapshot.map(HttpQuizCatalog::toPublishedQuiz);
    }

    /** Never the message of an HTTP error: RestClient puts the response body in it, and bodies aren't logged. */
    private static String describe(Throwable failure) {
        return failure instanceof HttpStatusCodeException httpError
                ? "HTTP " + httpError.getStatusCode().value()
                : failure.getClass().getSimpleName();
    }

    private Optional<QuizSnapshotResponse> fetch(UUID quizId) {
        try {
            return Optional.of(api.snapshot(quizId));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private static PublishedQuiz toPublishedQuiz(QuizSnapshotResponse snapshot) {
        List<SessionQuestion> questions = snapshot.questions().stream().map(HttpQuizCatalog::toQuestion).toList();
        return new PublishedQuiz(snapshot.id(), snapshot.ownerId(), snapshot.title(), questions);
    }

    /** Options become plain texts; the one marked correct becomes correctOption (its index). */
    private static SessionQuestion toQuestion(SnapshotQuestion question) {
        List<SnapshotOption> options = question.options();
        List<Integer> correct = IntStream.range(0, options.size())
                .filter(i -> options.get(i).correct())
                .boxed()
                .toList();
        if (correct.size() != 1) {
            // quiz-service only publishes quizzes with exactly one correct option per question: a broken contract.
            throw new IllegalStateException("Question " + question.id() + " in the snapshot has "
                    + correct.size() + " correct options, expected exactly 1");
        }
        return new SessionQuestion(question.id(), question.text(), question.timeLimitSeconds(),
                options.stream().map(SnapshotOption::text).toList(), correct.getFirst());
    }
}
