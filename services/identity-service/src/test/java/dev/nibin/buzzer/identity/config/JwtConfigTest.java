package dev.nibin.buzzer.identity.config;

import com.nimbusds.jose.jwk.RSAKey;
import dev.nibin.buzzer.identity.TestJwtKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Key loading without a Spring context: plain calls to the @Bean methods. */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    @TempDir
    private Path dir;

    @Test
    void loadsTheKeyPairFromFileLocationsAndDerivesTheKid() throws Exception {
        KeyPair pair = TestJwtKeys.generateKeyPair();
        JwtProperties properties = properties(
                TestJwtKeys.writePrivateKey(pair, dir.resolve("private.pem")),
                TestJwtKeys.writePublicKey(pair, dir.resolve("public.pem")));

        RSAKey key = config.signingKey(properties);

        assertThat(key.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
    }

    @Test
    void encoderPutsTheKidInTheTokenHeader() {
        KeyPair pair = TestJwtKeys.generateKeyPair();
        RSAKey key = config.signingKey(properties(
                TestJwtKeys.writePrivateKey(pair, dir.resolve("private.pem")),
                TestJwtKeys.writePublicKey(pair, dir.resolve("public.pem"))));

        Jwt jwt = config.jwtEncoder(key).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().subject("someone").build()));

        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID());
    }

    @Test
    void rejectsKeysFromDifferentPairs() {
        JwtProperties properties = properties(
                TestJwtKeys.writePrivateKey(TestJwtKeys.generateKeyPair(), dir.resolve("private.pem")),
                TestJwtKeys.writePublicKey(TestJwtKeys.generateKeyPair(), dir.resolve("public.pem")));

        assertThatThrownBy(() -> config.signingKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same key pair");
    }

    @Test
    void missingKeyFileNamesTheProperty() {
        KeyPair pair = TestJwtKeys.generateKeyPair();
        JwtProperties properties = properties(
                dir.resolve("does-not-exist.pem"),
                TestJwtKeys.writePublicKey(pair, dir.resolve("public.pem")));

        assertThatThrownBy(() -> config.signingKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity.jwt.private-key-location not found");
    }

    /** Resolves locations the same way Spring converts the property strings. */
    private static JwtProperties properties(Path privateKey, Path publicKey) {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        Resource privateLocation = loader.getResource(privateKey.toUri().toString());
        Resource publicLocation = loader.getResource(publicKey.toUri().toString());
        return new JwtProperties(privateLocation, publicLocation, "buzzer-identity", Duration.ofMinutes(15),
                Duration.ofHours(3), Duration.ofDays(7));
    }
}
