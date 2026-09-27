package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.config.JwtProperties;
import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Creates refresh tokens: stores only the hash and returns the raw value, which exists only in this
 * method's return value and in the response to the client.
 */
@Component
public class RefreshTokenIssuer {

    private final RefreshTokenRepository refreshTokens;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public RefreshTokenIssuer(RefreshTokenRepository refreshTokens, JwtProperties jwtProperties, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /** At login: the first token of a new family (a new session). */
    public String startSession(UUID userId) {
        return issue(userId, UUID.randomUUID());
    }

    /** At refresh: the successor of a token that was just marked used, in the same family. */
    public String rotate(RefreshToken used) {
        return issue(used.userId(), used.familyId());
    }

    private String issue(UUID userId, UUID familyId) {
        String value = RefreshTokenSecret.generate();
        refreshTokens.add(RefreshToken.issue(userId, familyId, RefreshTokenSecret.hash(value), clock.instant(),
                jwtProperties.refreshTokenTtl()));
        return value;
    }
}
