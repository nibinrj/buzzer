package dev.nibin.buzzer.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The whole app starts against real Postgres (Flyway migrates it) and a real broker. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RedpandaTestcontainer.class})
class ScoringServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
