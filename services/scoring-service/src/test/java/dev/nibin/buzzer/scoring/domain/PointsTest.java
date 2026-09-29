package dev.nibin.buzzer.scoring.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PointsTest {

    @ParameterizedTest(name = "correct, rank {0} -> {1}")
    @CsvSource({
            "1, 1000",
            "2, 900",
            "7, 400",
            "8, 300",  // 1000 - 700: the floor is reached exactly here
            "9, 300",  // would be 200: held at the floor
            "500, 300",
            "2147483647, 300" // no overflow into a large positive number
    })
    void aCorrectAnswerLosesAHundredPerEarlierCorrectOneButNeverGoesBelowThreeHundred(int rank, int points) {
        assertThat(Points.of(true, rank)).isEqualTo(points);
    }

    @Test
    void aWrongAnswerScoresNothing() {
        assertThat(Points.of(false, 0)).isZero();
    }

    @Test
    void aCorrectAnswerWithoutARankIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> Points.of(true, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Points.of(true, -1));
    }

    @Test
    void aWrongAnswerWithARankIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> Points.of(false, 1));
    }
}
