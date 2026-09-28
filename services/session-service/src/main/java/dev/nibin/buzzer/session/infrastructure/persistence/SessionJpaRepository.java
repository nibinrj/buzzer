package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.domain.Session;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SessionJpaRepository extends JpaRepository<SessionJpaEntity, UUID> {

    /**
     * SELECT ... FOR UPDATE (or PostgreSQL's lighter FOR NO KEY UPDATE, Hibernate's choice): the row stays locked
     * until the surrounding transaction ends, and anyone else asking for the same lock waits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SessionJpaEntity> findByRoomCode(String roomCode);

    /** The same lock, by id. An explicit query: the inherited findById can't carry @Lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SessionJpaEntity s where s.id = :id")
    Optional<SessionJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /** clearAutomatically: a bulk UPDATE bypasses the persistence context, so drop what it holds (now stale). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SessionJpaEntity s set s.status = :status where s.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") Session.Status status);
}
