package dev.nibin.buzzer.session.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** session.security.jwt.*: where to fetch identity-service's public keys, and which issuer to accept. */
@Validated
@ConfigurationProperties("session.security.jwt")
public record JwtVerificationProperties(@NotBlank String jwkSetUri, @NotBlank String issuer) {
}
