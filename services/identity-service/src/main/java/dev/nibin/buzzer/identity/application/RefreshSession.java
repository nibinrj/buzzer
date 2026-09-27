package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Use case: exchange a refresh token for a new access token and a new refresh token (rotation).
 * A refresh token works once. Presenting a used one means two parties hold it (the user and a thief),
 * and we can't tell which is which, so the whole family (that login session) is revoked.
 */
@Service
public class RefreshSession {

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final Clock clock;

    public RefreshSession(RefreshTokenRepository refreshTokens, UserRepository users,
            AccessTokenIssuer accessTokenIssuer, RefreshTokenIssuer refreshTokenIssuer, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.clock = clock;
    }

    /**
     * noRollbackFor: on reuse we revoke the family and THEN throw. By default the exception would roll the
     * transaction back, undoing the revocation and leaving the thief's session alive.
     *
     * @throws InvalidRefreshTokenException for unknown, expired, revoked or reused tokens (deliberately the same)
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public SessionTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(RefreshTokenSecret.hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        switch (token.status(now)) {
            case REVOKED, EXPIRED -> throw new InvalidRefreshTokenException();
            case USED -> throw revokeFamily(token, now);
            case ACTIVE -> {
                // Atomic: if a concurrent request used it a moment ago, we lose, and that is reuse too.
                if (!refreshTokens.markUsed(token.id(), now)) {
                    throw revokeFamily(token, now);
                }
            }
        }

        // The token's FK guarantees the user exists. Loading it picks up role changes since login.
        User user = users.findById(token.userId()).orElseThrow();
        return new SessionTokens(accessTokenIssuer.issueFor(user), refreshTokenIssuer.rotate(token));
    }

    private InvalidRefreshTokenException revokeFamily(RefreshToken token, Instant now) {
        refreshTokens.revokeFamily(token.familyId(), now);
        return new InvalidRefreshTokenException();
    }

    public static class InvalidRefreshTokenException extends RuntimeException {

        public InvalidRefreshTokenException() {
            super("Invalid refresh token");
        }
    }
}
