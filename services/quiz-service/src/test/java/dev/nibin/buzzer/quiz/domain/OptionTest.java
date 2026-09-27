package dev.nibin.buzzer.quiz.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionTest {

    @Test
    void textIsStripped() {
        assertThat(new Option("  Paris ", true).text()).isEqualTo("Paris");
    }

    @Test
    void rejectsBlankOrMissingText() {
        assertThatThrownBy(() -> new Option("   ", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Option(null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void textLimitIs120Characters() {
        assertThat(new Option("x".repeat(120), false).text()).hasSize(120);
        assertThatThrownBy(() -> new Option("x".repeat(121), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
    }

    @Test
    void lengthCountsCharactersNotUtf16Units() {
        // "😀" is 2 Java chars but 1 character, like Postgres counts it: 120 of them fit.
        String emoji = "😀".repeat(120);

        assertThat(new Option(emoji, false).text()).isEqualTo(emoji);
    }

    @Test
    void isAValueComparedByContent() {
        assertThat(new Option("Paris", true)).isEqualTo(new Option(" Paris ", true))
                .isNotEqualTo(new Option("Paris", false));
    }
}
