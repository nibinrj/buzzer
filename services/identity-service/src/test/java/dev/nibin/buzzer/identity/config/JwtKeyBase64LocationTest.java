package dev.nibin.buzzer.identity.config;

import com.nimbusds.jose.jwk.RSAKey;
import dev.nibin.buzzer.identity.TestJwtKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On ECS Fargate there is no secret volume to mount the PEM files from, so the keys arrive as environment variables
 * holding Spring Boot's base64: resource location (the PEM file's bytes, base64-encoded). This runs a real
 * SpringApplication, because Boot registers the base64: resolver while starting one; plain Spring would not
 * understand the prefix.
 */
class JwtKeyBase64LocationTest {

    @TempDir
    private Path dir;

    @Test
    void loadsTheKeyPairFromBase64Locations() throws Exception {
        KeyPair pair = TestJwtKeys.generateKeyPair();
        String privateKey = base64Location(TestJwtKeys.writePrivateKey(pair, dir.resolve("private.pem")));
        String publicKey = base64Location(TestJwtKeys.writePublicKey(pair, dir.resolve("public.pem")));

        // Command-line arguments: they outrank application.yml, whose ${IDENTITY_JWT_*} placeholders have no value here.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(JwtConfig.class)
                .web(WebApplicationType.NONE)
                .run("--identity.jwt.private-key-location=" + privateKey,
                        "--identity.jwt.public-key-location=" + publicKey)) {

            assertThat(context.getBean(RSAKey.class).toRSAPublicKey()).isEqualTo(pair.getPublic());
        }
    }

    /** What Terraform writes: "base64:" + filebase64(<pem file>). */
    private static String base64Location(Path pemFile) throws IOException {
        return "base64:" + Base64.getEncoder().encodeToString(Files.readAllBytes(pemFile));
    }
}
