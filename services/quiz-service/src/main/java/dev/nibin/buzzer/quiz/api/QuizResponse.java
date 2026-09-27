package dev.nibin.buzzer.quiz.api;

import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Question;
import dev.nibin.buzzer.quiz.domain.Quiz;

import java.util.List;
import java.util.UUID;

/**
 * The OWNER's view of a quiz, including which option is correct. Only ever returned to the quiz's owner
 * (everyone else gets 404), which is how "never show correct answers except to the owner" holds.
 * version: send it back with the next change (optimistic locking).
 */
public record QuizResponse(UUID id, String title, Quiz.Status status, long version, List<QuestionResponse> questions) {

    static QuizResponse from(Quiz quiz) {
        return new QuizResponse(quiz.id(), quiz.title(), quiz.status(), quiz.version(),
                quiz.questions().stream().map(QuestionResponse::from).toList());
    }

    public record QuestionResponse(UUID id, String text, int timeLimitSeconds, List<OptionResponse> options) {

        static QuestionResponse from(Question question) {
            return new QuestionResponse(question.id(), question.text(), question.timeLimitSeconds(),
                    question.options().stream().map(OptionResponse::from).toList());
        }
    }

    public record OptionResponse(String text, boolean correct) {

        static OptionResponse from(Option option) {
            return new OptionResponse(option.text(), option.correct());
        }
    }

    /** One line in "my quizzes": no questions, so no answers. */
    public record Summary(UUID id, String title, Quiz.Status status, int questionCount, long version) {

        static Summary from(Quiz quiz) {
            return new Summary(quiz.id(), quiz.title(), quiz.status(), quiz.questions().size(), quiz.version());
        }
    }
}
