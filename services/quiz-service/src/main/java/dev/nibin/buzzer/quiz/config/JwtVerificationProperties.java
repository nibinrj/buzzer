package dev.nibin.buzzer.quiz.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** quiz.security.jwt.*: where to fetch identity-service's public keys, and which issuer to accept. */
@Validated
@ConfigurationProperties("quiz.security.jwt")
public record JwtVerificationProperties(@NotBlank String jwkSetUri, @NotBlank String issuer) {
}
