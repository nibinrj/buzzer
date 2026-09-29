package dev.nibin.buzzer.scoring.domain;

import dev.nibin.buzzer.scoring.domain.Ranking.Ranked;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RankingTest {

    @Test
    void distinctPointsRankOneTwoThree() {
        assertThat(ranks(1000, 900, 300)).containsExactly(1, 2, 3);
    }

    @Test
    void equalPointsShareARankAndTheNextRankSkipsTheirPlaces() {
        assertThat(ranks(1000, 900, 900, 800)).containsExactly(1, 2, 2, 4);
        assertThat(ranks(1000, 1000, 1000)).containsExactly(1, 1, 1);
        assertThat(ranks(500, 0, 0)).containsExactly(1, 2, 2);
    }

    @Test
    void theItemsKeepTheirOrder() {
        List<Ranked<Integer>> ranked = Ranking.rank(List.of(900, 900, 100), Integer::intValue);

        assertThat(ranked).extracting(Ranked::item).containsExactly(900, 900, 100);
    }

    @Test
    void nothingToRankIsAnEmptyList() {
        assertThat(ranks()).isEmpty();
    }

    @Test
    void anUnsortedListIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> ranks(300, 900))
                .withMessage("not sorted best first at position 1");
    }

    private static List<Integer> ranks(Integer... points) {
        return Ranking.rank(List.of(points), Integer::intValue).stream().map(Ranked::rank).toList();
    }
}
