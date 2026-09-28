package dev.nibin.buzzer.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Which requests reach which service, and which never leave the gateway. */
class GatewayRoutingTest extends GatewayTestSupport {

    private static final UUID HOST = UUID.randomUUID();

    static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                Arguments.of("/api/quizzes", QUIZ),
                Arguments.of("/api/quizzes/42/questions", QUIZ),
                Arguments.of("/api/sessions/abc", SESSION),
                // Only the exact handshake path /ws is public; anything below it still needs a token.
                Arguments.of("/ws/info", SESSION),
                Arguments.of("/api/results/abc", SCORING));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRoutes")
    void routesAnAuthenticatedRequestToItsServiceWithPathAndTokenUnchanged(String path, WireMockServer service) {
        String token = validToken(HOST, List.of("HOST"));

        client.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isOk();

        LoggedRequest forwarded = onlyRequestTo(service, path);
        assertThat(forwarded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
    }

    static Stream<Arguments> publicRoutes() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/auth/login", IDENTITY),
                Arguments.of(HttpMethod.POST, "/api/auth/guest", IDENTITY),
                Arguments.of(HttpMethod.GET, JWKS_PATH, IDENTITY),
                Arguments.of(HttpMethod.POST, "/api/sessions/join", SESSION),
                // A plain GET here; WebSocketProxyTest does the real upgrade.
                Arguments.of(HttpMethod.GET, "/ws", SESSION));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("publicRoutes")
    void publicPathsNeedNoToken(HttpMethod method, String path, WireMockServer service) {
        client.method(method).uri(path).exchange()
                .expectStatus().isOk();

        // JWKS_PATH is also fetched by the gateway itself, but only once a token needs checking. None here.
        onlyRequestTo(service, path);
    }

    @Test
    void anExpiredTokenDoesNotBlockAPublicPath() {
        // A client refreshing because its access token expired usually still sends that token.
        String expired = sign(IDENTITY_KEY, ISSUER, HOST, List.of("HOST"), Instant.now().minus(Duration.ofMinutes(5)));

        client.post().uri("/api/auth/refresh").headers(h -> h.setBearerAuth(expired)).exchange()
                .expectStatus().isOk();

        LoggedRequest forwarded = onlyRequestTo(IDENTITY, "/api/auth/refresh");
        assertThat(forwarded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + expired);
    }

    @Test
    void aProtectedPathWithoutATokenIs401AndNeverForwarded() {
        client.get().uri("/api/quizzes").exchange()
                .expectStatus().isUnauthorized();

        assertNothingForwarded();
    }

    static Stream<Arguments> rejectedTokens() {
        Instant later = Instant.now().plus(Duration.ofMinutes(15));
        return Stream.of(
                // More than the default 60 s clock skew in the past.
                Arguments.of("expired",
                        sign(IDENTITY_KEY, ISSUER, HOST, List.of("HOST"), Instant.now().minus(Duration.ofMinutes(5)))),
                Arguments.of("another issuer", sign(IDENTITY_KEY, "someone-else", HOST, List.of("HOST"), later)),
                // Unknown kid: the gateway refetches the JWKS, still finds no such key, and rejects it.
                Arguments.of("signed with another key", sign(generateKey(), ISSUER, HOST, List.of("HOST"), later)),
                Arguments.of("not a JWT", "not-a-jwt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    void anInvalidTokenIs401AndNeverForwarded(String why, String token) {
        client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isUnauthorized();

        assertNothingForwarded();
    }

    @ParameterizedTest(name = "with token: {0}")
    @ValueSource(booleans = {true, false})
    void internalPathsAre404WithOrWithoutAToken(boolean withToken) {
        String token = validToken(HOST, List.of("HOST"));

        client.get().uri("/internal/quizzes/" + UUID.randomUUID() + "/snapshot")
                .headers(h -> {
                    if (withToken) {
                        h.setBearerAuth(token);
                    }
                })
                .exchange()
                .expectStatus().isNotFound();

        assertNothingForwarded();
    }

    @Test
    void anUnroutedPathIs404() {
        client.get().uri("/api/nothing-here").headers(h -> h.setBearerAuth(validToken(HOST, List.of("HOST"))))
                .exchange()
                .expectStatus().isNotFound();

        assertNothingForwarded();
    }

    @Test
    void pathTraversalOutOfARouteIsRejectedBeforeRouting() {
        // Matches /api/quizzes/** as written, but a downstream server would normalize it to /internal/...
        // Spring Security's firewall rejects paths that are not normalized.
        client.get().uri("/api/quizzes/../../internal/quizzes")
                .headers(h -> h.setBearerAuth(validToken(HOST, List.of("HOST"))))
                .exchange()
                .expectStatus().isBadRequest();

        assertNothingForwarded();
    }
}
