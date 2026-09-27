package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CORS is answered at the edge: preflights never need a token and never reach a service. */
class CorsTest extends GatewayTestSupport {

    private static final String TEST_CLIENT = "http://localhost:5173";

    @Test
    void aPreflightFromLocalhostIsAnsweredByTheGatewayWithoutAToken() {
        client.options().uri("/api/quizzes")
                .header(HttpHeaders.ORIGIN, TEST_CLIENT)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TEST_CLIENT)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, methods -> assertThat(methods).contains("POST"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        headers -> assertThat(headers.toLowerCase()).contains("authorization", "content-type"));

        assertNothingForwarded();
    }

    @Test
    void aPreflightFromAnotherOriginIsRefused() {
        client.options().uri("/api/quizzes")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

        assertNothingForwarded();
    }

    @Test
    void aRealRequestFromAnAllowedOriginCarriesTheAllowOriginHeaderOnce() {
        client.get().uri("/api/quizzes")
                .header(HttpHeaders.ORIGIN, TEST_CLIENT)
                .headers(h -> h.setBearerAuth(validToken(UUID.randomUUID(), List.of("HOST"))))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().values(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        values -> assertThat(values).containsExactly(TEST_CLIENT));
    }
}
