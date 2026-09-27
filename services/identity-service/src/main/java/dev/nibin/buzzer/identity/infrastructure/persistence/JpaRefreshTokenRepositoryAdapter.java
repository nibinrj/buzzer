package dev.nibin.buzzer.identity.infrastructure.persistence;

import dev.nibin.buzzer.identity.domain.RefreshToken;
import dev.nibin.buzzer.identity.domain.RefreshTokenRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Implements the domain's RefreshTokenRepository port with Spring Data JPA. */
@Component
class JpaRefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    JpaRefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void add(RefreshToken token) {
        // Flush now so a constraint violation surfaces here rather than at commit.
        jpaRepository.saveAndFlush(RefreshTokenMapper.toEntity(token));
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(RefreshTokenMapper::toDomain);
    }

    @Override
    public boolean markUsed(UUID tokenId, Instant now) {
        return jpaRepository.markUsed(tokenId, now) == 1;
    }

    @Override
    public int revokeFamily(UUID familyId, Instant now) {
        return jpaRepository.revokeFamily(familyId, now);
    }
}
