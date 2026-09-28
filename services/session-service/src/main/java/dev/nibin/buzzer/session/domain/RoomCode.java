package dev.nibin.buzzer.session.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The code players type to join: 6 characters from a 32-symbol alphabet with no look-alikes
 * (no 0/O, no 1/I), so it survives being read out loud or off a projector. 32^6 is about 10^9 codes.
 * <p>
 * Uniqueness is not this class's job: the database's unique constraint decides, and CreateSession retries
 * with a new code on a collision.
 */
public record RoomCode(String value) {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 6;

    public RoomCode {
        Objects.requireNonNull(value, "value");
        if (value.length() != LENGTH || !value.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            throw new IllegalArgumentException("not a room code: " + value);
        }
    }

    /**
     * What a player typed, forgiving case and surrounding spaces ("  abc234 " → ABC234). Empty if it can't be a
     * room code at all, which callers treat exactly like a code no session has.
     */
    public static Optional<RoomCode> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = input.strip().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(new RoomCode(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A random code. Production passes a SecureRandom, so codes can't be predicted from earlier ones. */
    public static RoomCode random(RandomGenerator random) {
        char[] chars = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new RoomCode(new String(chars));
    }
}
