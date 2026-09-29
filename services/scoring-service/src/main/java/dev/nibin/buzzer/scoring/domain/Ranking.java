package dev.nibin.buzzer.scoring.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Standard competition ranking ("1224"): equal points share a rank, and the next rank skips the places they took.
 * 1000, 900, 900, 800 → ranks 1, 2, 2, 4. The order among equal points is whatever the input's order was (Redis and
 * the SQL both break ties by playerId, descending), so it is stable but means nothing.
 */
public final class Ranking {

    private Ranking() {
    }

    /** One ranked line. */
    public record Ranked<T>(int rank, T item) {
    }

    /**
     * @param bestFirst already sorted by points, highest first (the caller's query or Redis did the sorting)
     * @throws IllegalArgumentException if it isn't sorted: ranks computed from an unsorted list would be wrong
     */
    public static <T> List<Ranked<T>> rank(List<T> bestFirst, ToIntFunction<T> points) {
        List<Ranked<T>> ranked = new ArrayList<>(bestFirst.size());
        for (int i = 0; i < bestFirst.size(); i++) {
            T item = bestFirst.get(i);
            int rank = i + 1;
            if (i > 0) {
                int previousPoints = points.applyAsInt(bestFirst.get(i - 1));
                int thisPoints = points.applyAsInt(item);
                if (thisPoints > previousPoints) {
                    throw new IllegalArgumentException("not sorted best first at position " + i);
                }
                if (thisPoints == previousPoints) {
                    rank = ranked.get(i - 1).rank();
                }
            }
            ranked.add(new Ranked<>(rank, item));
        }
        return List.copyOf(ranked);
    }
}
