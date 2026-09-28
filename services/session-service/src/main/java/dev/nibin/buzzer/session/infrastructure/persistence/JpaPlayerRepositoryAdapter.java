package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the domain's PlayerRepository port with Spring Data JPA. Each method joins the caller's transaction
 * if there is one (JoinSession's locked transaction), or runs in its own.
 */
@Component
class JpaPlayerRepositoryAdapter implements PlayerRepository {

    private final PlayerJpaRepository jpaRepository;

    JpaPlayerRepositoryAdapter(PlayerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void add(Player player) {
        jpaRepository.save(new PlayerJpaEntity(player.playerId(), player.sessionId(), player.userId(),
                player.displayName(), player.joinedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Player> find(UUID sessionId, UUID userId) {
        return jpaRepository.findBySessionIdAndUserId(sessionId, userId).map(JpaPlayerRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public int count(UUID sessionId) {
        return Math.toIntExact(jpaRepository.countBySessionId(sessionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Player> findBySession(UUID sessionId) {
        return jpaRepository.findBySessionIdOrderByJoinedAt(sessionId).stream()
                .map(JpaPlayerRepositoryAdapter::toDomain)
                .toList();
    }

    private static Player toDomain(PlayerJpaEntity entity) {
        return new Player(entity.getId(), entity.getSessionId(), entity.getUserId(), entity.getDisplayName(),
                entity.getJoinedAt());
    }
}
