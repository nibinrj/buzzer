package dev.nibin.buzzer.session.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for storing sessions. Use cases depend on this interface; the JPA adapter in infrastructure
 * implements it.
 */
public interface SessionRepository {

    /**
     * Stores a new session with its questions, in its own transaction.
     *
     * @throws RoomCodeTakenException if another session already has this room code (nothing is stored)
     */
    void add(Session session);

    Optional<Session> findById(UUID id);

    /**
     * Finds the session AND locks its row until the caller's transaction ends (must be called inside one).
     * Everything that checks-then-changes a session's membership or status takes this lock first, so those
     * changes happen one at a time per session: no 501st player, no join slipping past a start.
     */
    Optional<Session> findByRoomCodeForUpdate(RoomCode roomCode);

    /** Same lock as {@link #findByRoomCodeForUpdate}, found by id. Must be called inside a transaction. */
    Optional<Session> findByIdForUpdate(UUID id);

    /** Writes the session's new status, inside the caller's transaction (which should hold the row lock). */
    void updateStatus(Session session);
}
