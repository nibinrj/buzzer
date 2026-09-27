package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.RefreshToken;

/** Converts between the domain RefreshToken and its JPA representation. */
public final class RefreshTokenMapper {

    private RefreshTokenMapper() {
    }

    public static RefreshTokenJpaEntity toEntity(RefreshToken token) {
        return new RefreshTokenJpaEntity(token.id(), token.userId(), token.familyId(), token.tokenHash(),
                token.createdAt(), token.expiresAt(), token.usedAt().orElse(null), token.revokedAt().orElse(null));
    }

    public static RefreshToken toDomain(RefreshTokenJpaEntity entity) {
        return new RefreshToken(entity.getId(), entity.getUserId(), entity.getFamilyId(), entity.getTokenHash(),
                entity.getCreatedAt(), entity.getExpiresAt(), entity.getUsedAt(), entity.getRevokedAt());
    }
}
