package dev.nibin.buzzer.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The raw refresh-token value a client holds, and how it is stored.
 * The value goes to the client once; the database only ever sees {@link #hash(String)}.
 */
public final class RefreshTokenSecret {

    // 256 bits: far beyond brute force, so a fast hash is enough (no BCrypt, no salt).
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenSecret() {
    }

    /** 43 URL-safe characters (base64url, no padding), safe in JSON, headers and URLs. */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 as 64 lowercase hex characters. Deterministic, so the database can look a token up
     * by its hash (a salted hash like BCrypt couldn't be looked up).
     */
    public static String hash(String value) {
        Objects.requireNonNull(value, "value");
        try {
            // MessageDigest is not thread-safe: one instance per call.
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must support SHA-256, so this can't happen.
            throw new IllegalStateException(e);
        }
    }
}
