package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;
import dev.nibin.buzzer.identity.application.RefreshSession.InvalidRefreshTokenException;
import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every branch of the rotation / reuse-detection decision, with mocked ports. */
class RefreshSessionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String RAW = "raw-refresh-token-value";
    private static final User USER = User.register("host@test.dev", "$2a$10$hash", Set.of(Role.HOST), NOW);
    private static final UUID FAMILY = UUID.randomUUID();

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final RefreshTokenIssuer refreshTokenIssuer = mock(RefreshTokenIssuer.class);
    private final RefreshSession refreshSession = new RefreshSession(refreshTokens, users, accessTokenIssuer,
            refreshTokenIssuer, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void activeTokenIsRotatedIntoANewPairForTheCurrentUser() {
        RefreshToken token = stored(null, null, NOW.plus(Duration.ofDays(7)));
        AccessToken access = new AccessToken("signed.jwt.value", Duration.ofMinutes(15));
        when(refreshTokens.markUsed(token.id(), NOW)).thenReturn(true);
        when(users.findById(USER.id())).thenReturn(Optional.of(USER));
        when(accessTokenIssuer.issueFor(USER)).thenReturn(access);
        when(refreshTokenIssuer.rotate(token)).thenReturn("next-refresh-value");

        SessionTokens tokens = refreshSession.refresh(RAW);

        assertThat(tokens.accessToken()).isSameAs(access);
        assertThat(tokens.refreshToken()).isEqualTo("next-refresh-value");
        verify(refreshTokens, never()).revokeFamily(any(), any());
    }

    @Test
    void unknownTokenIsInvalid() {
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshSession.refresh(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void expiredTokenIsInvalidWithoutRevokingAnything() {
        stored(null, null, NOW);

        assertThatThrownBy(() -> refreshSession.refresh(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens, never()).revokeFamily(any(), any());
        verify(refreshTokens, never()).markUsed(any(), any());
    }

    @Test
    void revokedTokenIsInvalid() {
        stored(null, NOW.minusSeconds(60), NOW.plus(Duration.ofDays(7)));

        assertThatThrownBy(() -> refreshSession.refresh(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens, never()).markUsed(any(), any());
    }

    @Test
    void reusingAUsedTokenRevokesTheWholeFamily() {
        stored(NOW.minusSeconds(60), null, NOW.plus(Duration.ofDays(7)));

        assertThatThrownBy(() -> refreshSession.refresh(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens).revokeFamily(FAMILY, NOW);
        verify(refreshTokenIssuer, never()).rotate(any());
    }

    @Test
    void losingTheRaceToMarkItUsedCountsAsReuse() {
        RefreshToken token = stored(null, null, NOW.plus(Duration.ofDays(7)));
        when(refreshTokens.markUsed(token.id(), NOW)).thenReturn(false);

        assertThatThrownBy(() -> refreshSession.refresh(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens).revokeFamily(FAMILY, NOW);
        verify(accessTokenIssuer, never()).issueFor(any());
    }

    /** Stubs the lookup by the hash of RAW. */
    private RefreshToken stored(Instant usedAt, Instant revokedAt, Instant expiresAt) {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), USER.id(), FAMILY, RefreshTokenSecret.hash(RAW),
                NOW.minus(Duration.ofDays(1)), expiresAt, usedAt, revokedAt);
        when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash(RAW))).thenReturn(Optional.of(token));
        return token;
    }
}
