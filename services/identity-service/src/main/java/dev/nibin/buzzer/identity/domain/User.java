package dev.nibin.buzzer.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A registered account. Pure domain object: no Spring, JPA or Jackson.
 * Identity is the id, so equals/hashCode compare the id only.
 */
public final class User {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final Set<Role> roles;
    private final Instant createdAt;

    /** Rehydrates an existing user (e.g. loaded from the database). */
    public User(UUID id, String email, String passwordHash, Set<Role> roles, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = normalizeEmail(email);
        this.passwordHash = requireNonBlank(passwordHash, "passwordHash");
        Objects.requireNonNull(roles, "roles");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        this.roles = Set.copyOf(roles);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Creates a brand-new user with a fresh id. */
    public static User register(String email, String passwordHash, Set<Role> roles, Instant now) {
        return new User(UUID.randomUUID(), email, passwordHash, roles, now);
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Set<Role> roles() {
        return roles;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** The one email normalization rule; use it before looking a user up by email. */
    public static String normalizeEmail(String email) {
        return requireNonBlank(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof User user && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // Never include passwordHash in logs.
        return "User[id=" + id + ", email=" + email + ", roles=" + roles + "]";
    }
}
