package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request's Authorization header passes through the gateway, so this is where a token would leak into the
 * logs first. The gateway's own code and Spring Security log at DEBUG here, more than production (INFO) ever does,
 * and still no token value may appear in the output: not a forwarded one, not a rejected one.
 *
 * <p>Red run (2026-09-30, before application.yml pinned its logger): Spring Cloud Gateway's
 * ObservedRequestHttpHeadersFilter logged "Will instrument the HTTP request headers [..., Authorization:"Bearer ..."]".
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "logging.level.org.springframework.security=DEBUG",
        "logging.level.org.springframework.cloud.gateway=DEBUG",
        "logging.level.dev.nibin.buzzer.gateway=DEBUG"})
class LogSafetyTest extends GatewayTestSupport {

    private static final UUID USER = UUID.randomUUID();

    @Test
    void noTokenValueReachesTheLogs(CapturedOutput output) {
        String valid = validToken(USER, List.of("PLAYER"));
        Instant past = Instant.now().minus(Duration.ofMinutes(5));
        Instant future = Instant.now().plus(Duration.ofMinutes(5));
        String expired = sign(IDENTITY_KEY, ISSUER, USER, List.of("PLAYER"), past);
        String forged = sign(generateKey(), ISSUER, USER, List.of("PLAYER"), future);
        String garbage = "not-a-jwt-" + UUID.randomUUID();

        client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(valid)).exchange()
                .expectStatus().isOk();
        for (String rejected : List.of(expired, forged, garbage)) {
            client.get().uri("/api/quizzes").headers(h -> h.setBearerAuth(rejected)).exchange()
                    .expectStatus().isUnauthorized();
        }

        // The DEBUG loggers did write: this test would prove nothing against an empty output.
        assertThat(output.getOut()).contains("\"log\":{\"level\":\"DEBUG\"");
        for (String token : List.of(valid, expired, forged, garbage)) {
            // A JWT's signature (after the last dot) is unique to it; a leak shows at least that part.
            String signature = token.substring(token.lastIndexOf('.') + 1);
            assertThat(output.getAll()).doesNotContain(token).doesNotContain(signature);
        }
    }
}
