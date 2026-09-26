package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.TestcontainersConfiguration;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs Flyway V1 against a real PostgreSQL 16, then Hibernate validates the entity against it.
 * replace = NONE: keep the Testcontainers database instead of swapping in an embedded one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserJpaRepositoryTest {

    // Postgres stores microseconds; truncate so the round-trip comparison is exact.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

    // Field injection is the norm in test classes; production code uses constructor injection only.
    @Autowired
    private UserJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndReloadsUserWithRoles() {
        User user = User.register("host@test.dev", HASH, EnumSet.of(Role.HOST, Role.PLAYER), NOW);

        repository.saveAndFlush(UserMapper.toEntity(user));
        entityManager.clear(); // force a real SELECT instead of the first-level cache

        User loaded = UserMapper.toDomain(repository.findById(user.id()).orElseThrow());
        assertThat(loaded.id()).isEqualTo(user.id());
        assertThat(loaded.email()).isEqualTo("host@test.dev");
        assertThat(loaded.passwordHash()).isEqualTo(HASH);
        assertThat(loaded.roles()).containsExactlyInAnyOrder(Role.HOST, Role.PLAYER);
        assertThat(loaded.createdAt()).isEqualTo(NOW);
    }

    @Test
    void findsByEmail() {
        User user = User.register("player@test.dev", HASH, Set.of(Role.PLAYER), NOW);
        repository.saveAndFlush(UserMapper.toEntity(user));
        entityManager.clear();

        assertThat(repository.findByEmail("player@test.dev")).get()
                .extracting(UserJpaEntity::getId).isEqualTo(user.id());
        assertThat(repository.findByEmail("nobody@test.dev")).isEmpty();
    }

    @Test
    void uniqueIndexRejectsSameEmailInDifferentCase() {
        User first = User.register("dup@test.dev", HASH, Set.of(Role.HOST), NOW);
        repository.saveAndFlush(UserMapper.toEntity(first));

        // Built directly as an entity to bypass the domain's lower-casing: the database must still reject it.
        UserJpaEntity sameEmailUpperCase = new UserJpaEntity(
                UUID.randomUUID(), "DUP@Test.dev", HASH, Set.of(Role.HOST), NOW);

        assertThatThrownBy(() -> repository.saveAndFlush(sameEmailUpperCase))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
