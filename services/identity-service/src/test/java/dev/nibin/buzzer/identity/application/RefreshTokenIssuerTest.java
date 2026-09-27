package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.config.JwtProperties;
import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import dev.nibin.buzzer.identity.domain.RefreshTokenSecret;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RefreshTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final RefreshTokenIssuer issuer = new RefreshTokenIssuer(refreshTokens,
            new JwtProperties(null, null, "buzzer-identity", Duration.ofMinutes(15), Duration.ofHours(3),
                    Duration.ofDays(7)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesOnlyTheHashOfTheValueItReturns() {
        String value = issuer.startSession(USER);

        RefreshToken stored = storedTokens(1)[0];
        assertThat(stored.tokenHash()).isEqualTo(RefreshTokenSecret.hash(value)).isNotEqualTo(value);
        assertThat(stored.userId()).isEqualTo(USER);
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(stored.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void everyLoginStartsANewFamily() {
        issuer.startSession(USER);
        issuer.startSession(USER);

        RefreshToken[] stored = storedTokens(2);
        assertThat(stored[0].familyId()).isNotEqualTo(stored[1].familyId());
    }

    @Test
    void rotationStaysInTheSameFamilyWithANewValue() {
        UUID family = UUID.randomUUID();
        RefreshToken used = RefreshToken.issue(USER, family, RefreshTokenSecret.hash("old"), NOW.minusSeconds(60),
                Duration.ofDays(7));

        String value = issuer.rotate(used);

        RefreshToken successor = storedTokens(1)[0];
        assertThat(successor.familyId()).isEqualTo(family);
        assertThat(successor.userId()).isEqualTo(USER);
        assertThat(successor.id()).isNotEqualTo(used.id());
        assertThat(value).isNotEqualTo("old");
    }

    private RefreshToken[] storedTokens(int expected) {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens, times(expected)).add(captor.capture());
        return captor.getAllValues().toArray(RefreshToken[]::new);
    }
}
