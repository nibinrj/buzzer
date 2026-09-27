package dev.nibin.buzzer.identity.application;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;
import dev.nibin.buzzer.identity.config.JwtProperties;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real RS256 signing with a generated key; every token is verified with the public key. */
class AccessTokenIssuerTest {

    private static final String ISSUER = "buzzer-identity";
    // Real current time (the decoder rejects expired tokens), truncated to whole seconds like JWT timestamps.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static RSAKey signingKey;
    private static JwtDecoder decoder;

    private final AccessTokenIssuer issuer = new AccessTokenIssuer(
            new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey))),
            new JwtProperties(null, null, ISSUER, Duration.ofMinutes(15), Duration.ofHours(3), Duration.ofDays(7)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeAll
    static void generateKey() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
    }

    @Test
    void userTokenHasSubRolesIssuerAndFifteenMinuteExpiry() {
        User user = User.register("host@test.dev", "$2a$10$hash", EnumSet.of(Role.PLAYER, Role.HOST), Instant.now());

        AccessToken token = issuer.issueFor(user);

        // decode() verifies the signature with the public key and rejects expired tokens.
        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("HOST", "PLAYER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getClaims()).doesNotContainKey("nickname");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(token.expiresIn()).isEqualTo(Duration.ofMinutes(15));
        // What a resource server (the gateway, later) will check.
        assertThat(new JwtIssuerValidator(ISSUER).validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void guestTokenIsAThreeHourPlayerTokenWithNickname() {
        UUID guestId = UUID.randomUUID();

        AccessToken token = issuer.issueForGuest(guestId, "Quiz Fan");

        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getSubject()).isEqualTo(guestId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("PLAYER");
        assertThat(jwt.getClaimAsString("nickname")).isEqualTo("Quiz Fan");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(3)));
        assertThat(token.expiresIn()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void tokenDoesNotAppearInToString() {
        AccessToken token = issuer.issueForGuest(UUID.randomUUID(), "Quiz Fan");

        assertThat(token.toString()).doesNotContain(token.value());
    }
}
