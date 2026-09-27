package dev.nibin.buzzer.identity.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for storing users. The domain and use cases depend on this interface;
 * the JPA adapter in infrastructure implements it.
 * Emails passed in must already be normalized (as {@link User#email()} returns them).
 */
public interface UserRepository {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findById(UUID id);

    /**
     * Stores a new user.
     *
     * @throws EmailAlreadyRegisteredException if another user has the same email
     */
    void add(User user);
}
