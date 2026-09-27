package dev.nibin.buzzer.identity.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import dev.nibin.buzzer.identity.HttpIntegrationTest;
import dev.nibin.buzzer.identity.TestJwtKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The JWKS endpoint as a verifier (the gateway, later) sees it: over HTTP, parsed with Nimbus. */
@HttpIntegrationTest
class JwksControllerTest {

    // Private RSA fields (RFC 7518 6.3.2). None may ever appear in a published key set.
    private static final List<String> PRIVATE_RSA_FIELDS = List.of("d", "p", "q", "dp", "dq", "qi", "oth");

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void publishesExactlyOnePublicRsaSigningKey() throws Exception {
        ResponseEntity<String> response = client.get().uri("/.well-known/jwks.json").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=300, public");

        JWKSet jwkSet = JWKSet.parse(response.getBody());
        assertThat(jwkSet.getKeys()).hasSize(1);
        JWK key = jwkSet.getKeys().getFirst();
        assertThat(key).isInstanceOf(RSAKey.class);
        assertThat(key.isPrivate()).isFalse();
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.toRSAKey().toRSAPublicKey()).isEqualTo(TestJwtKeys.publicKey());

        // Check the raw JSON too (plain map, no JWK model in between): no private field names at all.
        @SuppressWarnings("unchecked")
        Map<String, Object> rawKey = ((List<Map<String, Object>>) JSONObjectUtils.parse(response.getBody())
                .get("keys")).getFirst();
        assertThat(rawKey).doesNotContainKeys(PRIVATE_RSA_FIELDS.toArray(String[]::new));
    }

    @Test
    void aLoginTokenVerifiesWithTheKeyWhoseKidMatchesItsHeader() throws Exception {
        String email = "jwks-" + UUID.randomUUID() + "@test.dev";
        Map<String, String> credentials = Map.of("email", email, "password", "correct horse");
        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).body(credentials)
                .retrieve().toBodilessEntity();
        String accessToken = (String) client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(credentials).retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
                }).get("accessToken");

        // What a verifier does: read kid from the token header, pick that key from the JWKS, check the signature.
        String kid = SignedJWT.parse(accessToken).getHeader().getKeyID();
        JWKSet jwkSet = JWKSet.parse(client.get().uri("/.well-known/jwks.json").retrieve().body(String.class));
        RSAKey key = jwkSet.getKeyByKeyId(kid).toRSAKey();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(accessToken);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("HOST");
    }
}
