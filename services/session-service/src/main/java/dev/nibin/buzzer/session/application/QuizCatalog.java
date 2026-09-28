package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.domain.SessionQuestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port to quiz-service: where published quizzes come from. The HTTP adapter in infrastructure implements it.
 */
public interface QuizCatalog {

    /**
     * The published quiz with this id, correct answers included; empty if it doesn't exist or is still a draft.
     *
     * @throws QuizServiceUnavailableException if quiz-service can't answer right now (down, slow, or the
     *                                         circuit breaker is open)
     */
    Optional<PublishedQuiz> publishedQuiz(UUID quizId);

    /** Questions in play order. ownerId is the host who wrote the quiz. */
    record PublishedQuiz(UUID id, UUID ownerId, String title, List<SessionQuestion> questions) {
    }
}
