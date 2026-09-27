package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.EmailAlreadyRegisteredException;
import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Implements the domain's UserRepository port with Spring Data JPA. */
@Component
class JpaUserRepositoryAdapter implements UserRepository {

    // Unique index from V1__users.sql.
    private static final String EMAIL_UNIQUE_INDEX = "users_email_lower_uk";

    private final UserJpaRepository jpaRepository;

    JpaUserRepositoryAdapter(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public void add(User user) {
        try {
            // Flush now so a unique-index violation surfaces here, not later at commit.
            jpaRepository.saveAndFlush(UserMapper.toEntity(user));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations can both pass the existsByEmail check; the index decides.
            if (violatesEmailIndex(e)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw e;
        }
    }

    private static boolean violatesEmailIndex(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return EMAIL_UNIQUE_INDEX.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
