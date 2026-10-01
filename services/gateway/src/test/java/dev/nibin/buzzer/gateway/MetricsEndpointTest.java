package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /actuator/prometheus is served on the management port only (management.server.port), never on the public one,
 * and carries the gateway's per-route timings with fixed histogram buckets.
 */
class MetricsEndpointTest extends GatewayTestSupport {

    @LocalManagementPort
    private int managementPort;

    @Test
    void thePublicPortDoesNotServeMetricsEvenToALoggedInUserAndForwardsNothing() {
        String token = validToken(UUID.randomUUID(), List.of("PLAYER"));

        client.get().uri("/actuator/prometheus").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isNotFound();

        assertNothingForwarded();
    }

    @Test
    void theManagementPortServesRouteTimingsInFixedBucketsTaggedWithTheApplication() {
        String token = validToken(UUID.randomUUID(), List.of("HOST"));
        String sessionPath = "/api/sessions/" + UUID.randomUUID() + "/state";
        client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
        client.get().uri(sessionPath).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();

        String metrics = scrape();

        assertThat(metrics).containsPattern(
                "spring_cloud_gateway_requests_seconds_bucket\\{[^}]*application=\"gateway\"[^}]*routeId=\"quiz\"[^}]*"
                        + "le=\"0\\.1\"[^}]*}");
        assertThat(metrics).containsPattern(
                "http_server_requests_seconds_bucket\\{[^}]*application=\"gateway\"[^}]*le=\"0\\.1\"[^}]*}");
        // 10 SLO buckets + "+Inf" per series, not Micrometer's ~70 default percentile buckets.
        assertThat(metrics).doesNotContain("le=\"0.001\"");
        // No id becomes a label: one series per session would be unbounded.
        assertThat(metrics).doesNotContain(sessionPath);
    }

    private String scrape() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build()
                .get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }
}
