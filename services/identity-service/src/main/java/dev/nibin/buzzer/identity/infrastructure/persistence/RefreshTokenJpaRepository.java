package dev.nibin.buzzer.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The two updates are single conditional UPDATE statements, not load-modify-save: the WHERE clause is
 * the check, so two concurrent callers can't both succeed. Postgres makes the second UPDATE wait for
 * the first one's row lock, then re-checks the WHERE clause against the committed row.
 * <p>
 * {@code @Transactional}: Spring Data runs query methods read-only by default, and Postgres rejects
 * an UPDATE in a read-only transaction. {@code clearAutomatically}: a bulk UPDATE bypasses Hibernate's
 * first-level cache, so entities already loaded in this transaction would be stale; clear them.
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshTokenJpaEntity t set t.usedAt = :now
            where t.id = :id and t.usedAt is null and t.revokedAt is null and t.expiresAt > :now
            """)
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshTokenJpaEntity t set t.revokedAt = :now
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
