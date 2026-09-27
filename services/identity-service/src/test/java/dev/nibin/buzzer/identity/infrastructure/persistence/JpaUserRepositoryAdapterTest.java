package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.EmailAlreadyRegisteredException;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The adapter against a real PostgreSQL 16, including the unique-index translation. */
@PersistenceTest
class JpaUserRepositoryAdapterTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

    @Autowired
    private JpaUserRepositoryAdapter adapter;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void addedUserCanBeFoundByEmail() {
        User user = User.register("host@test.dev", HASH, Set.of(Role.HOST), NOW);

        adapter.add(user);
        entityManager.clear();

        assertThat(adapter.existsByEmail("host@test.dev")).isTrue();
        User found = adapter.findByEmail("host@test.dev").orElseThrow();
        assertThat(found.id()).isEqualTo(user.id());
        assertThat(found.passwordHash()).isEqualTo(HASH);
        assertThat(found.roles()).containsExactly(Role.HOST);
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(adapter.findById(user.id())).contains(found);
    }

    @Test
    void unknownIdIsNotFound() {
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void unknownEmailIsNotFound() {
        assertThat(adapter.existsByEmail("nobody@test.dev")).isFalse();
        assertThat(adapter.findByEmail("nobody@test.dev")).isEmpty();
    }

    @Test
    void secondUserWithSameEmailIsRejectedByTheUniqueIndex() {
        // Simulates the race RegisterUser can't prevent: both passed existsByEmail, both insert.
        adapter.add(User.register("race@test.dev", HASH, Set.of(Role.HOST), NOW));
        User second = User.register("RACE@test.dev", HASH, Set.of(Role.HOST), NOW);

        assertThatThrownBy(() -> adapter.add(second)).isInstanceOf(EmailAlreadyRegisteredException.class);
    }
}
