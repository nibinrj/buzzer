package dev.nibin.buzzer.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    /** Emails are stored lower-cased by the domain, so pass a normalized email. */
    Optional<UserJpaEntity> findByEmail(String email);
}
