package dev.nibin.buzzer.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * gateway.security.jwt.*: where to fetch identity-service's public keys, and which issuer to accept.
 * Checked in the constructor (the gateway has no Bean Validation on its classpath), so a missing value
 * fails startup instead of the first request.
 */
@ConfigurationProperties("gateway.security.jwt")
public record JwtVerificationProperties(String jwkSetUri, String issuer) {

    public JwtVerificationProperties {
        Assert.hasText(jwkSetUri, "gateway.security.jwt.jwk-set-uri must be set");
        Assert.hasText(issuer, "gateway.security.jwt.issuer must be set");
    }
}
