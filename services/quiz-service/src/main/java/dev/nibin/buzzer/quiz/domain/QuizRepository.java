package dev.nibin.buzzer.quiz.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for storing quizzes. Use cases depend on this interface; the JPA adapter in infrastructure
 * implements it.
 */
public interface QuizRepository {

    /**
     * Inserts a new quiz or updates an existing one, questions included.
     *
     * @return the stored quiz, carrying its new version
     */
    Quiz save(Quiz quiz);

    Optional<Quiz> findById(UUID id);

    List<Quiz> findByOwnerId(UUID ownerId);
}
