package dev.nibin.buzzer.scoring.domain;

import java.util.UUID;

/** A player's total points in one session: one line of a leaderboard. */
public record PlayerPoints(UUID playerId, int points) {
}
