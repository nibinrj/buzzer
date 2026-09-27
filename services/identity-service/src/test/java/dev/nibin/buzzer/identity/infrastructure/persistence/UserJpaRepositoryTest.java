package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway V1 against a real PostgreSQL 16, then Hibernate validates the entity against it. */
@PersistenceTest
class UserJpaRepositoryTest {

    // Postgres stores microseconds; truncate so the round-trip comparison is exact.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

    // Field injection is the norm in test classes; production code uses constructor injection only.
    @Autowired
    private UserJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

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
    void savingANewUserInsertsWithoutASelectFirst() {
        UserJpaEntity entity = UserMapper.toEntity(User.register("new@test.dev", HASH, Set.of(Role.HOST), NOW));
        assertThat(entity.isNew()).isTrue();

        UserJpaEntity saved = repository.saveAndFlush(entity);

        // persist() keeps the instance; merge() would have returned a managed copy after a SELECT.
        assertThat(saved).isSameAs(entity);
        assertThat(entity.isNew()).isFalse();
        // Exactly two statements: INSERT users, INSERT user_roles. No SELECT.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void loadedUserIsNotNew() {
        User user = User.register("loaded@test.dev", HASH, Set.of(Role.HOST), NOW);
        repository.saveAndFlush(UserMapper.toEntity(user));
        entityManager.clear();

        assertThat(repository.findById(user.id()).orElseThrow().isNew()).isFalse();
    }

    @Test
    void findsByEmailWithRolesInOneQuery() {
        User user = User.register("player@test.dev", HASH, EnumSet.of(Role.PLAYER, Role.HOST), NOW);
        repository.saveAndFlush(UserMapper.toEntity(user));
        entityManager.clear();
        statistics.clear();

        UserJpaEntity found = repository.findByEmail("player@test.dev").orElseThrow();

        assertThat(found.getId()).isEqualTo(user.id());
        assertThat(found.getRoles()).containsExactlyInAnyOrder(Role.PLAYER, Role.HOST);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(repository.findByEmail("nobody@test.dev")).isEmpty();
    }

    @Test
    void existsByEmail() {
        repository.saveAndFlush(UserMapper.toEntity(User.register("exists@test.dev", HASH, Set.of(Role.HOST), NOW)));

        assertThat(repository.existsByEmail("exists@test.dev")).isTrue();
        assertThat(repository.existsByEmail("nobody@test.dev")).isFalse();
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
