package dev.nibin.buzzer.quiz.application;

import dev.nibin.buzzer.quiz.domain.Quiz;
import dev.nibin.buzzer.quiz.domain.QuizRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use case for session-service (service-to-service, not a user): the complete content of a published quiz,
 * correct answers included, so a live game can be run and answers checked. Drafts are invisible here:
 * a draft can still change, and a game must never start from something that can change under it.
 */
@Service
public class QuizSnapshots {

    private final QuizRepository quizzes;

    public QuizSnapshots(QuizRepository quizzes) {
        this.quizzes = quizzes;
    }

    /** @throws QuizNotFoundException if the quiz doesn't exist or isn't published (deliberately the same) */
    @Transactional(readOnly = true)
    public Quiz publishedQuiz(UUID quizId) {
        return quizzes.findById(quizId)
                .filter(quiz -> quiz.status() == Quiz.Status.PUBLISHED)
                .orElseThrow(() -> new QuizNotFoundException(quizId));
    }
}
