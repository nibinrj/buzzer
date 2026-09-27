package dev.nibin.buzzer.identity.api;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the public signing key as a JWK Set (RFC 7517). The gateway and other services fetch this
 * to verify tokens, matching each token's "kid" header to a key here.
 */
@RestController
public class JwksController {

    private final Map<String, Object> jwkSet;

    public JwksController(RSAKey signingKey) {
        // toPublicJWK() drops the private parts (d, p, q, ...). The key never changes while running,
        // so the JSON is built once.
        this.jwkSet = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        // Public data; verifiers may cache it briefly. A rotation publishes the new key before using it.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(jwkSet);
    }
}
