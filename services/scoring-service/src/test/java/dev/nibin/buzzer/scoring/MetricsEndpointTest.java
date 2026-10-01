package dev.nibin.buzzer.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /actuator/prometheus is served on the management port only (management.server.port), never on the service port,
 * with request timings in fixed histogram buckets and the application tag.
 */
@ScoringIntegrationTest
class MetricsEndpointTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Test
    void theServicePortDoesNotServeMetrics() throws Exception {
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void theManagementPortServesRequestTimingsInFixedBucketsTaggedWithTheApplication() throws Exception {
        String id = UUID.randomUUID().toString();
        get(port, "/api/results/sessions/" + id + "/leaderboard"); // any answer: every request is timed

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).containsPattern(
                "http_server_requests_seconds_bucket\\{[^}]*application=\"scoring-service\"[^}]*le=\"0\\.1\"[^}]*}");
        // 10 SLO buckets + "+Inf" per series, not Micrometer's ~70 default percentile buckets.
        assertThat(scrape.body()).doesNotContain("le=\"0.001\"");
        // No id becomes a label (uri is the route template or UNKNOWN): one series per id would be unbounded.
        assertThat(scrape.body()).doesNotContain(id);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
