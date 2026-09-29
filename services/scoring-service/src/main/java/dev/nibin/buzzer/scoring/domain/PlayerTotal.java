package dev.nibin.buzzer.scoring.domain;

import java.util.UUID;

/** Everything scoring counted for one player in one session: one line of the final results. */
public record PlayerTotal(UUID playerId, int points, int answers, int correctAnswers) {

    public PlayerPoints toPoints() {
        return new PlayerPoints(playerId, points);
    }
}
