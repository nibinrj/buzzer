package dev.nibin.buzzer.quiz.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionTest {

    private static final Option RIGHT = new Option("Paris", true);
    private static final Option WRONG = new Option("Lyon", false);

    @Test
    void createKeepsContentAndAssignsAnId() {
        Question question = Question.create("  Capital of France? ", 20, List.of(WRONG, RIGHT));

        assertThat(question.id()).isNotNull();
        assertThat(question.text()).isEqualTo("Capital of France?");
        assertThat(question.timeLimitSeconds()).isEqualTo(20);
        assertThat(question.options()).containsExactly(WRONG, RIGHT); // order kept
    }

    @Test
    void timeLimitMustBeBetween5And60Seconds() {
        assertThat(Question.create("Q", 5, List.of()).timeLimitSeconds()).isEqualTo(5);
        assertThat(Question.create("Q", 60, List.of()).timeLimitSeconds()).isEqualTo(60);
        assertThatThrownBy(() -> Question.create("Q", 4, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Question.create("Q", 61, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atMostSixOptionsAlways() {
        assertThat(Question.create("Q", 20, options(6)).options()).hasSize(6);
        assertThatThrownBy(() -> Question.create("Q", 20, options(7)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6");
    }

    @Test
    void textIsRequiredAndAtMost300Characters() {
        assertThat(Question.create("x".repeat(300), 20, List.of()).text()).hasSize(300);
        assertThatThrownBy(() -> Question.create("x".repeat(301), 20, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Question.create(" ", 20, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullsIncludingNullOptions() {
        assertThatThrownBy(() -> new Question(null, "Q", 20, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Question.create("Q", 20, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Question.create("Q", 20, Arrays.asList(RIGHT, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void optionsAreACopyAndCannotBeModified() {
        List<Option> options = new ArrayList<>(List.of(RIGHT, WRONG));
        Question question = Question.create("Q", 20, options);

        options.add(new Option("Nice", false));

        assertThat(question.options()).hasSize(2);
        assertThatThrownBy(() -> question.options().add(WRONG)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aDraftQuestionMayBeIncompleteButReportsWhyItCantBePublished() {
        assertThat(Question.create("Q", 20, List.of()).publishProblems())
                .containsExactly("needs at least 2 options (has 0)", "needs exactly one correct option (has 0)");
        assertThat(Question.create("Q", 20, List.of(RIGHT)).publishProblems())
                .containsExactly("needs at least 2 options (has 1)");
    }

    @Test
    void publishNeedsExactlyOneCorrectOption() {
        assertThat(Question.create("Q", 20, List.of(WRONG, new Option("Nice", false))).publishProblems())
                .containsExactly("needs exactly one correct option (has 0)");
        assertThat(Question.create("Q", 20, List.of(RIGHT, new Option("Also right", true))).publishProblems())
                .containsExactly("needs exactly one correct option (has 2)");
        assertThat(Question.create("Q", 20, List.of(RIGHT, WRONG)).publishProblems()).isEmpty();
    }

    @Test
    void equalityIsById() {
        UUID id = UUID.randomUUID();

        assertThat(new Question(id, "Q", 20, List.of())).isEqualTo(new Question(id, "Other", 30, List.of(RIGHT)))
                .isNotEqualTo(Question.create("Q", 20, List.of()));
    }

    /** n options, the first one correct. */
    static List<Option> options(int n) {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            options.add(new Option("Option " + (i + 1), i == 0));
        }
        return options;
    }
}
