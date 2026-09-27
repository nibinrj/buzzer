package dev.nibin.buzzer.quiz.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface QuizJpaRepository extends JpaRepository<QuizJpaEntity, UUID> {

    List<QuizJpaEntity> findByOwnerId(UUID ownerId);

    /**
     * Compare-and-set on the version: 1 if the quiz is still at {@code expectedVersion} (now incremented,
     * and the row stays locked until commit), 0 if it doesn't exist or someone else saved it first.
     * Same pattern as identity-service's refresh-token markUsed; @Transactional and clearAutomatically for
     * the same reasons (Spring Data's read-only default; a bulk UPDATE bypasses the first-level cache).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update QuizJpaEntity q set q.version = q.version + 1 where q.id = :id and q.version = :expectedVersion")
    int incrementVersion(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
