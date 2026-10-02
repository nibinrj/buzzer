package dev.nibin.buzzer.gateway;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's part of a trace: it continues a trace the client sent, or starts one, and forwards it to the service
 * in a W3C traceparent header, with its own span as the parent. Nothing in our code does this: the gateway's HTTP
 * server and its proxying client are observed by Spring, and the OpenTelemetry bridge writes the header.
 */
class TracePropagationTest extends GatewayTestSupport {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CLIENT_SPAN_ID = "00f067aa0ba902b7";

    @Test
    void aTraceTheClientStartedIsContinuedToTheServiceWithTheGatewaysSpanAsParent() {
        client.get().uri("/api/quizzes")
                .headers(h -> h.setBearerAuth(validToken(UUID.randomUUID(), List.of("HOST"))))
                .header("traceparent", "00-" + TRACE_ID + "-" + CLIENT_SPAN_ID + "-01")
                .exchange().expectStatus().isOk();

        String[] forwarded = traceparentForwardedTo("/api/quizzes");
        assertThat(forwarded[1]).as("same trace").isEqualTo(TRACE_ID);
        assertThat(forwarded[2]).as("the gateway's span, not the client's").isNotEqualTo(CLIENT_SPAN_ID);
    }

    @Test
    void withoutATraceFromTheClientTheGatewayStartsOne() {
        client.get().uri("/api/quizzes")
                .headers(h -> h.setBearerAuth(validToken(UUID.randomUUID(), List.of("HOST"))))
                .exchange().expectStatus().isOk();

        assertThat(traceparentForwardedTo("/api/quizzes")[1]).matches("[0-9a-f]{32}");
    }

    /** The forwarded request's traceparent, split into version, trace id, parent span id and flags. */
    private static String[] traceparentForwardedTo(String path) {
        LoggedRequest forwarded = onlyRequestTo(QUIZ, path);
        String traceparent = forwarded.getHeader("traceparent");
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        return traceparent.split("-");
    }
}
