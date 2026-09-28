package dev.nibin.buzzer.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real gateway on a random port, in front of four WireMock servers standing in for the services. One server
 * per service, so a test can tell WHICH service a request reached. The identity stand-in also serves a JWKS
 * with a test key, so tokens are verified end to end exactly as in production (fetch keys, check signature).
 *
 * <p>The WireMock servers are started once per JVM and never restarted: Spring caches the application context
 * across test classes, and that context holds their ports.
 *
 * <p>Rate limiting runs against a real Redis (container), emptied before each test so every test starts with full
 * token buckets. Test buckets are small ({@link #BURST}) so a 429 takes only a few requests.
 *
 * <p>Each request costs {@link #TOKENS_PER_REQUEST} tokens and 1 token comes back per second, so a whole request
 * refills only after a minute. With a cost of 1, a burst that happened to straddle a second boundary got a free
 * token back (the limiter's Lua script counts time in whole seconds of Redis TIME), and the tests failed at random.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.per-ip.replenish-rate=1",
        "gateway.rate-limit.per-ip.requested-tokens=" + GatewayTestSupport.TOKENS_PER_REQUEST,
        "gateway.rate-limit.per-ip.burst-capacity=" + GatewayTestSupport.BURST * GatewayTestSupport.TOKENS_PER_REQUEST,
        "gateway.rate-limit.per-user.replenish-rate=1",
        "gateway.rate-limit.per-user.requested-tokens=" + GatewayTestSupport.TOKENS_PER_REQUEST,
        "gateway.rate-limit.per-user.burst-capacity=" + GatewayTestSupport.BURST * GatewayTestSupport.TOKENS_PER_REQUEST})
@Import(RedisTestcontainer.class)
public abstract class GatewayTestSupport {

    protected static final String ISSUER = "buzzer-identity";
    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    /** Requests allowed at once per bucket in tests. */
    protected static final int BURST = 3;

    /** What one request costs in tests: at 1 token per second, a spent request comes back after a minute. */
    protected static final int TOKENS_PER_REQUEST = 60;

    protected static final WireMockServer IDENTITY = started();
    protected static final WireMockServer QUIZ = started();
    protected static final WireMockServer SESSION = started();
    protected static final WireMockServer SCORING = started();

    /** identity-service's signing key, as published in the stubbed JWKS. */
    protected static final RSAKey IDENTITY_KEY = generateKey();

    @LocalServerPort
    private int port;

    @Autowired
    private ReactiveRedisConnectionFactory redis;

    protected WebTestClient client;

    @DynamicPropertySource
    static void pointAtStandIns(DynamicPropertyRegistry registry) {
        // The placeholders application.yml reads, as the environment would set them.
        registry.add("IDENTITY_URI", IDENTITY::baseUrl);
        registry.add("QUIZ_URI", QUIZ::baseUrl);
        registry.add("SESSION_URI", SESSION::baseUrl);
        registry.add("SCORING_URI", SCORING::baseUrl);
        registry.add("IDENTITY_JWKS_URI", () -> IDENTITY.baseUrl() + JWKS_PATH);
    }

    @BeforeEach
    void resetStandIns() {
        try (ReactiveRedisConnection connection = redis.getReactiveConnection()) {
            connection.serverCommands().flushAll().block();
        }
        for (WireMockServer service : List.of(IDENTITY, QUIZ, SESSION, SCORING)) {
            service.resetAll();
            service.stubFor(any(anyUrl()).willReturn(ok()));
        }
        // Added after the catch-all, so it wins for this URL (WireMock prefers the most recent match).
        IDENTITY.stubFor(get(urlEqualTo(JWKS_PATH))
                .willReturn(okJson(new JWKSet(IDENTITY_KEY.toPublicJWK()).toString())));
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** A valid access token shaped like identity-service's: RS256 with kid; iss, sub, iat, exp, roles. */
    protected static String validToken(UUID subject, List<String> roles) {
        return sign(IDENTITY_KEY, ISSUER, subject, roles, Instant.now().plus(Duration.ofMinutes(15)));
    }

    protected static String sign(RSAKey key, String issuer, UUID subject, List<String> roles, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject.toString())
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15)))
                .expiresAt(expiresAt)
                .claim("roles", roles)
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    protected static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The single request a stand-in received at this path. */
    protected static LoggedRequest onlyRequestTo(WireMockServer service, String path) {
        List<LoggedRequest> requests = service.findAll(anyRequestedFor(urlEqualTo(path)));
        assertThat(requests).as("requests to %s", path).hasSize(1);
        return requests.getFirst();
    }

    /** Nothing was forwarded anywhere. The identity stand-in may still have been asked for its JWKS. */
    protected static void assertNothingForwarded() {
        for (WireMockServer service : List.of(QUIZ, SESSION, SCORING)) {
            assertThat(service.getAllServeEvents()).isEmpty();
        }
        assertThat(IDENTITY.getAllServeEvents())
                .allSatisfy(event -> assertThat(event.getRequest().getUrl()).isEqualTo(JWKS_PATH));
    }

    private static WireMockServer started() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        return server;
    }
}
