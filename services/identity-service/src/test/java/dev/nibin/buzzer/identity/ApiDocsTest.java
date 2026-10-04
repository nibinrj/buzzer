package dev.nibin.buzzer.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The OpenAPI document and Swagger UI are served on the service port, without a token. */
@HttpIntegrationTest
class ApiDocsTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void theOpenApiDocumentListsTheAuthEndpoints() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"identity-service API\"", "\"/api/auth/login\"", "\"/api/auth/guest\"");
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
