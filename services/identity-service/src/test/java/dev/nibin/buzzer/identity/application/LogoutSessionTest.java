package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogoutSessionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final LogoutSession logoutSession = new LogoutSession(refreshTokens, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void knownTokenRevokesItsFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), family, RefreshTokenSecret.hash("value"),
                NOW.minusSeconds(60), Duration.ofDays(7));
        when(refreshTokens.findByTokenHash(RefreshTokenSecret.hash("value"))).thenReturn(Optional.of(token));

        logoutSession.logout("value");

        verify(refreshTokens).revokeFamily(family, NOW);
    }

    @Test
    void unknownTokenIsSilentlyIgnored() {
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        logoutSession.logout("unknown");

        verify(refreshTokens, never()).revokeFamily(any(), any());
    }
}
