package dev.nibin.buzzer.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

    @Test
    void registerAssignsIdAndKeepsFields() {
        User user = User.register("host@test.dev", HASH, Set.of(Role.HOST), NOW);

        assertThat(user.id()).isNotNull();
        assertThat(user.email()).isEqualTo("host@test.dev");
        assertThat(user.passwordHash()).isEqualTo(HASH);
        assertThat(user.roles()).containsExactly(Role.HOST);
        assertThat(user.createdAt()).isEqualTo(NOW);
    }

    @Test
    void registerGivesEachUserADifferentId() {
        User first = User.register("a@test.dev", HASH, Set.of(Role.PLAYER), NOW);
        User second = User.register("b@test.dev", HASH, Set.of(Role.PLAYER), NOW);

        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void emailIsTrimmedAndLowerCased() {
        User user = User.register("  Host@Test.DEV ", HASH, Set.of(Role.HOST), NOW);

        assertThat(user.email()).isEqualTo("host@test.dev");
    }

    @Test
    void rejectsBlankEmail() {
        assertThatThrownBy(() -> User.register("  ", HASH, Set.of(Role.HOST), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsBlankPasswordHash() {
        assertThatThrownBy(() -> User.register("host@test.dev", "", Set.of(Role.HOST), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordHash");
    }

    @Test
    void rejectsEmptyRoles() {
        assertThatThrownBy(() -> User.register("host@test.dev", HASH, Set.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void rejectsNulls() {
        UUID id = UUID.randomUUID();
        Set<Role> roles = Set.of(Role.HOST);

        assertThatThrownBy(() -> new User(null, "a@test.dev", HASH, roles, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new User(id, null, HASH, roles, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new User(id, "a@test.dev", null, roles, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new User(id, "a@test.dev", HASH, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new User(id, "a@test.dev", HASH, roles, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rolesAreACopyAndCannotBeModified() {
        Set<Role> roles = new HashSet<>(EnumSet.of(Role.HOST));
        User user = User.register("host@test.dev", HASH, roles, NOW);

        roles.add(Role.PLAYER);

        assertThat(user.roles()).containsExactly(Role.HOST);
        assertThatThrownBy(() -> user.roles().add(Role.PLAYER)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equalityIsById() {
        UUID id = UUID.randomUUID();
        User a = new User(id, "a@test.dev", HASH, Set.of(Role.HOST), NOW);
        User b = new User(id, "other@test.dev", "other-hash", Set.of(Role.PLAYER), NOW.plusSeconds(60));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(User.register("a@test.dev", HASH, Set.of(Role.HOST), NOW));
    }

    @Test
    void toStringDoesNotLeakPasswordHash() {
        User user = User.register("host@test.dev", HASH, Set.of(Role.HOST), NOW);

        assertThat(user.toString()).doesNotContain(HASH);
    }
}
