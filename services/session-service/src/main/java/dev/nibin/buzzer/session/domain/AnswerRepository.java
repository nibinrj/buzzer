package dev.nibin.buzzer.session.domain;

import java.util.Optional;
import java.util.UUID;

/** Port for recording accepted answers (Postgres, the source of record). Implemented by the JPA adapter. */
public interface AnswerRepository {

    /**
     * Joins the caller's transaction, or runs in its own. A second answer of one player to one question, or a seq
     * used twice for one question, is rejected by the database.
     */
    void add(Answer answer);

    Optional<Answer> find(UUID sessionId, UUID questionId, UUID playerId);
}
