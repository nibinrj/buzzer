package dev.nibin.buzzer.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

/**
 * Loads the RS256 key pair from the configured PEM files and builds the JwtEncoder.
 * Keys are never generated here: missing or mismatched files stop the service at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** The key pair as a JWK. Its kid is the RFC 7638 thumbprint, so a new key gets a new kid. */
    @Bean
    RSAKey signingKey(JwtProperties properties) {
        // PKCS#8 "BEGIN PRIVATE KEY" and X.509 "BEGIN PUBLIC KEY": the formats `tasks.ps1 keys` writes.
        RSAPrivateKey privateKey = read(properties.privateKeyLocation(), RsaKeyConverters.pkcs8(),
                "private-key-location");
        RSAPublicKey publicKey = read(properties.publicKeyLocation(), RsaKeyConverters.x509(),
                "public-key-location");

        // An RSA key pair shares its modulus. A mismatch would sign tokens no one can verify.
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalStateException("identity.jwt public and private keys do not belong to the same key pair");
        }

        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not compute the JWT key id", e);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    private static <K> K read(Resource location, Converter<InputStream, K> converter, String property) {
        if (!location.exists()) {
            throw new IllegalStateException("identity.jwt." + property + " not found: " + location.getDescription()
                    + ". Check IDENTITY_JWT_PRIVATE_KEY_LOCATION / IDENTITY_JWT_PUBLIC_KEY_LOCATION (see .env.example).");
        }
        try (InputStream in = location.getInputStream()) {
            return Objects.requireNonNull(converter.convert(in), "identity.jwt." + property);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read identity.jwt." + property, e);
        }
    }
}
