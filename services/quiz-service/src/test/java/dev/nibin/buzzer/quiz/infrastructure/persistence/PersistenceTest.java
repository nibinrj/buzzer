package dev.nibin.buzzer.quiz.infrastructure.persistence;

import dev.nibin.buzzer.quiz.TestcontainersConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JPA slice against PostgreSQL 16 in a container: Flyway runs, then Hibernate validates the schema.
 * Each test runs in a transaction that is rolled back. All persistence tests share ONE cached context,
 * so don't add per-class configuration to them. Hibernate statistics let tests count SQL statements.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=warn"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaQuizRepositoryAdapter.class})
public @interface PersistenceTest {
}
