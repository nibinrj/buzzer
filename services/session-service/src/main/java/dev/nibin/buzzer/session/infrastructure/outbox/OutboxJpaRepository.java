package dev.nibin.buzzer.session.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxJpaEntity, Long> {

    /**
     * The oldest unsent rows, locked until the caller's transaction ends. SKIP LOCKED: rows another instance's
     * publisher has locked are skipped, not waited for, so two instances never send the same row at the same time.
     * Native SQL because JPQL has neither LIMIT nor SKIP LOCKED. Must be called inside a transaction.
     */
    @Query(value = "SELECT * FROM outbox WHERE sent_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxJpaEntity> lockOldestUnsent(@Param("limit") int limit);

    Optional<OutboxJpaEntity> findByEventId(UUID eventId);
}
