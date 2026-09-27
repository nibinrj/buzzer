package dev.nibin.buzzer.gateway.api;

import com.github.tomakehurst.wiremock.http.Fault;
import dev.nibin.buzzer.gateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/** Every error the gateway produces itself comes back as application/problem+json. (429: RateLimitTest.) */
class ProblemDetailExceptionHandlerTest extends GatewayTestSupport {

    @Test
    void noTokenIs401WithABearerChallenge() {
        expectProblem(client.get().uri("/api/quizzes").exchange(), 401)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void aBadTokenIs401WithoutSayingWhatIsWrongWithIt() {
        expectProblem(client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth("not-a-jwt")).exchange(), 401)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    }

    @Test
    void anUnroutedPathIs404() {
        expectProblem(client.get().uri("/api/nothing-here").headers(h -> h.setBearerAuth(token())).exchange(), 404);
    }

    @Test
    void anInternalPathIs404() {
        expectProblem(client.get().uri("/internal/quizzes/x").exchange(), 404);
    }

    @Test
    void aRejectedPathIs400() {
        expectProblem(client.get().uri("/api/quizzes/../../internal/quizzes").exchange(), 400);
    }

    @Test
    void aServiceDroppingTheConnectionIsA5xxThatLeaksNothing() {
        SCORING.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        String body = client.get().uri("/api/results/abc").headers(h -> h.setBearerAuth(token())).exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).doesNotContain("Exception", "localhost", "reactor", "at ");
    }

    private static String token() {
        return validToken(UUID.randomUUID(), List.of("HOST"));
    }

    private static WebTestClient.ResponseSpec expectProblem(WebTestClient.ResponseSpec response, int status) {
        response.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.title").isNotEmpty();
        return response;
    }
}
