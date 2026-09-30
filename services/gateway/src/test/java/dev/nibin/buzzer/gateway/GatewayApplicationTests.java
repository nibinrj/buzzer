package dev.nibin.buzzer.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test: the full context starts, and logs the way production does. */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class GatewayApplicationTests {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplicationTests.class);

    @Test
    void contextLoads() {
    }

    /**
     * application.yml's logging.structured.format.console=ecs: every log line is one JSON object in Elastic Common
     * Schema, nested as ECS nests it, and MDC entries become top-level fields of the line.
     */
    @Test
    void logsOneEcsJsonObjectPerLineWithMdcEntriesAsFields(CapturedOutput output) {
        String marker = UUID.randomUUID().toString();
        try (var ignored = MDC.putCloseable("sessionId", "mdc-" + marker)) {
            log.info("probe {}", marker);
        }

        String line = output.getOut().lines().filter(l -> l.contains(marker)).findFirst().orElseThrow();
        JsonNode json = JsonMapper.builder().build().readTree(line);
        assertThat(json.path("@timestamp").isString()).isTrue();
        assertThat(json.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(json.path("log").path("logger").asString())
                .isEqualTo(GatewayApplicationTests.class.getName());
        assertThat(json.path("service").path("name").asString()).isEqualTo("gateway");
        assertThat(json.path("message").asString()).isEqualTo("probe " + marker);
        assertThat(json.path("sessionId").asString()).isEqualTo("mdc-" + marker);
        assertThat(json.path("ecs").path("version").isString()).isTrue();
    }
}
