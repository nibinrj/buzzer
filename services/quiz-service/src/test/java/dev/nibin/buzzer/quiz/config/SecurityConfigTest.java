package dev.nibin.buzzer.quiz.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of token verification that the MockMvc jwt() tests skip (they never decode a real token):
 * the roles mapping, and the validator applied to real RS256 tokens shaped exactly like identity-service's.
 */
class SecurityConfigTest {

    private static RSAKey identityKey;
    private static NimbusJwtEncoder identityEncoder;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void keys() throws JOSEException {
        identityKey = new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        identityEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(identityKey)));
        // Same validator as the real bean; only the key source differs (a public key instead of a JWKS URL).
        decoder = NimbusJwtDecoder.withPublicKey(identityKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(SecurityConfig.jwtValidator("buzzer-identity"));
    }

    @Test
    void rolesClaimBecomesSpringRoles() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("roles", List.of("HOST", "PLAYER")).build();

        assertThat(SecurityConfig.rolesToAuthorities().convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_HOST", "ROLE_PLAYER");
    }

    @Test
    void acceptsATokenExactlyLikeIdentityServiceIssues() {
        // Header {alg: RS256, kid} and claims {iss, sub, iat, exp, roles}: what AccessTokenIssuer produces.
        String token = sign("buzzer-identity", Instant.now().plus(Duration.ofMinutes(15)));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("HOST");
        assertThat(SecurityConfig.jwtAuthenticationConverter().convert(jwt).getName()).isEqualTo(jwt.getSubject());
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        String token = sign("someone-else", Instant.now().plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void rejectsAnExpiredToken() {
        // More than the default 60 s clock skew in the past.
        String token = sign("buzzer-identity", Instant.now().minus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    private static String sign(String issuer, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15)))
                .expiresAt(expiresAt)
                .claim("roles", List.of("HOST"))
                .build();
        return identityEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
