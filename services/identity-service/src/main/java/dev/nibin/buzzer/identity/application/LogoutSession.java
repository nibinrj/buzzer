package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Use case: end one login session by revoking its whole refresh-token family.
 * Other sessions of the same user (other devices) are untouched. Access tokens already issued stay
 * valid until they expire (at most 15 minutes): they are stateless and can't be revoked.
 */
@Service
public class LogoutSession {

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public LogoutSession(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /** Idempotent and silent: an unknown or already-revoked token is not an error, and nothing is revealed. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(RefreshTokenSecret.hash(rawRefreshToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), clock.instant()));
    }
}
