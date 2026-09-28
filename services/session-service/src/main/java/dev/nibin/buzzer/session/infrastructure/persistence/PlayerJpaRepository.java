package dev.nibin.buzzer.session.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every query filters on session_id first, so the unique (session_id, user_id) index serves all of them.
public interface PlayerJpaRepository extends JpaRepository<PlayerJpaEntity, UUID> {

    Optional<PlayerJpaEntity> findBySessionIdAndUserId(UUID sessionId, UUID userId);

    long countBySessionId(UUID sessionId);

    List<PlayerJpaEntity> findBySessionIdOrderByJoinedAt(UUID sessionId);
}
