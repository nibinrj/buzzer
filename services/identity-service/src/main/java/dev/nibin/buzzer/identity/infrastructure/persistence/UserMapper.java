package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.User;

/** Converts between the domain User and its JPA representation, keeping JPA out of the domain. */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(user.id(), user.email(), user.passwordHash(), user.roles(), user.createdAt());
    }

    public static User toDomain(UserJpaEntity entity) {
        return new User(entity.getId(), entity.getEmail(), entity.getPasswordHash(), entity.getRoles(),
                entity.getCreatedAt());
    }
}
