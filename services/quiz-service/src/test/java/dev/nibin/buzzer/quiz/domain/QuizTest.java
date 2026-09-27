package dev.nibin.buzzer.quiz.domain;

import dev.nibin.buzzer.quiz.domain.Quiz.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static dev.nibin.buzzer.quiz.domain.QuestionTest.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final List<Option> VALID_OPTIONS = options(2);

    @Test
    void createStartsAsAnEmptyDraft() {
        Quiz quiz = Quiz.create(OWNER, "  General knowledge ");

        assertThat(quiz.id()).isNotNull();
        assertThat(quiz.ownerId()).isEqualTo(OWNER);
        assertThat(quiz.title()).isEqualTo("General knowledge");
        assertThat(quiz.status()).isEqualTo(Status.DRAFT);
        assertThat(quiz.questions()).isEmpty();
        assertThat(quiz.version()).isZero();
    }

    @Test
    void titleIsRequiredAndAtMost120Characters() {
        assertThat(Quiz.create(OWNER, "x".repeat(120)).title()).hasSize(120);
        assertThatThrownBy(() -> Quiz.create(OWNER, "x".repeat(121))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quiz.create(OWNER, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quiz.create(null, "Title")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void draftCanBeRenamed() {
        Quiz quiz = Quiz.create(OWNER, "Old");

        quiz.rename(" New ");

        assertThat(quiz.title()).isEqualTo("New");
    }

    @Test
    void questionsKeepTheirOrderAndCanBeReplacedInPlace() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        Question first = quiz.addQuestion("First?", 10, VALID_OPTIONS);
        Question second = quiz.addQuestion("Second?", 20, VALID_OPTIONS);
        Question third = quiz.addQuestion("Third?", 30, VALID_OPTIONS);

        Question replaced = quiz.replaceQuestion(second.id(), "Second, edited?", 45, options(3));

        assertThat(replaced.id()).isEqualTo(second.id());
        assertThat(quiz.questions()).extracting(Question::text).containsExactly("First?", "Second, edited?", "Third?");
        assertThat(quiz.questions().get(1).timeLimitSeconds()).isEqualTo(45);
        assertThat(quiz.questions()).extracting(Question::id).containsExactly(first.id(), second.id(), third.id());
    }

    @Test
    void questionsCanBeRemoved() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        Question first = quiz.addQuestion("First?", 10, VALID_OPTIONS);
        Question second = quiz.addQuestion("Second?", 20, VALID_OPTIONS);

        quiz.removeQuestion(first.id());

        assertThat(quiz.questions()).containsExactly(second);
    }

    @Test
    void unknownQuestionIdIsRejected() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> quiz.replaceQuestion(unknown, "Q", 20, VALID_OPTIONS))
                .isInstanceOf(QuestionNotFoundException.class);
        assertThatThrownBy(() -> quiz.removeQuestion(unknown)).isInstanceOf(QuestionNotFoundException.class);
    }

    @Test
    void atMost50Questions() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        IntStream.range(0, 50).forEach(i -> quiz.addQuestion("Q" + i, 20, VALID_OPTIONS));

        assertThatThrownBy(() -> quiz.addQuestion("One too many", 20, VALID_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
        assertThat(quiz.questions()).hasSize(50);
    }

    @Test
    void questionsListIsASnapshotThatCannotChangeTheQuiz() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        quiz.addQuestion("Q", 20, VALID_OPTIONS);

        List<Question> snapshot = quiz.questions();

        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(quiz.questions()).hasSize(1);
    }

    @Test
    void completeDraftCanBePublished() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        quiz.addQuestion("Q", 20, VALID_OPTIONS);

        quiz.publish();

        assertThat(quiz.status()).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void publishRequiresAtLeastOneQuestion() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");

        assertThatThrownBy(quiz::publish)
                .isInstanceOfSatisfying(QuizNotPublishableException.class, e ->
                        assertThat(e.problems()).containsExactly("A quiz needs at least one question"));
        assertThat(quiz.status()).isEqualTo(Status.DRAFT);
    }

    @Test
    void publishListsEveryProblemWithOneBasedQuestionNumbers() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        quiz.addQuestion("Fine", 20, VALID_OPTIONS);
        quiz.addQuestion("One option", 20, List.of(new Option("Only", true)));
        quiz.addQuestion("No correct option", 20, List.of(new Option("A", false), new Option("B", false)));

        assertThatThrownBy(quiz::publish)
                .isInstanceOfSatisfying(QuizNotPublishableException.class, e ->
                        assertThat(e.problems()).containsExactly(
                                "Question 2: needs at least 2 options (has 1)",
                                "Question 3: needs exactly one correct option (has 0)"));
        assertThat(quiz.status()).isEqualTo(Status.DRAFT);
    }

    @Test
    void publishedQuizRejectsEveryChange() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        Question question = quiz.addQuestion("Q", 20, VALID_OPTIONS);
        quiz.publish();

        assertThatThrownBy(() -> quiz.rename("New")).isInstanceOf(QuizNotEditableException.class);
        assertThatThrownBy(() -> quiz.addQuestion("Q2", 20, VALID_OPTIONS))
                .isInstanceOf(QuizNotEditableException.class);
        assertThatThrownBy(() -> quiz.replaceQuestion(question.id(), "Q", 20, VALID_OPTIONS))
                .isInstanceOf(QuizNotEditableException.class);
        assertThatThrownBy(() -> quiz.removeQuestion(question.id())).isInstanceOf(QuizNotEditableException.class);
        assertThatThrownBy(quiz::publish).isInstanceOf(QuizNotEditableException.class);

        assertThat(quiz.title()).isEqualTo("Quiz");
        assertThat(quiz.questions()).containsExactly(question);
    }

    @Test
    void publishedQuizIsCheckedBeforeTheQuestionIdIsLookedUp() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        quiz.addQuestion("Q", 20, VALID_OPTIONS);
        quiz.publish();

        // "Published" wins over "no such question": nothing about a published quiz can be changed.
        assertThatThrownBy(() -> quiz.removeQuestion(UUID.randomUUID())).isInstanceOf(QuizNotEditableException.class);
    }

    @Test
    void rehydrationChecksStructureButNotPublishRules() {
        UUID id = UUID.randomUUID();
        Question incomplete = Question.create("Draft question", 20, List.of());

        Quiz quiz = new Quiz(id, OWNER, "Stored", Status.DRAFT, List.of(incomplete), 7);

        assertThat(quiz.version()).isEqualTo(7);
        assertThat(quiz.questions()).containsExactly(incomplete);
        assertThatThrownBy(() -> new Quiz(id, OWNER, "Stored", Status.DRAFT, List.of(incomplete, incomplete), 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void equalityIsById() {
        UUID id = UUID.randomUUID();

        assertThat(new Quiz(id, OWNER, "A", Status.DRAFT, List.of(), 0))
                .isEqualTo(new Quiz(id, OWNER, "B", Status.PUBLISHED, List.of(), 3))
                .isNotEqualTo(Quiz.create(OWNER, "A"));
    }
}
