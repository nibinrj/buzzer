package dev.nibin.buzzer.session.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for storing players (Postgres, the source of record). Implemented by the JPA adapter. */
public interface PlayerRepository {

    /** Joins the caller's transaction. A second membership of one user in one session is rejected by the database. */
    void add(Player player);

    Optional<Player> find(UUID sessionId, UUID userId);

    int count(UUID sessionId);

    List<Player> findBySession(UUID sessionId);
}
