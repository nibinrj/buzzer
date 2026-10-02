package dev.nibin.buzzer.scoring;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The whole app with real PostgreSQL 16, Redis 7 and Redpanda in containers; REST tests use MockMvc with
 * spring-security-test's jwt(). Every class using exactly this annotation shares ONE cached context (one database,
 * one Redis, one broker, one running consumer), so each test uses its own session id. CapturedSpans collects the
 * spans the app finishes, for the tracing test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainer.class, RedpandaTestcontainer.class, CapturedSpans.class})
public @interface ScoringIntegrationTest {
}
