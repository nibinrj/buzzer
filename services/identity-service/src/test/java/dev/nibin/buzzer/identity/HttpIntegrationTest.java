package dev.nibin.buzzer.identity;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The whole app on a random port, with PostgreSQL 16 in a container and throwaway JWT keys.
 * Every class using exactly this annotation shares ONE cached Spring context (and one container),
 * so don't add per-class configuration (@DynamicPropertySource, @MockitoBean, ...) to them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtKeys.class})
public @interface HttpIntegrationTest {
}
