package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a Kubernetes probe calls, without a token: the liveness/readiness groups on the management port, and the same
 * groups as /livez and /readyz on the public port (probes.add-additional-paths), answered by the gateway itself,
 * never forwarded to a service. Status only. Before this, the groups answered 401: the chain permitted only the
 * exact /actuator/health.
 */
class HealthProbeTest extends GatewayTestSupport {

    @LocalManagementPort
    private int managementPort;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void theManagementPortAnswersProbesWithoutAToken(String path) {
        WebTestClient management = WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
        assertUpWithoutDetails(management, path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/livez", "/readyz"})
    void thePublicPortAnswersProbesItselfWithoutAToken(String path) {
        assertUpWithoutDetails(client, path);

        assertNothingForwarded();
    }

    @Test
    void thePublicPortStillServesNoActuatorEndpoint() {
        client.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
    }

    private static void assertUpWithoutDetails(WebTestClient webClient, String path) {
        String body = webClient.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"status\":\"UP\"").doesNotContain("components");
    }
}
