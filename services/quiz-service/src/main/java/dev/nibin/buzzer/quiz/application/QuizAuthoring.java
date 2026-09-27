package dev.nibin.buzzer.quiz.application;

import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Question;
import dev.nibin.buzzer.quiz.domain.Quiz;
import dev.nibin.buzzer.quiz.domain.QuizModifiedConcurrentlyException;
import dev.nibin.buzzer.quiz.domain.QuizRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Use cases for a host authoring their own quizzes. Every operation is owner-only: someone else's quiz
 * is reported as not found. Every change carries the version the client last saw; if the quiz has been
 * saved since, the change is refused instead of silently overwriting the other save.
 */
@Service
public class QuizAuthoring {

    private final QuizRepository quizzes;

    public QuizAuthoring(QuizRepository quizzes) {
        this.quizzes = quizzes;
    }

    @Transactional
    public Quiz create(UUID ownerId, String title) {
        return quizzes.save(Quiz.create(ownerId, title));
    }

    @Transactional(readOnly = true)
    public Quiz get(UUID quizId, UUID userId) {
        return loadOwned(quizId, userId);
    }

    /** Sorted by title (case-insensitive), then id, so the order is stable. */
    @Transactional(readOnly = true)
    public List<Quiz> listMine(UUID ownerId) {
        return quizzes.findByOwnerId(ownerId).stream()
                .sorted(Comparator.comparing(Quiz::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Quiz::id))
                .toList();
    }

    @Transactional
    public Quiz rename(UUID quizId, UUID userId, long expectedVersion, String title) {
        Quiz quiz = loadForChange(quizId, userId, expectedVersion);
        quiz.rename(title);
        return quizzes.save(quiz);
    }

    @Transactional
    public AddedQuestion addQuestion(UUID quizId, UUID userId, long expectedVersion, String text,
            int timeLimitSeconds, List<Option> options) {
        Quiz quiz = loadForChange(quizId, userId, expectedVersion);
        Question question = quiz.addQuestion(text, timeLimitSeconds, options);
        return new AddedQuestion(quizzes.save(quiz), question.id());
    }

    @Transactional
    public Quiz replaceQuestion(UUID quizId, UUID questionId, UUID userId, long expectedVersion, String text,
            int timeLimitSeconds, List<Option> options) {
        Quiz quiz = loadForChange(quizId, userId, expectedVersion);
        quiz.replaceQuestion(questionId, text, timeLimitSeconds, options);
        return quizzes.save(quiz);
    }

    /**
     * DRAFT → PUBLISHED if the domain's publish rules pass. From then on the quiz is immutable.
     *
     * @throws dev.nibin.buzzer.quiz.domain.QuizNotPublishableException listing every problem (nothing is saved)
     */
    @Transactional
    public Quiz publish(UUID quizId, UUID userId, long expectedVersion) {
        Quiz quiz = loadForChange(quizId, userId, expectedVersion);
        quiz.publish();
        return quizzes.save(quiz);
    }

    public record AddedQuestion(Quiz quiz, UUID questionId) {
    }

    private Quiz loadOwned(UUID quizId, UUID userId) {
        return quizzes.findById(quizId)
                .filter(quiz -> quiz.ownerId().equals(userId))
                .orElseThrow(() -> new QuizNotFoundException(quizId));
    }

    /**
     * Ownership first (a stranger gets 404 whatever version they send), then the version the client last
     * saw. The repository's save repeats the version check atomically, for saves that race this one.
     */
    private Quiz loadForChange(UUID quizId, UUID userId, long expectedVersion) {
        Quiz quiz = loadOwned(quizId, userId);
        if (quiz.version() != expectedVersion) {
            throw new QuizModifiedConcurrentlyException(quizId);
        }
        return quiz;
    }
}
