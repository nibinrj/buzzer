package dev.nibin.buzzer.session;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The whole app (real security filter chain, real PostgreSQL 16, Redis 7 and Redpanda in containers, WireMock as
 * quiz-service) on a random port. REST tests drive it through MockMvc and authenticate with spring-security-test's
 * jwt() post-processor; WebSocket tests connect to the real port with real signed tokens (TestTokens).
 * Every class using exactly this annotation shares ONE cached context, and so one database, one Redis, one broker,
 * one WireMock and one circuit breaker: tests must reset what they use.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainer.class, RedpandaTestcontainer.class,
        QuizServiceStub.class})
public @interface ApiIntegrationTest {
}
