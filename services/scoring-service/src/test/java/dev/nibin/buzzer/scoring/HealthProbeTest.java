package dev.nibin.buzzer.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a Kubernetes probe calls, without a token: the liveness/readiness groups on the management port, and the same
 * groups as /livez and /readyz on the service port (probes.add-additional-paths). Status only, never component
 * details. Before this, the groups answered 401: the security chain permitted only the exact /actuator/health.
 */
@ScoringIntegrationTest
class HealthProbeTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void theManagementPortAnswersProbesWithoutAToken(String path) throws Exception {
        assertUpWithoutDetails(get(managementPort, path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/livez", "/readyz"})
    void theServicePortAnswersProbesWithoutAToken(String path) throws Exception {
        assertUpWithoutDetails(get(port, path));
    }

    @Test
    void theServicePortStillServesNoActuatorEndpoint() throws Exception {
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
    }

    private static void assertUpWithoutDetails(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
