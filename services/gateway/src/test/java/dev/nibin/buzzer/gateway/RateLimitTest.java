package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Token buckets in Redis: BURST requests pass, the next is 429, and the bucket belongs to the right caller. */
class RateLimitTest extends GatewayTestSupport {

    private static final String LOGIN = "/api/auth/login";

    @Test
    void identityRoutesAreLimitedPerClientIpWithA429Problem() {
        for (int i = 0; i < BURST; i++) {
            client.post().uri(LOGIN).exchange().expectStatus().isOk();
        }

        client.post().uri(LOGIN).exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.title").isEqualTo("Too Many Requests");

        assertThat(IDENTITY.findAll(postRequestedFor(urlEqualTo(LOGIN)))).hasSize(BURST);
    }

    @Test
    void aSpoofedXForwardedForDoesNotBuyAFreshBucket() {
        // With no trusted proxy (trusted-proxies=0) the header is the client's own claim, so it is ignored.
        for (int i = 0; i < BURST; i++) {
            client.post().uri(LOGIN).header("X-Forwarded-For", "203.0.113." + i).exchange()
                    .expectStatus().isOk();
        }

        client.post().uri(LOGIN).header("X-Forwarded-For", "203.0.113.99").exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void eachUserHasTheirOwnBucket() {
        String alice = validToken(UUID.randomUUID(), List.of("HOST"));
        String bob = validToken(UUID.randomUUID(), List.of("HOST"));
        for (int i = 0; i < BURST; i++) {
            client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(alice)).exchange()
                    .expectStatus().isOk();
        }

        client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(alice)).exchange()
                .expectStatus().isEqualTo(429);
        // Same IP, different user: not affected by Alice's empty bucket.
        client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(bob)).exchange()
                .expectStatus().isOk();
    }
}
