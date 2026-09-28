package dev.nibin.buzzer.session;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Real RS256 tokens for WebSocket tests, signed with a key pair generated per test run (never stored anywhere).
 * QuizServiceStub serves the public half as the JWKS, so session-service verifies these exactly like identity's
 * tokens: signature, issuer, expiry, roles. Built with Nimbus, which Spring Security already brings.
 */
public final class TestTokens {

    public static final String JWKS_PATH = "/.well-known/jwks.json";

    private static final RSAKey KEY = generateKey();

    private TestTokens() {
    }

    /** The JWKS document with the public key only. */
    public static String jwks() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    public static String host(UUID userId) {
        return sign(new JWTClaimsSet.Builder().subject(userId.toString()).claim("roles", List.of("HOST")));
    }

    public static String guest(UUID userId, String nickname) {
        return sign(new JWTClaimsSet.Builder().subject(userId.toString()).claim("roles", List.of("PLAYER"))
                .claim("nickname", nickname));
    }

    private static String sign(JWTClaimsSet.Builder claims) {
        Instant now = Instant.now();
        claims.issuer("buzzer-identity").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-" + UUID.randomUUID()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
