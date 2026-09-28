package dev.nibin.buzzer.session.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A live game of one published quiz, run by one host. Its questions are a copy taken when the session was
 * created: they never change afterwards, even if quiz-service is down or the quiz is deleted.
 * <p>
 * Immutable: status changes (start, end) return a new Session. Which question is running is live state
 * (Redis), not part of this record.
 */
public final class Session {

    public enum Status { LOBBY, IN_PROGRESS, ENDED }

    public static final int MAX_PLAYERS = 500;

    private final UUID id;
    private final RoomCode roomCode;
    private final UUID quizId;
    private final UUID hostId;
    private final String quizTitle;
    private final Status status;
    private final Instant createdAt;
    private final List<SessionQuestion> questions;

    /** Rehydrates a stored session. */
    public Session(UUID id, RoomCode roomCode, UUID quizId, UUID hostId, String quizTitle, Status status,
            Instant createdAt, List<SessionQuestion> questions) {
        this.id = Objects.requireNonNull(id, "id");
        this.roomCode = Objects.requireNonNull(roomCode, "roomCode");
        this.quizId = Objects.requireNonNull(quizId, "quizId");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.quizTitle = Objects.requireNonNull(quizTitle, "quizTitle");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.questions = List.copyOf(questions);
        if (this.questions.isEmpty()) {
            throw new IllegalArgumentException("a session needs at least one question");
        }
    }

    /** A new session in the lobby, waiting for players. */
    public static Session create(RoomCode roomCode, UUID quizId, UUID hostId, String quizTitle,
            List<SessionQuestion> questions, Instant now) {
        return new Session(UUID.randomUUID(), roomCode, quizId, hostId, quizTitle, Status.LOBBY, now, questions);
    }

    /**
     * Whether one more NEW player may join, given how many are in already. Only in the lobby: once questions
     * run, a newcomer would have missed some. Players already in are not new and are never checked here.
     *
     * @throws SessionNotJoinableException the session has started or ended
     * @throws SessionFullException        {@link #MAX_PLAYERS} are in already
     */
    public void checkJoinable(int currentPlayers) {
        if (status != Status.LOBBY) {
            throw new SessionNotJoinableException(status);
        }
        if (currentPlayers >= MAX_PLAYERS) {
            throw new SessionFullException();
        }
    }

    /** LOBBY → IN_PROGRESS. From here on no new players can join. */
    public Session start() {
        if (status != Status.LOBBY) {
            throw new SessionStateException("The session has already started.");
        }
        return withStatus(Status.IN_PROGRESS);
    }

    /**
     * The index of the question after {@code currentIndex}.
     *
     * @throws SessionStateException not running, or that was the last question
     */
    public int nextQuestionIndex(int currentIndex) {
        if (status != Status.IN_PROGRESS) {
            throw new SessionStateException("The session is not running. Start it first.");
        }
        if (currentIndex + 1 >= questions.size()) {
            throw new SessionStateException("That was the last question. End the session.");
        }
        return currentIndex + 1;
    }

    /** LOBBY or IN_PROGRESS → ENDED. A host may also end a session that never started. */
    public Session end() {
        if (status == Status.ENDED) {
            throw new SessionStateException("The session has already ended.");
        }
        return withStatus(Status.ENDED);
    }

    private Session withStatus(Status newStatus) {
        return new Session(id, roomCode, quizId, hostId, quizTitle, newStatus, createdAt, questions);
    }

    /** The same session under another room code: used when the first code turned out to be taken. */
    public Session withRoomCode(RoomCode newRoomCode) {
        return new Session(id, newRoomCode, quizId, hostId, quizTitle, status, createdAt, questions);
    }

    public UUID id() {
        return id;
    }

    public RoomCode roomCode() {
        return roomCode;
    }

    public UUID quizId() {
        return quizId;
    }

    public UUID hostId() {
        return hostId;
    }

    public String quizTitle() {
        return quizTitle;
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<SessionQuestion> questions() {
        return questions;
    }
}
