package dev.nibin.buzzer.scoring.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** scoring.security.jwt.*: where to fetch identity-service's public keys, and which issuer to accept. */
@Validated
@ConfigurationProperties("scoring.security.jwt")
public record JwtVerificationProperties(@NotBlank String jwkSetUri, @NotBlank String issuer) {
}
