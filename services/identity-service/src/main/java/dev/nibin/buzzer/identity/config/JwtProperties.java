package dev.nibin.buzzer.identity.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * identity.jwt.* from application.yml. The key locations are Spring resource strings, so the same
 * property takes file:D:/... on Windows and file:/run/secrets/... in a Linux container.
 * All token lifetimes live here, including the refresh token's, although that one is opaque, not a JWT.
 */
@Validated
@ConfigurationProperties("identity.jwt")
public record JwtProperties(
        @NotNull Resource privateKeyLocation,
        @NotNull Resource publicKeyLocation,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration guestTokenTtl,
        @NotNull Duration refreshTokenTtl) {
}
