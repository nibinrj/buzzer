package dev.nibin.buzzer.quiz;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The whole app (real security filter chain, real PostgreSQL 16 in a container) driven through MockMvc.
 * Requests authenticate with spring-security-test's jwt() post-processor, so no identity-service and no
 * real tokens are needed. Every class using exactly this annotation shares ONE cached context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface ApiIntegrationTest {
}
