package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.TestcontainersConfiguration;
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
 * Each test runs in a transaction that is rolled back. All persistence tests share ONE cached context.
 * <p>
 * replace = NONE keeps the container instead of an embedded database. The adapters are plain
 * components, which the JPA slice doesn't scan, so they are imported. Hibernate statistics let tests
 * count SQL statements; the per-session statistics log line is silenced.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=warn"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaUserRepositoryAdapter.class, JpaRefreshTokenRepositoryAdapter.class})
public @interface PersistenceTest {
}
