package dev.nibin.buzzer.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Emails are stored lower-cased by the domain, so always pass a normalized email. */
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    /** SELECT ... LIMIT 1: nothing is loaded. */
    boolean existsByEmail(String email);

    /** Fetches the roles in the same query (LEFT JOIN) instead of a second SELECT. */
    @EntityGraph(attributePaths = "roles")
    Optional<UserJpaEntity> findByEmail(String email);
}
