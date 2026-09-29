package dev.nibin.buzzer.scoring.domain;

/**
 * Points for one answer. They depend only on that answer (was it correct, and its place among the correct ones,
 * decided by session-service's Redis script), never on other answers or on when scoring reads it. So answers can be
 * scored in any order, and a player's total is a plain sum.
 */
public final class Points {

    static final int FIRST_CORRECT = 1000;
    static final int STEP = 100;
    static final int FLOOR = 300;

    private Points() {
    }

    /**
     * 1000 for the first correct answer, 100 fewer for each later one, never below 300; 0 for a wrong answer.
     *
     * @param correctRank place among correct answers, from 1; must be 0 for a wrong answer
     */
    public static int of(boolean correct, int correctRank) {
        if (!correct) {
            if (correctRank != 0) {
                throw new IllegalArgumentException("a wrong answer has no correctRank, got " + correctRank);
            }
            return 0;
        }
        if (correctRank < 1) {
            throw new IllegalArgumentException("a correct answer's correctRank starts at 1, got " + correctRank);
        }
        // long: a huge rank must not overflow into a large positive number
        return (int) Math.max(FLOOR, FIRST_CORRECT - (long) STEP * (correctRank - 1));
    }
}
