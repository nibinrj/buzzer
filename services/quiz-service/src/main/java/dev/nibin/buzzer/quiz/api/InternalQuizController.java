package dev.nibin.buzzer.quiz.api;

import dev.nibin.buzzer.quiz.application.QuizSnapshots;
import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Question;
import dev.nibin.buzzer.quiz.domain.Quiz;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Service-to-service API for session-service. NOT for users, and it returns correct answers.
 * <p>
 * No authentication here (see SecurityConfig): locally anyone on the machine can call it. In AWS it is
 * protected by the network instead: the gateway/ALB never routes /internal/**, and quiz-service's security
 * group only accepts traffic from the gateway and from session-service. If that ever changes (e.g. the
 * service becomes reachable from elsewhere), this endpoint needs real service-to-service auth first.
 */
@RestController
@RequestMapping("/internal/quizzes")
public class InternalQuizController {

    private final QuizSnapshots snapshots;

    public InternalQuizController(QuizSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    /** PUBLISHED quizzes only; a draft or unknown id is 404. Published quizzes never change, so this is stable. */
    @GetMapping("/{quizId}/snapshot")
    public QuizSnapshot snapshot(@PathVariable UUID quizId) {
        return QuizSnapshot.from(snapshots.publishedQuiz(quizId));
    }

    /**
     * The contract with session-service. Questions and options are in play order. ownerId lets session-service
     * check that the host starting a game owns the quiz.
     */
    public record QuizSnapshot(UUID id, UUID ownerId, String title, List<SnapshotQuestion> questions) {

        static QuizSnapshot from(Quiz quiz) {
            return new QuizSnapshot(quiz.id(), quiz.ownerId(), quiz.title(),
                    quiz.questions().stream().map(SnapshotQuestion::from).toList());
        }
    }

    public record SnapshotQuestion(UUID id, String text, int timeLimitSeconds, List<SnapshotOption> options) {

        static SnapshotQuestion from(Question question) {
            return new SnapshotQuestion(question.id(), question.text(), question.timeLimitSeconds(),
                    question.options().stream().map(SnapshotOption::from).toList());
        }
    }

    public record SnapshotOption(String text, boolean correct) {

        static SnapshotOption from(Option option) {
            return new SnapshotOption(option.text(), option.correct());
        }
    }
}
