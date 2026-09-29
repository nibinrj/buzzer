package dev.nibin.buzzer.scoring;

import org.junit.jupiter.api.Test;

/** The whole app starts against real Postgres (Flyway migrates it), Redis and a real broker. */
@ScoringIntegrationTest
class ScoringServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
