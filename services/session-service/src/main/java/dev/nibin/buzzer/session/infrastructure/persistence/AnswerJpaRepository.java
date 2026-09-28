package dev.nibin.buzzer.session.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// Served by the unique (session_id, question_id, player_id) index.
public interface AnswerJpaRepository extends JpaRepository<AnswerJpaEntity, UUID> {

    Optional<AnswerJpaEntity> findBySessionIdAndQuestionIdAndPlayerId(UUID sessionId, UUID questionId, UUID playerId);
}
