package dev.nibin.buzzer.identity.domain;

import dev.nibin.buzzer.identity.domain.RefreshToken.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String HASH = RefreshTokenSecret.hash("some token value");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID FAMILY = UUID.randomUUID();

    @Test
    void issueStartsActiveAndExpiresAfterTheTtl() {
        RefreshToken token = RefreshToken.issue(USER, FAMILY, HASH, NOW, TTL);

        assertThat(token.id()).isNotNull();
        assertThat(token.userId()).isEqualTo(USER);
        assertThat(token.familyId()).isEqualTo(FAMILY);
        assertThat(token.tokenHash()).isEqualTo(HASH);
        assertThat(token.createdAt()).isEqualTo(NOW);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.usedAt()).isEmpty();
        assertThat(token.revokedAt()).isEmpty();
        assertThat(token.status(NOW)).isEqualTo(Status.ACTIVE);
    }

    @Test
    void isExpiredFromTheExactExpiryInstant() {
        RefreshToken token = RefreshToken.issue(USER, FAMILY, HASH, NOW, TTL);

        assertThat(token.status(token.expiresAt().minusNanos(1))).isEqualTo(Status.ACTIVE);
        assertThat(token.status(token.expiresAt())).isEqualTo(Status.EXPIRED);
    }

    @Test
    void usedBeatsExpiredBecauseReuseMustBeDetectedEvenLate() {
        Instant afterExpiry = NOW.plus(TTL).plusSeconds(1);

        assertThat(stored(NOW.plusSeconds(60), null).status(afterExpiry)).isEqualTo(Status.USED);
    }

    @Test
    void revokedBeatsEverything() {
        Instant afterExpiry = NOW.plus(TTL).plusSeconds(1);

        assertThat(stored(NOW.plusSeconds(60), NOW.plusSeconds(120)).status(afterExpiry)).isEqualTo(Status.REVOKED);
        assertThat(stored(null, NOW.plusSeconds(120)).status(NOW.plusSeconds(180))).isEqualTo(Status.REVOKED);
    }

    @Test
    void rejectsAHashThatIsNotLowercaseSha256Hex() {
        assertThatThrownBy(() -> RefreshToken.issue(USER, FAMILY, HASH.toUpperCase(), NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(USER, FAMILY, "abc123", NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(USER, FAMILY, "raw-token-value-not-a-hash", NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiryNotAfterCreation() {
        assertThatThrownBy(() -> RefreshToken.issue(USER, FAMILY, HASH, NOW, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRequiredFields() {
        UUID id = UUID.randomUUID();
        Instant later = NOW.plus(TTL);

        assertThatThrownBy(() -> new RefreshToken(null, USER, FAMILY, HASH, NOW, later, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshToken(id, null, FAMILY, HASH, NOW, later, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshToken(id, USER, null, HASH, NOW, later, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshToken(id, USER, FAMILY, null, NOW, later, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshToken(id, USER, FAMILY, HASH, null, later, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshToken(id, USER, FAMILY, HASH, NOW, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityIsById() {
        UUID id = UUID.randomUUID();

        assertThat(new RefreshToken(id, USER, FAMILY, HASH, NOW, NOW.plus(TTL), null, null))
                .isEqualTo(new RefreshToken(id, USER, FAMILY, HASH, NOW, NOW.plus(TTL), NOW.plusSeconds(1), null))
                .isNotEqualTo(RefreshToken.issue(USER, FAMILY, HASH, NOW, TTL));
    }

    @Test
    void toStringDoesNotContainTheHash() {
        assertThat(RefreshToken.issue(USER, FAMILY, HASH, NOW, TTL).toString()).doesNotContain(HASH);
    }

    private static RefreshToken stored(Instant usedAt, Instant revokedAt) {
        return new RefreshToken(UUID.randomUUID(), USER, FAMILY, HASH, NOW, NOW.plus(TTL), usedAt, revokedAt);
    }
}
